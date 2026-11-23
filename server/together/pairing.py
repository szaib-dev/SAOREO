"""Persistent pairing credentials and short-lived, authenticated call invitations."""
import asyncio
import hashlib
import hmac
import html
import re
import secrets
import sqlite3
import time
from contextlib import closing
from dataclasses import dataclass
from urllib.parse import quote

from aiohttp import WSMsgType, web
from fcm import FcmSender


def timestamp():
    return int(time.time() * 1000)


def credential_hash(value):
    if not isinstance(value, str) or not re.fullmatch(r'[A-Za-z0-9_-]{43}', value):
        raise web.HTTPUnauthorized(text='Invalid pairing credential')
    return hashlib.sha256(value.encode()).hexdigest()


@dataclass(frozen=True)
class Ring:
    id: str
    sender: str
    recipient: str
    kind: str
    expires: int
    room: str | None = None
    host: str | None = None


class Pairing:
    def __init__(self, service, database):
        self.service = service
        self.database = database
        self.sockets = {}
        self.rings = {}
        self.timers = {}
        self.launches = {}
        self.fcm = FcmSender()
        self.received = set()
        self.push_tasks = set()

    async def run(self, app):
        with closing(sqlite3.connect(self.database)) as self.db:
            self.db.executescript('''
                CREATE TABLE IF NOT EXISTS device (
                    id TEXT PRIMARY KEY, name TEXT NOT NULL, peer TEXT UNIQUE
                );
                CREATE TABLE IF NOT EXISTS invite (
                    code TEXT PRIMARY KEY, owner TEXT NOT NULL, expires INTEGER NOT NULL, accepted TEXT
                );
                CREATE TABLE IF NOT EXISTS push_token (
                    device TEXT PRIMARY KEY, token TEXT UNIQUE NOT NULL
                );
            ''')
            yield

    async def shutdown(self, app):
        tasks = list(self.timers.values()) + list(self.push_tasks)
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        sockets = list(self.sockets.values())
        await asyncio.gather(*(ws.close(code=1001) for ws in sockets), return_exceptions=True)

    def device_id(self, request):
        return credential_hash(request.headers.get('Authorization', '').removeprefix('Bearer '))

    def device(self, identifier):
        return self.db.execute('SELECT name, peer FROM device WHERE id=?', (identifier,)).fetchone()

    def name(self, data):
        return ''.join(c for c in str(data.get('name', '')) if c >= ' ' and c != '\x7f')[:32].strip() or 'Partner'

    def link(self, identifier, code):
        item = self.db.execute('SELECT owner, accepted FROM invite WHERE code=? AND expires>?',
                               (credential_hash(code), timestamp())).fetchone()
        if not item or item[1] not in (None, identifier):
            raise web.HTTPNotFound(text='This pairing link expired or was already used')
        if item[0] == identifier:
            raise web.HTTPConflict(text='Open this pairing link on your partner’s phone')
        return item

    async def invite(self, request):
        self.service.limit(('pair-invite', self.service.ip(request)), 12, 300)
        identifier = self.device_id(request)
        data = await request.json()
        if not hmac.compare_digest(str(data.get('key', '')).encode(), self.service.key.encode()):
            raise web.HTTPUnauthorized(text='Incorrect server access key')
        current = self.device(identifier)
        if current and current[1]:
            raise web.HTTPConflict(text='Unpair your current partner before creating another link')
        code = secrets.token_urlsafe(32)
        with self.db:
            self.db.execute('INSERT INTO device (id, name) VALUES (?, ?) ON CONFLICT(id) DO UPDATE SET name=excluded.name',
                            (identifier, self.name(data)))
            self.db.execute('DELETE FROM invite WHERE owner=? OR expires<=?', (identifier, timestamp()))
            self.db.execute('INSERT INTO invite VALUES (?, ?, ?, NULL)', (credential_hash(code), identifier, timestamp() + 86_400_000))
        return web.json_response(dict(code=code))

    async def preview(self, request):
        self.service.limit(('pair-preview', self.service.ip(request)), 30, 300)
        data = await request.json()
        owner, _ = self.link(self.device_id(request), data.get('code'))
        return web.json_response(dict(name=self.device(owner)[0]))

    async def accept(self, request):
        self.service.limit(('pair-accept', self.service.ip(request)), 20, 300)
        identifier = self.device_id(request)
        data = await request.json()
        owner, accepted = self.link(identifier, data.get('code'))
        inviter = self.device(owner)
        current = self.device(identifier)
        if accepted is None:
            if inviter[1] or current and current[1]:
                raise web.HTTPConflict(text='One of these devices is already paired')
            with self.db:
                self.db.execute('''INSERT INTO device VALUES (?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET name=excluded.name, peer=excluded.peer''', (identifier, self.name(data), owner))
                self.db.execute('UPDATE device SET peer=? WHERE id=?', (identifier, owner))
                self.db.execute('UPDATE invite SET accepted=? WHERE code=?', (identifier, credential_hash(data['code'])))
        elif not current or current[1] != owner:
            raise web.HTTPNotFound(text='This pairing link has already been used')
        await self.send_status(owner)
        await self.send_status(identifier)
        return web.json_response(dict(name=inviter[0]))

    async def unpair(self, request):
        self.service.limit(('pair-unpair', self.service.ip(request)), 20, 300)
        identifier = self.device_id(request)
        current = self.device(identifier)
        if not current:
            raise web.HTTPUnauthorized(text='Create a pairing link first')
        peer = current[1]
        with self.db:
            self.db.execute('UPDATE device SET peer=NULL WHERE id IN (?, ?)', (identifier, peer))
            self.db.execute('DELETE FROM invite WHERE owner IN (?, ?)', (identifier, peer))
        self.launches.pop(identifier, None)
        self.launches.pop(peer, None)
        ring = self.rings.get(identifier)
        if ring:
            await self.resolve(ring, 'cancelled')
        await self.send_status(identifier)
        if peer:
            await self.send_status(peer)
        return web.json_response({})

    async def page(self, request):
        code = request.query.get('code')
        credential_hash(code)
        server = quote('https://' + request.host, safe='')
        intent = html.escape(f'intent://together/pair?code={code}&server={server}#Intent;scheme=saoreo;package=com.codetic.saoreo;end')
        return web.Response(text=f'''<!doctype html><html lang="en"><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1"><title>Pair with SAOREO</title>
            <h1>Pair your device</h1><p>Open SAOREO to review and accept the pairing request.</p>
            <p><a id="open" href="{intent}">Open SAOREO</a></p>
            <script>
            const secret = location.hash.slice(1);
            if (/^[A-Za-z0-9_-]{{43}}$/.test(secret)) {{
                const link = document.getElementById('open');
                link.href = link.getAttribute('href').replace(';end', ';S.callSecret=' + secret + ';end');
            }}
            </script></html>''', content_type='text/html', headers={'Referrer-Policy': 'no-referrer', 'Cache-Control': 'no-store'})

    def online(self, identifier):
        ws = self.sockets.get(identifier)
        return ws is not None and not ws.closed

    async def send(self, identifier, data):
        if self.online(identifier):
            try:
                async with asyncio.timeout(3):
                    await self.sockets[identifier].send_json(data)
            except (OSError, RuntimeError, asyncio.TimeoutError):
                pass

    def status(self, identifier):
        current = self.device(identifier)
        if current is None:
            raise web.HTTPUnauthorized(text='Pair this device first')
        peer = current[1]
        partner = self.device(peer) if peer else None
        ring = self.rings.get(identifier)
        return dict(type='pairState', partner=partner[0] if partner else None,
            online=self.online(peer), ring=dict(id=ring.id, kind=ring.kind, incoming=ring.recipient == identifier,
                delivered=ring.id in self.received,
                remaining=max(0, ring.expires - timestamp())) if ring else None)

    async def send_status(self, identifier):
        if self.device(identifier):
            await self.send(identifier, self.status(identifier))

    async def push_registration(self, request):
        identifier = self.device_id(request)
        self.status(identifier)
        self.service.limit(('push-registration', identifier), 30, 300)
        data = await request.json()
        token = data.get('token')
        if not isinstance(token, str) or not 20 <= len(token) <= 4096 or not re.fullmatch(r'[A-Za-z0-9_:.-]+', token):
            raise web.HTTPBadRequest(text='Invalid push token')
        with self.db:
            self.db.execute('DELETE FROM push_token WHERE device=? OR token=?', (identifier, token))
            if data.get('enabled') is True:
                self.db.execute('INSERT INTO push_token VALUES (?, ?)', (identifier, token))
        return web.json_response({'registered': data.get('enabled') is True})

    async def current_state(self, request):
        return web.json_response(self.status(self.device_id(request)))

    async def received_call(self, request):
        identifier = self.device_id(request)
        self.status(identifier)
        data = await request.json()
        ring = self.rings.get(identifier)
        if ring and ring.recipient == identifier and ring.id == data.get('id') and ring.expires > timestamp():
            if ring.id not in self.received:
                self.received.add(ring.id)
                await self.send_status(ring.sender)
        return web.json_response({'ok': True})

    async def answer_call(self, request):
        identifier = self.device_id(request)
        self.status(identifier)
        data = await request.json()
        data['type'] = 'answer'
        try:
            await self.command(identifier, data)
        except ValueError as error:
            raise web.HTTPConflict(text=str(error)) from error
        return web.json_response({'ok': True})

    async def wake_recipient(self, ring):
        entry = self.db.execute('SELECT token FROM push_token WHERE device=?', (ring.recipient,)).fetchone()
        if entry:
            await self.fcm.send(entry[0], ring.id, (ring.expires - timestamp()) // 1000)

    async def resolve(self, ring, outcome, tickets=None):
        self.received.discard(ring.id)
        if self.rings.get(ring.sender) != ring:
            return
        participants = (ring.sender, ring.recipient)
        for member in participants:
            self.rings.pop(member, None)
        task = self.timers.pop(ring.id, None)
        if task and task is not asyncio.current_task():
            task.cancel()
        if tickets:
            self.launches = {member: launch for member, launch in self.launches.items() if launch[0] > timestamp()}
            for member, ticket in zip(participants, tickets):
                self.launches[member] = (timestamp() + 45_000, dict(
                    type='pairLaunch', id=ring.id, kind=ring.kind.removesuffix('_silent'), ticket=ticket))
        await asyncio.gather(*(self.send(member, dict(type='ringResult', id=ring.id, outcome=outcome)) for member in participants))
        await asyncio.gather(*(self.send_status(member) for member in participants))
        if tickets:
            await asyncio.gather(*(self.launch(member) for member in participants))

    async def launch(self, identifier):
        pending = self.launches.get(identifier)
        if pending and pending[0] > timestamp():
            await self.send(identifier, pending[1])
        else:
            self.launches.pop(identifier, None)

    async def expire(self, ring):
        await asyncio.sleep(30)
        await self.resolve(ring, 'missed')

    async def command(self, identifier, data):
        op = data.get('type')
        if op == 'ack':
            pending = self.launches.get(identifier)
            if pending and pending[1]['id'] == data.get('id'):
                self.launches.pop(identifier, None)
            return
        if op == 'ring':
            partner = self.device(identifier)[1]
            if not partner:
                raise ValueError('Pair a partner before sending a request')
            if identifier in self.rings or partner in self.rings:
                raise ValueError('A request is already ringing')
            kind = data.get('kind')
            if kind not in ('audio', 'video', 'watch', 'audio_silent', 'video_silent'):
                raise ValueError('Unknown request type')
            room = host = None
            if kind == 'watch' and data.get('room'):
                room, host = self.service.paired_watch_host(data['room'], data.get('roomToken', ''))
            ring = Ring(secrets.token_hex(8), identifier, partner, kind, timestamp() + 30_000,
                        room.id if room else None, host.id if host else None)
            self.rings[identifier] = self.rings[partner] = ring
            self.timers[ring.id] = asyncio.create_task(self.expire(ring))
            await asyncio.gather(self.send_status(identifier), self.send_status(partner))
            task = asyncio.create_task(self.wake_recipient(ring))
            self.push_tasks.add(task)
            task.add_done_callback(self.push_tasks.discard)
            return
        ring = self.rings.get(identifier)
        if not ring or ring.id != data.get('id') or ring.expires <= timestamp():
            raise ValueError('This request has expired')
        if op == 'cancel' and ring.sender == identifier:
            await self.resolve(ring, 'cancelled')
        elif op == 'answer' and ring.recipient == identifier:
            if data.get('accepted') is True:
                if not self.online(ring.sender):
                    await self.resolve(ring, 'cancelled')
                    raise ValueError('Your partner disconnected')
                tickets = self.service.paired_room(self.device(ring.sender)[0], self.device(ring.recipient)[0], ring.room, ring.host)
                await self.resolve(ring, 'accepted', tickets)
            else:
                await self.resolve(ring, 'declined')
        else:
            raise ValueError('Only the recipient can answer this request')

    async def socket(self, request):
        self.service.limit(('pair-socket', self.service.ip(request)), 45, 300)
        identifier = self.device_id(request)
        current = self.device(identifier)
        if not current:
            raise web.HTTPUnauthorized(text='Create or accept a pairing link first')
        ws = web.WebSocketResponse(heartbeat=20, receive_timeout=90, max_msg_size=4096)
        await ws.prepare(request)
        previous = self.sockets.get(identifier)
        self.sockets[identifier] = ws
        if previous:
            await previous.close(code=4000, message=b'Reconnected')
        try:
            await self.send_status(identifier)
            if current[1]:
                await self.send_status(current[1])
            await self.launch(identifier)
            async for item in ws:
                if item.type != WSMsgType.TEXT or self.sockets.get(identifier) is not ws:
                    break
                try:
                    self.service.limit(('pair-command', identifier), 40, 30)
                    data = item.json()
                    if not isinstance(data, dict):
                        raise ValueError('Invalid pairing command')
                    await self.command(identifier, data)
                except (ValueError, web.HTTPException) as error:
                    await self.send(identifier, dict(type='error', message=error.text if isinstance(error, web.HTTPException) else str(error)))
        finally:
            if self.sockets.get(identifier) is ws:
                self.sockets.pop(identifier, None)
                current = self.device(identifier)
                if current and current[1]:
                    await self.send_status(current[1])
        return ws

    def routes(self, app):
        app.cleanup_ctx.append(self.run)
        app.on_shutdown.append(self.shutdown)
        app.router.add_get('/together/pair', self.page)
        app.router.add_post('/together/api/pair/push', self.push_registration)
        app.router.add_post('/together/api/pair/state', self.current_state)
        app.router.add_post('/together/api/pair/received', self.received_call)
        app.router.add_post('/together/api/pair/answer', self.answer_call)
        app.router.add_post('/together/api/pair/invite', self.invite)
        app.router.add_post('/together/api/pair/preview', self.preview)
        app.router.add_post('/together/api/pair/accept', self.accept)
        app.router.add_post('/together/api/pair/unpair', self.unpair)
        app.router.add_get('/together/pair-ws', self.socket)
