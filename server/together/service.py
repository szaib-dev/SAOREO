"""Private two-person playback coordination and temporary room video sharing."""
import asyncio
import base64
import hashlib
import hmac
import json
import math
import os
import re
import secrets
import time
import tempfile
from pathlib import Path
from collections import defaultdict, deque
from dataclasses import dataclass, field
from urllib.parse import urlsplit

from aiohttp import web, WSMsgType
from pairing import Pairing
from relay import FileRelay
from games import Games

def now():
    return int(time.time() * 1000)

def clean(value, limit=80):
    return ''.join(c for c in str(value) if c >= ' ' and c != '\x7f')[:limit].strip()

def number(value, low=0, high=604800000):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise ValueError('Invalid number')
    return max(low, min(high, value))

def media_item(data):
    kind = data.get('kind')
    source = str(data.get('source', ''))
    if kind == 'youtube':
        if not re.fullmatch(r'[A-Za-z0-9_-]{11}', source):
            raise ValueError('Invalid YouTube video')
    elif kind == 'stream':
        u = urlsplit(source)
        if u.scheme != 'https' or not u.hostname or u.username or u.password or len(source) > 4096:
            raise ValueError('Use an HTTPS stream without credentials. Private sources stay on your phone.')
    elif kind == 'local':
        if not re.fullmatch(r'[a-f0-9]{64}', source):
            raise ValueError('Invalid local file fingerprint')
    elif kind in ('upload', 'relay'):
        if not re.fullmatch(r'[a-f0-9]{32}', source):
            raise ValueError('Invalid shared video')
    else:
        raise ValueError('Unsupported media type')
    return dict(id=secrets.token_hex(8), kind=kind, source=source,
                title=clean(data.get('title', ''), 150) or 'Watch together',
                duration=number(data.get('duration', 0)), live=bool(data.get('live', False)))

@dataclass
class Member:
    id: str
    token: str
    name: str
    ordinal: int
    ws: object = None
    ready: bool = False
    buffering: bool = False
    call: bool = False
    mic: bool = False
    camera: bool = False
    protocol: int = 1
    last_seq: int = 0
    last_seen: int = field(default_factory=now)
    slow_connection: bool | None = None
    game_client: bool = False

@dataclass
class Room:
    id: str
    invitation: str
    members: dict = field(default_factory=dict)
    media: object = None
    queue: list = field(default_factory=list)
    chat: deque = field(default_factory=lambda: deque(maxlen=80))
    request: object = None
    position: float = 0
    anchor: int = field(default_factory=now)
    wanted: bool = False
    playing: bool = False
    wait: bool = True
    call_fullscreen: bool = False
    revision: int = 0
    updated: int = field(default_factory=now)

    def position_at(self, stamp):
        return max(0, self.position + (stamp - self.anchor if self.playing else 0))

    def can_start(self, include_buffering=True):
        return bool(self.media and len(self.members) == 2 and all(
            m.ws is not None and m.ready and (not include_buffering or not self.wait or not m.buffering)
            for m in self.members.values()
        ))

    def settle(self):
        stamp = now()
        self.position = self.position_at(stamp)
        self.anchor = stamp
        self.playing = self.wanted and self.can_start()
        self.revision += 1
        self.updated = stamp

    def snapshot(self):
        stamp = now()
        return dict(type='state', room=self.id, revision=self.revision, serverTime=stamp,
                    position=self.position_at(stamp), playing=self.playing, wanted=self.wanted,
                    wait=self.wait, callFullscreen=self.call_fullscreen, media=self.media, queue=self.queue, request=self.request,
                    autoAdvanceQueue=True,
                    members=[dict(id=m.id, name=m.name, ordinal=m.ordinal, online=m.ws is not None,
                                  ready=m.ready, buffering=m.buffering, call=m.call, mic=m.mic,
                                  camera=m.camera, slowConnection=m.slow_connection) for m in self.members.values()])

class Together:
    def __init__(self, key=None, turn_secret=None, turn_host=None, pair_db=':memory:'):
        self.key = key or os.environ.get('TOGETHER_ACCESS_KEY', '')
        if len(self.key) < 16:
            raise RuntimeError('Set TOGETHER_ACCESS_KEY to a random private deployment key')
        self.turn_secret = turn_secret or os.environ.get('TOGETHER_TURN_SECRET', '')
        self.turn_host = turn_host or os.environ.get('TOGETHER_TURN_HOST', '')
        self.rooms = {}
        self.rates = defaultdict(deque)
        self.uploads = {}
        self.upload_lock = asyncio.Lock()
        self.upload_dir = None
        self.relay = FileRelay(self)
        self.pairing = Pairing(self, pair_db)
        self.games = Games(self)

    def authenticate_media(self, request):
        room = self.rooms.get(request.match_info['room'])
        token = request.headers.get('Authorization', '').removeprefix('Bearer ')
        member = next((m for m in room.members.values() if hmac.compare_digest(m.token, token)), None) if room else None
        if member is None:
            raise web.HTTPUnauthorized(text='Join this room to access its video')
        return room, member

    async def upload_video(self, request):
        room, member = self.authenticate_media(request)
        if any(m.ws is not None and m.protocol < 2 for m in room.members.values()):
            raise web.HTTPConflict(text='Both phones need SAOREO 2.9.1 or later to share a local video')
        self.limit(('upload', member.id), 4, 900)
        maximum = 100 * 1024 * 1024
        if request.content_length is not None and request.content_length > maximum:
            raise web.HTTPBadRequest(text='Choose an MP4 under 100 MiB')
        if self.upload_lock.locked() or len(self.uploads) >= 4:
            raise web.HTTPServiceUnavailable(text='Shared video storage is busy')
        identifier = secrets.token_hex(16)
        path = Path(self.upload_dir.name) / identifier
        async with self.upload_lock:
            total = 0
            try:
                async with asyncio.timeout(600):
                    with path.open('xb') as output:
                        header = await request.content.readexactly(12)
                        if header[4:8] != b'ftyp':
                            raise web.HTTPBadRequest(text='Choose an MP4 video')
                        output.write(header)
                        total = len(header)
                        async for chunk in request.content.iter_chunked(256 * 1024):
                            total += len(chunk)
                            if total > maximum:
                                raise web.HTTPBadRequest(text='Choose an MP4 under 100 MiB')
                            output.write(chunk)
                if self.rooms.get(room.id) is not room or member.id not in room.members:
                    raise web.HTTPNotFound(text='Room expired')
                self.uploads[identifier] = (room.id, path, now())
            except (asyncio.IncompleteReadError, asyncio.TimeoutError) as ex:
                raise web.HTTPBadRequest(text='Video upload was incomplete') from ex
            finally:
                if identifier not in self.uploads:
                    path.unlink(missing_ok=True)
        return web.json_response(dict(id=identifier))

    async def read_video(self, request):
        room, _ = self.authenticate_media(request)
        upload = self.uploads.get(request.match_info['id'])
        if not upload or upload[0] != room.id:
            raise web.HTTPNotFound(text='Shared video expired')
        return web.FileResponse(upload[1], headers={'Content-Type': 'video/mp4'})

    async def invitation_page(self, request):
        invite = request.query.get('invite', '')
        if not re.fullmatch(r'[a-f0-9]{12}\.[A-Za-z0-9_-]{32}', invite):
            raise web.HTTPNotFound(text='Invalid invitation')
        deep_link = f'intent://together/join?invite={invite}#Intent;scheme=saoreo;package=com.codetic.saoreo;end'
        return web.Response(content_type='text/html', text=f"""<!doctype html>
<html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Join SAOREO Together</title><style>
body{{font:1rem system-ui;background:#111115;color:#f4f3f6;display:grid;place-items:center;min-height:90vh}}
main{{max-width:24rem;padding:2rem}}a{{display:inline-block;background:#d5ef72;color:#19191e;padding:1rem 1.5rem;border-radius:2rem;font-weight:700;text-decoration:none}}
</style><main><h1>Join your watch room</h1><p>Open SAOREO on your Android phone to join your partner.</p>
<a href="{deep_link}">Open SAOREO</a></main></html>""")

    def limit(self, key, maximum, seconds):
        stamp = time.monotonic()
        bucket = self.rates[key]
        while bucket and bucket[0] < stamp - seconds:
            bucket.popleft()
        if len(bucket) >= maximum:
            raise web.HTTPTooManyRequests(text='Please wait before trying again')
        bucket.append(stamp)
        if len(self.rates) > 2000:
            for k in list(self.rates):
                if not self.rates[k] or self.rates[k][-1] < stamp - 3600:
                    del self.rates[k]

    def ip(self, request):
        # Service is bound to loopback behind a controlled reverse proxy.
        return request.headers.get('X-Forwarded-For', request.remote or '?').split(',')[0].strip()

    def member(self, room, name):
        token = secrets.token_urlsafe(32)
        member = Member(secrets.token_hex(8), token, clean(name, 32) or 'Listener', len(room.members))
        room.members[member.id] = member
        return member

    def ice(self, member):
        if not self.turn_secret or not self.turn_host:
            return []
        username = f'{int(time.time()) + 43200}:{member.id}'
        credential = base64.b64encode(hmac.new(self.turn_secret.encode(), username.encode(), hashlib.sha1).digest()).decode()
        return [dict(urls=[f'stun:{self.turn_host}:3478']),
                dict(urls=[f'turn:{self.turn_host}:3478?transport=udp', f'turn:{self.turn_host}:3478?transport=tcp'],
                     username=username, credential=credential)]

    def ticket(self, room, member):
        return dict(room=room.id, token=member.token, member=member.id,
                    invite=room.id + '.' + room.invitation, iceServers=self.ice(member))

    def new_room(self):
        if len(self.rooms) >= 8:
            raise web.HTTPServiceUnavailable(text='All private rooms are occupied')
        room = Room(secrets.token_hex(6), secrets.token_urlsafe(24))
        self.rooms[room.id] = room
        return room

    def paired_watch_host(self, room_id, token):
        room = self.rooms.get(str(room_id))
        host = next(iter(room.members.values()), None) if room else None
        if not host or not host.ws or not hmac.compare_digest(host.token.encode(), str(token).encode()):
            raise ValueError('Open your own watch room to invite your partner')
        if len(room.members) != 1:
            raise ValueError('This watch room already has a partner slot in use')
        return room, host

    def paired_room(self, caller, recipient, room_id=None, host_id=None):
        if room_id:
            room = self.rooms.get(room_id)
            host = room.members.get(host_id) if room else None
            if not host or not host.ws or len(room.members) != 1:
                raise ValueError('The watch room is no longer available')
        else:
            room = self.new_room()
            host = self.member(room, caller)
        return [self.ticket(room, host), self.ticket(room, self.member(room, recipient))]

    async def create(self, request):
        self.limit(('create', self.ip(request)), 10, 300)
        data = await request.json()
        if not hmac.compare_digest(str(data.get('key', '')), self.key):
            raise web.HTTPUnauthorized(text='Incorrect server access key')
        room = self.new_room()
        return web.json_response(self.ticket(room, self.member(room, data.get('name', ''))))

    async def join(self, request):
        self.limit(('join', self.ip(request)), 20, 300)
        data = await request.json()
        parts = str(data.get('invite', '')).split('.', 1)
        room = self.rooms.get(parts[0])
        if len(parts) != 2 or not room or not hmac.compare_digest(room.invitation, parts[1]):
            raise web.HTTPNotFound(text='Invitation is invalid or expired')
        # A disconnected partner retains their slot for reconnect. Host can explicitly free it.
        if len(room.members) >= 2:
            raise web.HTTPConflict(text='This room already has two people. Ask the host to reset the partner slot.')
        return web.json_response(self.ticket(room, self.member(room, data.get('name', ''))))

    async def broadcast(self, room, payload):
        for m in list(room.members.values()):
            if m.ws is not None and not m.ws.closed:
                try:
                    await asyncio.wait_for(m.ws.send_json(payload), 3)
                except (ConnectionError, asyncio.TimeoutError, RuntimeError):
                    await m.ws.close()

    async def command(self, room, m, data):
        op = data.get('type')
        room.updated = m.last_seen = now()
        if op == 'game':
            try:
                self.limit(('game', room.id, m.id), 58, 4)
                await self.games.command(room, m, data)
            except (ValueError, TypeError, KeyError, web.HTTPException) as error:
                await m.ws.send_json(dict(type='gameError', message=str(error)[:180]))
            return
        if op == 'fileChunk':
            await self.relay.chunk(room, m, data)
            return
        if op == 'ping':
            await m.ws.send_json(dict(type='pong', sent=data.get('sent'), serverTime=now()))
            slow = data.get('slowConnection')
            if type(slow) is bool and slow != m.slow_connection:
                m.slow_connection = slow
                await self.broadcast(room, dict(type='network', member=m.id, slow=slow))
            return
        if op == 'signal':
            payload = data.get('payload')
            if not isinstance(payload, dict) or len(json.dumps(payload)) > 24000:
                raise ValueError('Invalid call signal')
            for peer in room.members.values():
                if peer.id != m.id and peer.ws is not None and peer.call and m.call:
                    await peer.ws.send_json(dict(type='signal', sender=m.id, payload=payload))
            return
        if op == 'chat':
            text = clean(data.get('text', ''), 2000)
            if text:
                event = dict(type='chat', id=secrets.token_hex(8), sender=m.id, name=m.name, text=text, time=now())
                room.chat.append(event)
                await self.broadcast(room, event)
            return
        if op == 'reaction':
            if data.get('emoji') not in ['heart', 'laugh', 'wow', 'clap', 'fire', 'kiss']:
                raise ValueError('Unknown reaction')
            await self.broadcast(room, dict(type='reaction', sender=m.id, emoji=data['emoji'], time=now()))
            return
        if op == 'request':
            kind = data.get('kind')
            if kind not in ('voice', 'video', 'watch'):
                raise ValueError('Unknown request')
            peer = next((p for p in room.members.values() if p.id != m.id and p.ws is not None), None)
            if peer is None:
                raise ValueError('Your partner needs to open Together first')
            if peer.protocol < 2:
                raise ValueError('Your partner needs SAOREO 2.9.1 to receive requests')
            if room.request and room.request['expires'] > now():
                raise ValueError('A request is already ringing')
            room.request = dict(id=secrets.token_hex(8), kind=kind, sender=m.id, recipient=peer.id, expires=now()+30000)
            await self.broadcast(room, room.snapshot())
            return
        if op == 'answer':
            ring = room.request
            if not ring or ring['id'] != data.get('id') or ring['expires'] <= now() or ring['recipient'] != m.id:
                raise ValueError('This request has expired')
            room.request = None
            ring = dict(ring, type='requestResult', accepted=data.get('accepted') is True)
            await self.broadcast(room, ring)
            await self.broadcast(room, room.snapshot())
            return
        if op == 'finished' and (
            not room.media or room.media['id'] != data.get('mediaId') or not room.wanted or room.media.get('live')
        ):
            return
        if op in ('play', 'pause', 'seek', 'finished', 'media', 'queueAdd', 'queueSelect', 'queueRemove', 'wait', 'resetPartner'):
            seq = int(number(data.get('seq', 0), high=2**53))
            if seq <= m.last_seq:
                await m.ws.send_json(room.snapshot())
                return
            m.last_seq = seq
        # Preserve current position before changing playback state.
        room.position = room.position_at(now())
        room.anchor = now()
        if op in ('play', 'pause', 'seek'):
            if not room.media or data.get('mediaId') != room.media['id']:
                raise ValueError('The video changed. Try again.')
            if op in ('play', 'seek') and not room.can_start(include_buffering=False):
                raise ValueError('Wait for both players to be ready.')
            if op == 'play':
                if not room.wanted:
                    for participant in room.members.values():
                        participant.buffering = False
                room.wanted = True
            elif op == 'pause':
                room.wanted = False
                for participant in room.members.values():
                    participant.buffering = False
            else: room.position = number(data.get('position'))
        elif op in ('media', 'queueAdd'):
            item = media_item(data.get('media', {}))
            if item['kind'] == 'relay' and (not self.relay.valid(room, item['source']) or any(p.ws is not None and p.protocol < 3 for p in room.members.values())):
                raise ValueError('Update both phones to SAOREO 2.9.2 and reselect the local video')
            if item['kind'] == 'upload' and self.uploads.get(item['source'], (None,))[0] != room.id:
                raise ValueError('Shared video expired')
            if item['kind'] == 'upload' and any(p.ws is not None and p.protocol < 2 for p in room.members.values()):
                raise ValueError('Both phones need SAOREO 2.9.1 or later to share a local video')
            if item['kind'] == 'local':
                raise ValueError('Update SAOREO and select Local video to share one copy with your partner')
            if op == 'media':
                room.media = item
                room.position = 0
                room.wanted = False
                for member in room.members.values(): member.ready = False; member.buffering = False
            else:
                if len(room.queue) >= 20: raise ValueError('Queue is full')
                room.queue.append(item)
        elif op == 'finished':
            room.wanted = bool(room.queue)
            if room.wanted:
                room.media = room.queue.pop(0)
                room.position = 0
                for member in room.members.values():
                    member.ready = False
                    member.buffering = False
            else:
                for member in room.members.values():
                    member.buffering = False
        elif op == 'queueSelect':
            item = next((x for x in room.queue if x['id'] == data.get('id')), None)
            if not item: raise ValueError('Queue item is no longer available')
            room.media = item; room.queue.remove(item); room.position = 0; room.wanted = False
            for member in room.members.values(): member.ready = False; member.buffering = False
        elif op == 'queueRemove':
            room.queue = [x for x in room.queue if x['id'] != data.get('id')]
        elif op == 'status':
            if not room.media or data.get('mediaId') != room.media['id']: return
            m.ready = data.get('ready') is True
            m.buffering = room.wanted and data.get('buffering') is True
        elif op == 'callView':
            enabled = data.get('enabled') is True
            if enabled and not (len(room.members) == 2 and all(p.ws is not None and p.call and p.protocol >= 3 for p in room.members.values())):
                raise ValueError('Both participants must join the call and update SAOREO before opening full call')
            room.call_fullscreen = enabled
        elif op == 'call':
            m.call = data.get('active') is True
            m.mic = m.call and data.get('mic') is True
            m.camera = m.call and data.get('camera') is True
            if not m.call: room.call_fullscreen = False
        elif op == 'wait':
            room.wait = data.get('enabled') is True
        elif op == 'resetPartner':
            if m.ordinal != 0: raise ValueError('Only the room creator can reset the partner slot')
            for mid, peer in list(room.members.items()):
                if mid != m.id:
                    if peer.ws is not None: await peer.ws.close(code=4001, message=b'Partner slot reset by host')
                    del room.members[mid]
            room.invitation = secrets.token_urlsafe(24)
            room.wanted = False
            await m.ws.send_json(dict(type='invite', invite=room.id + '.' + room.invitation))
        elif op == 'leave':
            socket = m.ws
            self.relay.disconnected(m)
            room.call_fullscreen = False
            room.members.pop(m.id, None)
            m.ws = None
            room.wanted = False
            room.request = None
            if m.ordinal == 0:
                for remaining in room.members.values(): remaining.ordinal = 0
            if not room.members:
                self.rooms.pop(room.id, None)
            else:
                room.invitation = secrets.token_urlsafe(24)
                room.settle()
                await self.broadcast(room, room.snapshot())
                await self.broadcast(room, dict(type='invite', invite=room.id + '.' + room.invitation))
            await socket.close(code=1000)
            return
        else:
            raise ValueError('Unknown room action')
        room.settle()
        await self.broadcast(room, room.snapshot())

    async def socket(self, request):
        self.limit(('ws', self.ip(request)), 40, 300)
        ws = web.WebSocketResponse(heartbeat=20, receive_timeout=90, max_msg_size=196608, compress=False)
        await ws.prepare(request)
        room = member = None
        try:
            hello = await asyncio.wait_for(ws.receive_json(), 10)
            room = self.rooms.get(str(hello.get('room', '')))
            if not room: raise ValueError('Room expired')
            member = next((m for m in room.members.values() if hmac.compare_digest(m.token, str(hello.get('token', '')))), None)
            if not member: raise ValueError('Invalid room session')
            member.protocol = int(number(hello.get('protocol', 1), low=1, high=3))
            if room.media and (member.protocol < 2 and room.media['kind'] == 'upload' or member.protocol < 3 and room.media['kind'] == 'relay'):
                await ws.send_json(dict(type='error', message='Update SAOREO to play shared local videos'))
                await ws.close(code=4003)
                return ws
            if member.ws is not None: await member.ws.close(code=4000, message=b'Reconnected')
            member.ws = ws; member.last_seq = 0; member.ready = False; member.buffering = False
            if hello.get('callActive') is False and member.call:
                member.call = member.mic = member.camera = False
                room.call_fullscreen = False
            member.game_client = hello.get('games') == 1
            member.slow_connection = None
            room.settle()
            await ws.send_json(dict(type='history', messages=list(room.chat)))
            await self.broadcast(room, room.snapshot())
            await self.games.sync(room, member)
            async for msg in ws:
                if msg.type != WSMsgType.TEXT: break
                try:
                    data = json.loads(msg.data)
                    if not isinstance(data, dict): raise ValueError('Invalid message')
                    if data.get('type') != 'game':
                        self.limit(('chunks' if data.get('type') == 'fileChunk' else 'member', room.id, member.id), 400 if data.get('type') == 'fileChunk' else 160, 10)
                    await self.command(room, member, data)
                except (ValueError, TypeError, KeyError, web.HTTPException) as ex:
                    await ws.send_json(dict(type='error', message=str(ex)[:180]))
        except (ValueError, asyncio.TimeoutError, TypeError):
            await ws.close(code=4003, message=b'Invalid or expired room session')
        finally:
            if member is not None and member.ws is ws:
                self.relay.disconnected(member)
                # The authenticated member can resume after a transport failure.
                # Offline members still prevent playback until their players report ready again.
                member.ws = None; member.ready = False; member.buffering = False
                member.slow_connection = None
                if room and room.id in self.rooms:
                    room.request = None
                    room.settle()
                    await self.broadcast(room, room.snapshot())
        return ws

    async def cleanup(self, app):
        self.upload_dir = tempfile.TemporaryDirectory(prefix='saoreo-watch-')
        async def loop():
            while True:
                await asyncio.sleep(60)
                for rid, room in list(self.rooms.items()):
                    if now() - room.updated > 1800000:
                        for m in room.members.values():
                            if m.ws is not None: await m.ws.close(code=4004, message=b'Room expired')
                        self.rooms.pop(rid, None)
                self.relay.cleanup()
                for identifier, (room_id, path, created) in list(self.uploads.items()):
                    if room_id not in self.rooms or now() - created > 14_400_000:
                        self.uploads.pop(identifier)
                        path.unlink(missing_ok=True)
        task = asyncio.create_task(loop())
        games = asyncio.create_task(self.games.run())
        yield
        task.cancel()
        games.cancel()
        await asyncio.gather(task, games, return_exceptions=True)
        for room in list(self.rooms.values()):
            for m in room.members.values():
                if m.ws is not None: await m.ws.close(code=1001)
        self.upload_dir.cleanup()

    def app(self):
        async def health(request):
            return web.json_response(dict(status='ok', version='1.2', protocol=3, features=['rangeRelay', 'sharedFullCall', 'pairing']))
        async def asset_links(request):
            return web.json_response([{
                'relation': ['delegate_permission/common.handle_all_urls'],
                'target': {
                    'namespace': 'android_app',
                    'package_name': 'com.codetic.saoreo',
                    'sha256_cert_fingerprints': [
                        'C0:C0:50:AE:68:B0:98:FB:89:2F:F2:E9:7D:BB:0A:5D:BE:92:81:FF:EC:0C:5F:C8:63:12:F1:5A:1B:34:1D:6F'
                    ],
                },
            }])
        @web.middleware
        async def errors(request, handler):
            try:
                response = await handler(request)
            except web.HTTPException as e:
                response = web.json_response(dict(error=e.text), status=e.status)
            except (ValueError, TypeError, KeyError, json.JSONDecodeError):
                response = web.json_response(dict(error='Invalid request'), status=400)
            response.headers['Cache-Control'] = 'no-store'
            response.headers['X-Content-Type-Options'] = 'nosniff'
            return response
        app = web.Application(client_max_size=16384, middlewares=[errors])
        app.router.add_get('/.well-known/assetlinks.json', asset_links)
        app.router.add_get('/together/health', health)
        app.router.add_post('/together/api/rooms', self.create)
        app.router.add_post('/together/api/join', self.join)
        app.router.add_get('/together/join', self.invitation_page)
        app.router.add_post('/together/media/{room}', self.upload_video)
        app.router.add_get('/together/media/{room}/{id}', self.read_video)
        app.router.add_post('/together/share/{room}', self.relay.register)
        app.router.add_get('/together/share/{room}/{id}', self.relay.read)
        app.router.add_get('/together/ws', self.socket)
        app.cleanup_ctx.append(self.cleanup)
        self.pairing.routes(app)
        return app

if __name__ == '__main__':
    database = os.environ.get('TOGETHER_PAIR_DB', 'pairs.sqlite3')
    web.run_app(Together(pair_db=database).app(), host='127.0.0.1', port=8770, access_log=None, print=None, handler_cancellation=True)
