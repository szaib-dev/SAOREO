"""Persistent pairing credentials and short-lived, authenticated call invitations."""
import asyncio
import base64
import json
import os
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


CHAT_RETENTION = 14 * 24 * 60 * 60 * 1000


def timestamp():
    return int(time.time() * 1000)


def credential_hash(value):
    if not isinstance(value, str) or not re.fullmatch(r'[A-Za-z0-9_-]{43}', value):
        raise web.HTTPUnauthorized(text='Invalid pairing credential')
    return hashlib.sha256(value.encode()).hexdigest()


def valid_chat_envelope(envelope, maximum):
    if (not isinstance(envelope, str) or len(envelope) > maximum
            or not re.fullmatch(r'[A-Za-z0-9_-]{1,128}\.\.[A-Za-z0-9_-]{16}\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]{22}', envelope)):
        return False
    try:
        header = envelope.split('.', 1)[0]
        return json.loads(base64.urlsafe_b64decode(header + '=' * (-len(header) % 4))) == {'alg': 'dir', 'enc': 'A256GCM'}
    except (ValueError, UnicodeError):
        return False


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
        self.socket_chat = set()
        self.socket_inbox = set()
        self.rings = {}
        self.timers = {}
        self.launches = {}
        self.fcm = FcmSender()
        self.received = set()
        self.push_tasks = set()
        self.chat_wakes = {}
        self.pending_chat_wakes = {}
        self.missed_call_wakes = set()
        self.chat_pending_limit = min(1024, max(1, int(os.environ.get('TOGETHER_CHAT_PER_DEVICE', '256'))))
        self.chat_attachment_device_bytes = max(100000, int(os.environ.get('TOGETHER_ATTACHMENT_PER_DEVICE_BYTES', '67108864')))
        self.chat_attachment_bytes = max(100000, int(os.environ.get('TOGETHER_ATTACHMENT_QUEUE_BYTES', '134217728')))
        self.chat_queue_bytes = max(16384, int(os.environ.get('TOGETHER_CHAT_QUEUE_BYTES', '33554432')))

    async def run(self, app):
        with closing(sqlite3.connect(self.database)) as self.db:
            self.db.executescript('''
                CREATE TABLE IF NOT EXISTS device (
                    id TEXT PRIMARY KEY, name TEXT NOT NULL, peer TEXT UNIQUE
                );
                CREATE TABLE IF NOT EXISTS invite (
                    code TEXT PRIMARY KEY, owner TEXT NOT NULL, expires INTEGER NOT NULL, accepted TEXT
                );
                CREATE TABLE IF NOT EXISTS conversation (
                    device TEXT PRIMARY KEY, id TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS chat_delivery (
                    sender TEXT NOT NULL, recipient TEXT NOT NULL, conversation TEXT NOT NULL,
                    id TEXT NOT NULL, envelope TEXT, queued INTEGER NOT NULL,
                    delivered INTEGER, read INTEGER,
                    PRIMARY KEY(conversation, sender, id)
                );
                CREATE INDEX IF NOT EXISTS chat_conversation_order ON chat_delivery(conversation, queued);
                CREATE INDEX IF NOT EXISTS chat_expiration ON chat_delivery(queued);
                CREATE INDEX IF NOT EXISTS chat_pending ON chat_delivery(queued) WHERE envelope IS NOT NULL;
                CREATE INDEX IF NOT EXISTS chat_recipient ON chat_delivery(recipient, conversation, queued);
                CREATE INDEX IF NOT EXISTS chat_sender ON chat_delivery(sender, conversation, queued);
                CREATE TABLE IF NOT EXISTS chat_attachment (
                    conversation TEXT NOT NULL, sender TEXT NOT NULL, recipient TEXT NOT NULL,
                    id TEXT NOT NULL, parts INTEGER NOT NULL, expires INTEGER NOT NULL,
                    stored INTEGER NOT NULL DEFAULT 0, bytes INTEGER NOT NULL DEFAULT 0,
                    delivered INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(conversation, sender, id)
                );
                CREATE INDEX IF NOT EXISTS chat_attachment_expiry ON chat_attachment(expires);
                CREATE TABLE IF NOT EXISTS chat_attachment_part (
                    conversation TEXT NOT NULL, sender TEXT NOT NULL, id TEXT NOT NULL,
                    part INTEGER NOT NULL, envelope TEXT NOT NULL,
                    PRIMARY KEY(conversation, sender, id, part)
                );
                CREATE TABLE IF NOT EXISTS paired_call (
                    id TEXT PRIMARY KEY, conversation TEXT NOT NULL, sender TEXT NOT NULL,
                    kind TEXT NOT NULL, started INTEGER NOT NULL, ended INTEGER, outcome TEXT
                );
                CREATE INDEX IF NOT EXISTS paired_call_recent ON paired_call(conversation, ended);
                CREATE INDEX IF NOT EXISTS paired_call_ended ON paired_call(ended);
                CREATE TABLE IF NOT EXISTS chat_push_token (
                    device TEXT PRIMARY KEY, token TEXT UNIQUE NOT NULL
                );
                CREATE TABLE IF NOT EXISTS push_token (
                    device TEXT PRIMARY KEY, token TEXT UNIQUE NOT NULL
                );
            ''')
            # Existing pairs acquire a conversation ID without changing pairing credentials.
            with self.db:
                if 'rejected' not in {row[1] for row in self.db.execute('PRAGMA table_info(chat_delivery)')}:
                    self.db.execute('ALTER TABLE chat_delivery ADD COLUMN rejected INTEGER')
                self.db.execute("UPDATE paired_call SET ended=?, outcome='unavailable' WHERE ended IS NULL", (self.next_call_end(),))
                for a, b in self.db.execute("""SELECT a.id, b.id FROM device a JOIN device b
                        ON a.peer=b.id AND b.peer=a.id WHERE a.id<b.id AND a.id NOT IN (SELECT device FROM conversation)""").fetchall():
                    self.create_conversation(a, b)
            cleanup = asyncio.create_task(self.clean_chat())
            try:
                yield
            finally:
                cleanup.cancel()
                await asyncio.gather(cleanup, return_exceptions=True)

    async def clean_chat(self):
        while True:
            with self.db:
                self.db.execute('DELETE FROM chat_delivery WHERE queued<?', (timestamp() - CHAT_RETENTION,))
                self.expire_attachments()

            await asyncio.sleep(3600)

    def create_conversation(self, a, b):
        alias = secrets.token_hex(16)
        self.db.executemany('INSERT OR REPLACE INTO conversation VALUES (?, ?)', [(a, alias), (b, alias)])

    def conversation(self, device):
        row = self.db.execute('SELECT id FROM conversation WHERE device=?', (device,)).fetchone()
        return row[0] if row else None

    def expire_attachments(self):
        expired = self.db.execute('SELECT conversation,sender,id FROM chat_attachment WHERE expires<=?',
                                  (timestamp(),)).fetchall()
        self.db.executemany('DELETE FROM chat_attachment_part WHERE conversation=? AND sender=? AND id=?', expired)
        self.db.execute('DELETE FROM chat_attachment WHERE expires<=?', (timestamp(),))

    async def chat_attachment(self, request):
        device = self.device_id(request)
        current = self.device(device)
        conversation = self.conversation(device)
        if not current or not current[1] or not conversation or request.match_info['conversation'] != conversation:
            raise web.HTTPForbidden(text='Secure pairing required')
        attachment = request.match_info['attachment']
        if not re.fullmatch('[a-f0-9]{32}', attachment):
            raise web.HTTPBadRequest(text='Invalid attachment ID')
        part_text = request.match_info['part']
        if not re.fullmatch('[0-9]{1,3}', part_text):
            raise web.HTTPBadRequest(text='Invalid attachment part')
        part = int(part_text)
        self.service.limit(('chat-attachment', device), 2048, 60)
        upload = request.method == 'PUT'
        sender = device if request.method in ('HEAD', 'PUT') else current[1]
        key = (conversation, sender, attachment)
        if request.method == 'HEAD':
            with self.db:
                self.expire_attachments()
                row = self.db.execute('SELECT stored FROM chat_attachment WHERE conversation=? AND sender=? AND id=?',
                                      key).fetchone()
            return web.Response(headers={'X-Parts-Stored': str(row[0] if row else 0),
                                         'Cache-Control': 'no-store'})
        if upload:
            request = request.clone(client_max_size=100000)
            envelope = await request.text()
            total_text = request.headers.get('X-Part-Count', '')
            if not re.fullmatch('[0-9]{1,3}', total_text):
                raise web.HTTPBadRequest(text='Invalid part count')
            total = int(total_text)
            if not 0 <= part < total <= 512 or not valid_chat_envelope(envelope, 99000):
                raise web.HTTPBadRequest(text='Invalid encrypted attachment part')
            # Recheck after reading the request body, which may have suspended.
            if self.device(device) != current or self.conversation(device) != conversation:
                raise web.HTTPForbidden(text='Pairing changed')
            with self.db:
                self.expire_attachments()
                record = self.db.execute('SELECT parts,stored FROM chat_attachment WHERE conversation=? AND sender=? AND id=?', key).fetchone()
                if record and record[0] != total:
                    raise web.HTTPConflict(text='Part count changed')
                prior = self.db.execute('SELECT envelope FROM chat_attachment_part WHERE conversation=? AND sender=? AND id=? AND part=?',
                                        (*key, part)).fetchone()
                if prior:
                    if prior[0] != envelope:
                        raise web.HTTPConflict(text='Attachment part already exists')
                    return web.json_response(dict(stored=True))
                if part != (record[1] if record else 0):
                    raise web.HTTPConflict(text='Upload preceding parts first')
                used, device_used = self.db.execute('''SELECT coalesce(sum(bytes),0),
                    coalesce(sum(CASE WHEN sender=? THEN bytes ELSE 0 END),0) FROM chat_attachment''', (device,)).fetchone()
                if (used + len(envelope) > self.chat_attachment_bytes or
                        device_used + len(envelope) > self.chat_attachment_device_bytes):
                    raise web.HTTPTooManyRequests(text='Attachment delivery queue is full')
                if not record:
                    self.db.execute('INSERT INTO chat_attachment(conversation,sender,recipient,id,parts,expires) VALUES (?,?,?,?,?,?)',
                                    (conversation, sender, current[1], attachment, total, timestamp() + 7 * 86400000))
                self.db.execute('INSERT INTO chat_attachment_part VALUES (?,?,?,?,?)', (*key, part, envelope))
                self.db.execute('UPDATE chat_attachment SET stored=stored+1, bytes=bytes+? WHERE conversation=? AND sender=? AND id=?',
                                (len(envelope), *key))
            return web.json_response(dict(stored=True))
        with self.db:
            self.expire_attachments()
            record = self.db.execute('SELECT recipient,parts,delivered FROM chat_attachment WHERE conversation=? AND sender=? AND id=?', key).fetchone()
            if not record or record[0] != device or record[2]:
                raise web.HTTPGone(text='Attachment is no longer available')
            if request.method == 'DELETE':
                stored = self.db.execute('SELECT stored FROM chat_attachment WHERE conversation=? AND sender=? AND id=?',
                                         key).fetchone()[0]
                if stored != record[1]:
                    raise web.HTTPConflict(text='Attachment is incomplete')
                self.db.execute('DELETE FROM chat_attachment_part WHERE conversation=? AND sender=? AND id=?', key)
                self.db.execute('UPDATE chat_attachment SET delivered=1,bytes=0,expires=? WHERE conversation=? AND sender=? AND id=?',
                                (timestamp() + CHAT_RETENTION, *key))
                return web.json_response(dict(removed=True))
            row = self.db.execute('SELECT envelope FROM chat_attachment_part WHERE conversation=? AND sender=? AND id=? AND part=?',
                                  (*key, part)).fetchone()
            if not row:
                raise web.HTTPNotFound(text='Attachment part is not available')
            return web.Response(text=row[0], content_type='application/jose', headers={'Cache-Control': 'no-store'})

    async def chat_send(self, request):
        return web.json_response(await self.store_chat(*await self.chat_request(request)))

    async def store_chat(self, sender, recipient, conversation, data):
        message = data.get('id')
        envelope = data.get('envelope')
        if not isinstance(message, str) or not re.fullmatch('[a-f0-9]{32}', message):
            raise web.HTTPBadRequest(text='Invalid message ID')
        if not valid_chat_envelope(envelope, 16384):
            raise web.HTTPBadRequest(text='Expected a compact A256GCM JWE')
        now = timestamp()
        with self.db:
            self.db.execute('DELETE FROM chat_delivery WHERE queued<?', (now - CHAT_RETENTION,))
            previous = self.db.execute("""SELECT envelope, queued, delivered, read, rejected FROM chat_delivery
                WHERE conversation=? AND sender=? AND id=?""", (conversation, sender, message)).fetchone()
            if previous and previous[4] is None:
                if previous[0] is not None and previous[0] != envelope:
                    raise web.HTTPConflict(text='Message ID already in use')
                return dict(queued=previous[1], delivered=previous[2], read=previous[3])
            if data.get('attachment') is True:
                media = self.db.execute('''SELECT parts,stored FROM chat_attachment
                    WHERE conversation=? AND sender=? AND recipient=? AND id=?''',
                    (conversation, sender, recipient, message)).fetchone()
                if not media or media[0] != media[1]:
                    raise web.HTTPConflict(text='Attachment upload is incomplete')
            count = self.db.execute('SELECT count(*) FROM chat_delivery WHERE sender=? AND conversation=?',
                                    (sender, conversation)).fetchone()[0]
            pending = self.db.execute('SELECT count(*) FROM chat_delivery WHERE recipient=? AND conversation=? AND delivered IS NULL AND rejected IS NULL',
                                      (recipient, conversation)).fetchone()[0]
            if count >= 10000 or pending >= self.chat_pending_limit:
                raise web.HTTPTooManyRequests(text='Conversation delivery queue is full')
            used = self.db.execute('SELECT coalesce(sum(length(envelope)), 0) FROM chat_delivery WHERE envelope IS NOT NULL').fetchone()[0]
            if used + len(envelope) > self.chat_queue_bytes:
                raise web.HTTPTooManyRequests(text='Conversation delivery queue is full')
            latest = self.db.execute('SELECT max(queued) FROM chat_delivery WHERE conversation=?', (conversation,)).fetchone()[0]
            now = previous[1] if previous else max(now, (latest or 0) + 1)
            if previous:
                self.db.execute('UPDATE chat_delivery SET envelope=?, queued=?, rejected=NULL WHERE conversation=? AND sender=? AND id=?',
                                (envelope, now, conversation, sender, message))
            else:
                self.db.execute('INSERT INTO chat_delivery(sender,recipient,conversation,id,envelope,queued) VALUES (?, ?, ?, ?, ?, ?)',
                                (sender, recipient, conversation, message, envelope, now))
        self.schedule_chat_wake(recipient)
        if recipient in self.socket_inbox:
            row = dict(sender=sender, id=message, envelope=envelope, queued=now)
            await self.send(recipient, dict(type='chatMessage', conversation=conversation, message=row))
        else:
            await self.send(recipient, dict(type='chatWake', conversation=conversation))
        return dict(queued=now, delivered=None, read=None)

    def schedule_chat_wake(self, device, missed_call=False):
        if missed_call:
            self.missed_call_wakes.add(device)
        if device in self.pending_chat_wakes:
            return
        if not self.db.execute('SELECT 1 FROM chat_push_token WHERE device=?', (device,)).fetchone():
            self.missed_call_wakes.discard(device)
            return

        async def send_later():
            try:
                now = timestamp()
                self.chat_wakes = {item: last for item, last in self.chat_wakes.items() if now - last < 15000}
                wait = 15000 - (now - self.chat_wakes.get(device, 0))
                wait = max(wait, 3000 if self.online(device) else 0)
                if wait > 0:
                    await asyncio.sleep(wait / 1000)
                missed_call = device in self.missed_call_wakes
                self.missed_call_wakes.discard(device)
                pending = self.db.execute("""SELECT 1 FROM chat_delivery
                    WHERE recipient=? AND conversation=? AND envelope IS NOT NULL LIMIT 1""",
                    (device, self.conversation(device))).fetchone()
                if not missed_call and not pending:
                    return
                token = self.db.execute('SELECT token FROM chat_push_token WHERE device=?', (device,)).fetchone()
                if token:
                    self.chat_wakes[device] = timestamp()
                    await self.fcm.send(token[0], None, 86400)
            finally:
                self.pending_chat_wakes.pop(device, None)
                if device in self.missed_call_wakes and not asyncio.current_task().cancelling():
                    self.schedule_chat_wake(device, missed_call=True)

        task = asyncio.create_task(send_later())
        self.pending_chat_wakes[device] = task
        self.push_tasks.add(task)
        task.add_done_callback(self.push_tasks.discard)

    async def chat_request(self, request):
        sender = self.device_id(request)
        self.service.limit(('chat', sender), 480, 60)
        data = await request.json()
        device = self.device(sender)
        if not device or not device[1]:
            raise web.HTTPForbidden(text='Secure pairing required')
        conversation = self.conversation(sender)
        if not conversation or not isinstance(data, dict) or data.get('conversation') != conversation:
            raise web.HTTPConflict(text='Pairing changed')
        return sender, device[1], conversation, data

    async def chat_sync(self, request):
        return web.json_response(await self.sync_chat(*await self.chat_request(request)))

    async def sync_chat(self, device, peer, conversation, data):
        actions = {}
        for key in ('delivered', 'read', 'status', 'rejected'):
            ids = data.get(key, [])
            if not isinstance(ids, list) or len(ids) > 50 or any(
                    not isinstance(i, str) or not re.fullmatch('[a-f0-9]{32}', i) for i in ids):
                raise web.HTTPBadRequest(text='Invalid chat acknowledgement list')
            actions[key] = ids
        now = timestamp()
        with self.db:
            self.db.execute('DELETE FROM chat_delivery WHERE queued<?', (now - CHAT_RETENTION,))
            self.db.executemany("""UPDATE chat_delivery SET delivered=?, envelope=NULL
                WHERE conversation=? AND recipient=? AND id=? AND delivered IS NULL AND rejected IS NULL""",
                [(now, conversation, device, i) for i in actions['delivered']])
            self.db.executemany("""UPDATE chat_delivery SET read=?
                WHERE conversation=? AND recipient=? AND id=? AND delivered IS NOT NULL AND read IS NULL""",
                [(now, conversation, device, i) for i in actions['read']])
            self.db.executemany('''UPDATE chat_delivery SET rejected=?, envelope=NULL
                WHERE conversation=? AND recipient=? AND id=? AND delivered IS NULL AND rejected IS NULL''',
                [(now, conversation, device, i) for i in actions['rejected']])
        inbox = self.db.execute("""SELECT sender, id, envelope, queued FROM chat_delivery
            WHERE recipient=? AND conversation=? AND delivered IS NULL AND rejected IS NULL ORDER BY queued, rowid LIMIT 50""",
            (device, conversation)).fetchall()
        status = []
        for message in actions['status']:
            row = self.db.execute("""SELECT delivered, read, rejected, queued FROM chat_delivery
                WHERE sender=? AND conversation=? AND id=?""", (device, conversation, message)).fetchone()
            if row:
                status.append(dict(id=message, delivered=row[0], read=row[1], rejected=row[2], queued=row[3]))
        confirmed = []
        for message in dict.fromkeys(actions['delivered'] + actions['read'] + actions['rejected']):
            row = self.db.execute("""SELECT delivered,read,rejected FROM chat_delivery
                WHERE conversation=? AND recipient=? AND id=?""", (conversation, device, message)).fetchone()
            if row and now in row:
                confirmed.append(dict(id=message, delivered=row[0], read=row[1], rejected=row[2]))
        if confirmed:
            if peer in self.socket_chat:
                await self.send(peer, dict(type='chatReceipts', conversation=conversation, status=confirmed))
            else:
                await self.send(peer, dict(type='chatWake', conversation=conversation))
        cursor = data.get('callCursor')
        next_cursor = None
        if isinstance(cursor, str) and re.fullmatch(r'(0|[0-9]{1,15}):[a-f0-9]{0,16}', cursor):
            ended, last_id = cursor.split(':')
            calls = self.db.execute("""SELECT id,sender,kind,started,ended,outcome FROM paired_call
                WHERE conversation=? AND (ended,id)>(?,?) ORDER BY ended,id LIMIT 128""",
                (conversation, int(ended), last_id)).fetchall()
            if calls:
                next_cursor = f'{calls[-1][4]}:{calls[-1][0]}'
        else:
            calls = self.db.execute("""SELECT id,sender,kind,started,ended,outcome FROM paired_call
                WHERE conversation=? AND ended IS NOT NULL ORDER BY ended DESC LIMIT 128""", (conversation,)).fetchall()
        return dict(inbox=[dict(sender=a, id=b, envelope=c, queued=d) for a,b,c,d in inbox],
            status=status, callCursor=next_cursor, moreCalls=next_cursor is not None and len(calls) == 128,
            calls=[dict(id=i, incoming=s != device, kind=k.removesuffix('_silent'), silent=k.endswith('_silent'), started=t, ended=e,
                outcome='missed' if o == 'unavailable' and s != device else o) for i,s,k,t,e,o in calls])

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
            raise web.HTTPConflict(text='Open this pairing link on your partnerÃ¢â‚¬â„¢s phone')
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
                self.create_conversation(owner, identifier)
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
            conversation = self.conversation(identifier)
            self.db.execute('DELETE FROM chat_attachment_part WHERE conversation=?', (conversation,))
            self.db.execute('DELETE FROM chat_attachment WHERE conversation=?', (conversation,))
            self.db.execute('DELETE FROM chat_delivery WHERE conversation=?', (conversation,))
            self.db.execute('DELETE FROM paired_call WHERE conversation=?', (conversation,))
            self.db.execute('DELETE FROM conversation WHERE id=?', (conversation,))
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
        intent = html.escape(f'intent://together/pair?code={code}&server={server}', quote=True)
        nonce = secrets.token_urlsafe(24)
        return web.Response(text=f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Pair with SAOREO Together</title>
<style nonce="{nonce}">
:root{{color-scheme:dark;font:1rem system-ui;background:#111115;color:#f4f3f6}}
body{{margin:0;min-height:100svh;display:grid;place-items:center;padding:1.25rem;box-sizing:border-box}}
main{{max-width:26rem;background:#1b1b21;border:1px solid #303038;border-radius:1.5rem;padding:clamp(1.5rem,7vw,2.5rem)}}
small{{color:#d5ef72;font-weight:700}}h1{{margin:.6rem 0}}p{{color:#c7c7ce;line-height:1.55}}
a{{display:inline-block;background:#d5ef72;color:#19191e;padding:.9rem 1.35rem;border-radius:2rem;font-weight:700;text-decoration:none}}
a[hidden]{{display:none}}a:focus-visible{{outline:2px solid white;outline-offset:4px}}
</style>
</head>
<body>
<main>
<small>SAOREO Together</small>
<h1>Pair your device</h1>
<p>Open SAOREO on your Android phone to review and accept the pairing request.</p>
<a id="open" data-intent="{intent}" hidden>Open SAOREO</a>
<p id="help" role="status">Copy the full invitation link and paste it into Together on your Android phone.</p>
</main>
<script nonce="{nonce}">
const key = location.hash.slice(1);
const button = document.getElementById('open');
if (/^[A-Za-z0-9_-]{{43}}$/.test(key)) {{
  button.href = button.dataset.intent + '#Intent;scheme=saoreo;package=com.codetic.saoreo;S.callSecret=' + key + ';end';
  button.hidden = false;
}} else {{
  document.getElementById('help').textContent = 'Open the complete invitation link from your partner.';
}}
</script>
</body>
</html>""", content_type='text/html', headers={
            'Referrer-Policy': 'no-referrer', 'Cache-Control': 'no-store',
            'Content-Security-Policy': f"default-src 'none'; script-src 'nonce-{nonce}'; style-src 'nonce-{nonce}'; base-uri 'none'; frame-ancestors 'none'",
        })

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
        if ring and ring.expires <= timestamp():
            ring = None
        return dict(type='pairState', partner=partner[0] if partner else None,
            online=self.online(peer), socketChat=True, conversation=self.conversation(identifier), peer=peer, ring=dict(id=ring.id, kind=ring.kind, incoming=ring.recipient == identifier,
                delivered=ring.id in self.received, started=ring.expires - 30_000,
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
            if 'chat' in data:
                self.db.execute('DELETE FROM chat_push_token WHERE device=? OR token=?', (identifier, token))
                if data.get('chat') is True:
                    self.db.execute('INSERT INTO chat_push_token VALUES (?, ?)', (identifier, token))
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

    def next_call_end(self):
        latest = self.db.execute('SELECT max(ended) FROM paired_call').fetchone()[0]
        return max(timestamp(), (latest or 0) + 1)

    async def resolve(self, ring, outcome, tickets=None):
        alerted = ring.id in self.received
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
                    type='pairLaunch', id=ring.id, kind=ring.kind.removesuffix('_silent'), ticket=ticket,
                    incoming=member == ring.recipient, started=ring.expires - 30_000))
        sender_outcome = 'unavailable' if outcome == 'missed' and not alerted else outcome
        with self.db:
            self.db.execute("UPDATE paired_call SET ended=?,outcome=? WHERE id=?", (self.next_call_end(), sender_outcome, ring.id))
        await asyncio.gather(*(self.send(member, dict(type='ringResult', id=ring.id,
            outcome=sender_outcome if member == ring.sender else outcome)) for member in participants))
        await asyncio.gather(*(self.send_status(member) for member in participants))
        if outcome == 'missed' and ring.kind in ('audio', 'video'):
            self.schedule_chat_wake(ring.recipient, missed_call=True)
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
        if op in ('chat-send', 'chat-sync'):
            request_id = data.get('requestId')
            if not isinstance(request_id, str) or not re.fullmatch('[0-9]{1,18}', request_id):
                raise ValueError('Invalid chat request ID')
            try:
                self.service.limit(('chat', identifier), 480, 60)
                conversation = self.conversation(identifier)
                peer = self.device(identifier)[1]
                if not conversation or not peer or data.get('conversation') != conversation:
                    raise web.HTTPConflict(text='Pairing changed')
                self.socket_chat.add(identifier)
                if data.get('pushInbox') is True:
                    self.socket_inbox.add(identifier)
                handler = self.store_chat if op == 'chat-send' else self.sync_chat
                reply = dict(result=await handler(identifier, peer, conversation, data))
            except web.HTTPException as error:
                reply = dict(code=error.status)
            await self.send(identifier, dict(type='chatResponse', requestId=request_id, **reply))
            return
        if op == 'ack':
            pending = self.launches.get(identifier)
            if pending and pending[1]['id'] == data.get('id'):
                self.launches.pop(identifier, None)
            return
        if op == 'chatTyping':
            partner = self.device(identifier)[1]
            conversation = self.conversation(identifier)
            if partner and conversation and conversation == data.get('conversation'):
                await self.send(partner, dict(type='chatTyping', conversation=conversation))
            return
        if op == 'ring':
            partner = self.device(identifier)[1]
            if not partner:
                raise ValueError('Pair a partner before sending a request')
            kind = data.get('kind')
            if kind not in ('audio', 'video', 'watch', 'audio_silent', 'video_silent'):
                raise ValueError('Unknown request type')
            started = timestamp()
            conversation = self.conversation(identifier)
            if identifier in self.rings or partner in self.rings:
                if kind != 'watch':
                    with self.db:
                        self.db.execute("INSERT INTO paired_call VALUES (?,?,?,?,?,?,'busy')",
                            (secrets.token_hex(8), conversation, identifier, kind, started, self.next_call_end()))
                raise ValueError('Busy')
            room = host = None
            if kind == 'watch' and data.get('room'):
                room, host = self.service.paired_watch_host(data['room'], data.get('roomToken', ''))
            ring = Ring(secrets.token_hex(8), identifier, partner, kind, started + 30_000,
                        room.id if room else None, host.id if host else None)
            if kind != 'watch':
                with self.db:
                    self.db.execute("INSERT INTO paired_call VALUES (?,?,?,?,?,NULL,NULL)",
                        (ring.id, conversation, identifier, kind, started))
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
                    await self.resolve(ring, 'failed')
                    raise ValueError('Your partner disconnected')
                tickets = self.service.paired_room(self.device(ring.sender)[0], self.device(ring.recipient)[0], ring.room, ring.host)
                await self.resolve(ring, 'accepted', tickets)
            else:
                await self.resolve(ring, 'busy' if data.get('busy') is True else 'declined')
        else:
            raise ValueError('Only the recipient can answer this request')

    async def socket(self, request):
        self.service.limit(('pair-socket', self.service.ip(request)), 45, 300)
        identifier = self.device_id(request)
        current = self.device(identifier)
        if not current:
            raise web.HTTPUnauthorized(text='Create or accept a pairing link first')
        ws = web.WebSocketResponse(heartbeat=20, receive_timeout=90, max_msg_size=32768)
        await ws.prepare(request)
        previous = self.sockets.get(identifier)
        self.sockets[identifier] = ws
        self.socket_chat.discard(identifier)
        self.socket_inbox.discard(identifier)
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
                    data = item.json()
                    if not isinstance(data, dict):
                        raise ValueError('Invalid pairing command')
                    if data.get('type') not in ('chat-send', 'chat-sync'):
                        self.service.limit(('pair-command', identifier), 40, 30)
                    await self.command(identifier, data)
                except (ValueError, web.HTTPException) as error:
                    await self.send(identifier, dict(type='error', message=error.text if isinstance(error, web.HTTPException) else str(error)))
        finally:
            if self.sockets.get(identifier) is ws:
                self.sockets.pop(identifier, None)
                self.socket_chat.discard(identifier)
                self.socket_inbox.discard(identifier)
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
        for method in ('PUT', 'HEAD', 'GET', 'DELETE'):
            app.router.add_route(method, '/together/api/pair/attachment/{conversation}/{attachment}/{part}', self.chat_attachment)
        app.router.add_post('/together/api/pair/chat-send', self.chat_send)
        app.router.add_post('/together/api/pair/chat-sync', self.chat_sync)
        app.router.add_get('/together/pair-ws', self.socket)
