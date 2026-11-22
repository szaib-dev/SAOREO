"""Bounded, room-authenticated byte-range relay. No movie is stored on the server."""
import asyncio
import base64
import re
import secrets
from dataclasses import dataclass
from aiohttp import web

CHUNK = 128 * 1024
MAX_FILE = 20 * 1024**3


def byte_range(value, size):
    if value is None:
        return 0, size - 1, False
    match = re.fullmatch(r'bytes=(\d*)-(\d*)', value)
    if not match or not any(match.groups()):
        raise ValueError('Invalid range')
    first, last = match.groups()
    if not first:
        suffix = int(last)
        if suffix <= 0:
            raise ValueError('Invalid range')
        return max(0, size - suffix), size - 1, True
    start, end = int(first), int(last) if last else size - 1
    if start >= size or end < start:
        raise ValueError('Range outside file')
    return start, min(end, size - 1), True


@dataclass
class Share:
    room: str
    owner: str
    size: int
    mime: str


class FileRelay:
    def __init__(self, service):
        self.service = service
        self.shares = {}
        self.pending = {}
        self.slots = asyncio.Semaphore(8)
        self.readers = 0

    async def register(self, request):
        room, member = self.service.authenticate_media(request)
        if member.ws is None or any(m.protocol < 3 for m in room.members.values() if m.ws is not None):
            raise web.HTTPConflict(text='Update both phones to SAOREO 2.9.2 for local streaming')
        self.service.limit(('share', member.id), 20, 60)
        data = await request.json()
        size, mime = data.get('size'), data.get('mime')
        if type(size) is not int or not 0 < size <= MAX_FILE or mime not in ('video/mp4', 'video/webm', 'video/x-matroska'):
            raise web.HTTPBadRequest(text='Choose a readable MP4, WebM or MKV video under 20 GiB')
        if sum(s.room == room.id for s in self.shares.values()) >= 20 or len(self.shares) >= 100:
            raise web.HTTPServiceUnavailable(text='Shared source limit reached; start a new room')
        identifier = secrets.token_hex(16)
        self.shares[identifier] = Share(room.id, member.id, size, mime)
        return web.json_response(dict(id=identifier))

    def valid(self, room, identifier):
        share = self.shares.get(identifier)
        return share is not None and share.room == room.id

    async def chunk(self, room, member, data):
        entry = self.pending.get(str(data.get('request', '')))
        if entry is None:
            return  # Late or cancelled request.
        share, length, future = entry
        if share.room != room.id or share.owner != member.id:
            raise ValueError('This file request belongs to another source')
        if future.done():
            return
        if data.get('error'):
            future.set_exception(OSError('The source phone could not read this part of the video'))
            return
        payload = data.get('data')
        if not isinstance(payload, str) or len(payload) > 4 * ((CHUNK + 2) // 3):
            raise ValueError('Invalid video chunk')
        try:
            decoded = base64.b64decode(payload, validate=True)
        except ValueError as ex:
            raise ValueError('Invalid video chunk') from ex
        if len(decoded) != length:
            future.set_exception(OSError('The source video changed or could not be read completely'))
        else:
            future.set_result(decoded)

    async def fetch(self, room, share, identifier, offset, length):
        async with self.slots:
            owner = room.members.get(share.owner)
            if owner is None or owner.ws is None:
                raise OSError('The source phone is offline')
            future = asyncio.get_running_loop().create_future()
            key = secrets.token_hex(16)
            self.pending[key] = share, length, future
            try:
                await owner.ws.send_json(dict(type='fileRead', request=key, source=identifier, offset=offset, length=length))
                return await asyncio.wait_for(future, 15)
            finally:
                self.pending.pop(key, None)
                future.cancel()

    async def read(self, request):
        room, _ = self.service.authenticate_media(request)
        identifier = request.match_info['id']
        share = self.shares.get(identifier)
        if share is None or share.room != room.id:
            raise web.HTTPNotFound(text='Shared source expired')
        if self.readers >= 8:
            raise web.HTTPServiceUnavailable(text='Video relay is busy')
        try:
            start, end, partial = byte_range(request.headers.get('Range'), share.size)
        except ValueError:
            raise web.HTTPRequestRangeNotSatisfiable(headers={'Content-Range': f'bytes */{share.size}'})
        headers = {'Content-Type': share.mime, 'Content-Length': str(end - start + 1), 'Accept-Ranges': 'bytes',
                   'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff'}
        if partial:
            headers['Content-Range'] = f'bytes {start}-{end}/{share.size}'
        response = web.StreamResponse(status=206 if partial else 200, headers=headers)
        if request.method == 'HEAD':
            await response.prepare(request)
            return response
        self.readers += 1
        prepared = False
        try:
            offset = start
            while offset <= end:
                if request.transport is None or request.transport.is_closing():
                    break
                chunk = await self.fetch(room, share, identifier, offset, min(CHUNK, end - offset + 1))
                if not prepared:
                    await response.prepare(request)
                    prepared = True
                await response.write(chunk)  # Await downstream backpressure before requesting another chunk.
                offset += len(chunk)
            if prepared:
                await response.write_eof()
            return response
        except (OSError, asyncio.TimeoutError, ConnectionError) as error:
            if not prepared:
                raise web.HTTPServiceUnavailable(text='Keep the source phone in this room to stream its video') from error
            if request.transport is not None:
                request.transport.close()  # Preserve Content-Length; never pretend a truncated movie completed.
            return response
        finally:
            self.readers -= 1

    def disconnected(self, member):
        for share, _, future in list(self.pending.values()):
            if share.owner == member.id and not future.done():
                future.set_exception(OSError('Source phone disconnected'))

    def cleanup(self):
        for identifier, share in list(self.shares.items()):
            room = self.service.rooms.get(share.room)
            if room is None or share.owner not in room.members:
                self.shares.pop(identifier, None)
