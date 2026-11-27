import asyncio
import hashlib
import secrets
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from aiohttp import WSServerHandshakeError
from aiohttp.test_utils import TestClient, TestServer

from service import Together


KEY = 'paired-device-test-key'


class PairingTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.database = Path(self.directory.name) / 'pairing.sqlite3'
        self.a, self.b, self.stranger = (secrets.token_urlsafe(32) for _ in range(3))
        await self.start_server()

    async def start_server(self):
        self.service = Together(KEY, pair_db=self.database)
        self.client = TestClient(TestServer(self.service.app()))
        await self.client.start_server()

    async def asyncTearDown(self):
        await self.client.close()

    def auth(self, token):
        return {'Authorization': f'Bearer {token}'}

    async def post(self, route, token, **values):
        async with self.client.post('/together/api/pair/' + route, headers=self.auth(token), json=values) as response:
            return response.status, await response.json()

    async def link(self):
        status, reply = await self.post('invite', self.a, key=KEY, name='Alex')
        self.assertEqual(200, status)
        return reply['code']

    async def pair(self):
        code = await self.link()
        self.assertEqual((200, {'name': 'Alex'}), await self.post('accept', self.b, code=code, name='Sam'))
        return code

    async def socket(self, token):
        return await self.client.ws_connect('/together/pair-ws', headers=self.auth(token))

    async def event(self, ws, kind, predicate=lambda _: True):
        async with asyncio.timeout(4):
            while True:
                item = await ws.receive_json()
                if item['type'] == kind and predicate(item):
                    return item

    async def test_pairing_requires_acceptance_and_survives_restart(self):
        code = await self.link()
        a = await self.socket(self.a)
        self.assertIsNone((await self.event(a, 'pairState'))['partner'])
        self.assertEqual((200, {'name': 'Alex'}), await self.post('preview', self.b, code=code))
        self.assertEqual(409, (await self.post('accept', self.a, code=code, name='Alex'))[0])
        with self.assertRaises(WSServerHandshakeError) as unregistered:
            await self.socket(self.b)
        self.assertEqual(401, unregistered.exception.status)
        self.assertEqual((200, {'name': 'Alex'}), await self.post('accept', self.b, code=code, name='Sam'))
        self.assertEqual('Sam', (await self.event(a, 'pairState'))['partner'])
        self.assertEqual(404, (await self.post('accept', self.stranger, code=code, name='Stranger'))[0])
        self.assertEqual(200, (await self.post('accept', self.b, code=code, name='Sam'))[0])

        await a.close()
        await self.client.close()
        await self.start_server()
        a, b = await self.socket(self.a), await self.socket(self.b)
        self.assertEqual('Alex', (await self.event(b, 'pairState'))['partner'])
        self.assertEqual('Sam', (await self.event(a, 'pairState', lambda s: s['online']))['partner'])
        self.assertEqual(200, (await self.post('unpair', self.b))[0])
        self.assertIsNone((await self.event(a, 'pairState', lambda s: s['partner'] is None))['partner'])
        self.assertEqual(404, (await self.post('accept', self.b, code=code, name='Sam'))[0])

    async def test_chat_queue_survives_restart_with_idempotent_delivery_and_read_ack(self):
        await self.pair()
        _, state = await self.post('state', self.a)
        room = state['conversation']
        message = secrets.token_hex(16)
        # Opaque JWE-shaped fixture. The server has no decryption key.
        envelope = 'eyJhbGciOiJkaXIiLCJlbmMiOiJBMjU2R0NNIn0..AAAAAAAAAAAAAAAA.AAAA.AAAAAAAAAAAAAAAAAAAAAA'
        args = dict(conversation=room, id=message, envelope=envelope)
        self.assertEqual(403, (await self.post('chat-send', self.stranger, **args))[0])
        self.assertEqual(409, (await self.post('chat-send', self.a, **(args | {'conversation': 'not-my-pair'})))[0])
        self.assertEqual(400, (await self.post('chat-send', self.a, **(args | {'envelope': 'hello'})))[0])
        status, queued = await self.post('chat-send', self.a, **args)
        self.assertEqual(200, status)
        self.assertIsNone(queued['delivered'])
        self.assertEqual((200, queued), await self.post('chat-send', self.a, **args))
        await self.client.close()
        await self.start_server()
        _, state = await self.post('state', self.b)
        self.assertEqual(room, state['conversation'])
        _, sync = await self.post('chat-sync', self.b, conversation=room)
        self.assertEqual([(message, envelope)], [(m['id'], m['envelope']) for m in sync['inbox']])
        _, premature = await self.post('chat-sync', self.b, conversation=room, read=[message])
        self.assertEqual(1, len(premature['inbox']))
        _, sync = await self.post('chat-sync', self.b, conversation=room, delivered=[message])
        self.assertEqual([], sync['inbox'])
        _, status = await self.post('chat-sync', self.a, conversation=room, status=[message])
        self.assertIsNotNone(status['status'][0]['delivered'])
        self.assertIsNone(status['status'][0]['read'])
        await self.post('chat-sync', self.b, conversation=room, read=[message])
        _, status = await self.post('chat-sync', self.a, conversation=room, status=[message])
        self.assertIsNotNone(status['status'][0]['read'])
        self.assertEqual(200, (await self.post('chat-send', self.a, **args))[0])
        _, sync = await self.post('chat-sync', self.b, conversation=room)
        self.assertEqual([], sync['inbox'])
        self.assertIsNone(self.service.pairing.db.execute('SELECT envelope FROM chat_delivery').fetchone()[0])
        await self.post('unpair', self.a)
        self.assertEqual(403, (await self.post('chat-sync', self.a, conversation=room))[0])

    async def test_rejected_ciphertext_does_not_block_inbox_and_can_be_retried(self):
        await self.pair()
        _, state = await self.post('state', self.a)
        conversation = state['conversation']
        message = secrets.token_hex(16)
        envelope = 'eyJhbGciOiJkaXIiLCJlbmMiOiJBMjU2R0NNIn0..AAAAAAAAAAAAAAAA.AAAA.AAAAAAAAAAAAAAAAAAAAAA'
        args = dict(conversation=conversation, id=message, envelope=envelope)
        self.assertEqual(409, (await self.post('chat-send', self.a, **args, attachment=True))[0])
        self.assertEqual(200, (await self.post('chat-send', self.a, **args))[0])
        _, inbox = await self.post('chat-sync', self.b, conversation=conversation, rejected=[message])
        self.assertEqual([], inbox['inbox'])
        _, status = await self.post('chat-sync', self.a, conversation=conversation, status=[message])
        self.assertIsNotNone(status['status'][0]['rejected'])
        self.assertIsNone(status['status'][0]['delivered'])
        self.assertEqual(200, (await self.post('chat-send', self.a, **args))[0])
        _, inbox = await self.post('chat-sync', self.b, conversation=conversation)
        self.assertEqual(message, inbox['inbox'][0]['id'])

    async def test_socket_chat_and_receipts_without_polling(self):
        await self.pair()
        a, b = await self.socket(self.a), await self.socket(self.b)
        conversation = (await self.event(a, 'pairState'))['conversation']
        self.assertTrue((await self.event(b, 'pairState'))['socketChat'])

        async def request(ws, operation, sequence, **body):
            request_id = str(sequence)
            await ws.send_json(dict(type=operation, requestId=request_id, conversation=conversation, **body))
            return (await self.event(ws, 'chatResponse', lambda event: event['requestId'] == request_id))['result']

        await request(b, 'chat-sync', 1, pushInbox=True)
        message = secrets.token_hex(16)
        envelope = 'eyJhbGciOiJkaXIiLCJlbmMiOiJBMjU2R0NNIn0..AAAAAAAAAAAAAAAA.AAAA.AAAAAAAAAAAAAAAAAAAAAA'
        sent = await request(a, 'chat-send', 1, id=message, envelope=envelope)
        self.assertIsNone(sent['delivered'])
        direct = (await self.event(b, 'chatMessage'))['message']
        self.assertEqual((message, envelope, sent['queued']), (direct['id'], direct['envelope'], direct['queued']))
        inbox = (await request(b, 'chat-sync', 2))['inbox']
        self.assertEqual([message], [row['id'] for row in inbox])
        await request(b, 'chat-sync', 3, read=[message])
        await request(b, 'chat-sync', 4, delivered=[message])
        receipt = (await self.event(a, 'chatReceipts'))['status'][0]
        self.assertIsNotNone(receipt['delivered'])
        self.assertIsNone(receipt['read'])
        await request(b, 'chat-sync', 5, read=[message])
        self.assertIsNotNone((await self.event(a, 'chatReceipts'))['status'][0]['read'])
        self.assertEqual(sent['queued'], (await request(a, 'chat-send', 2, id=message, envelope=envelope))['queued'])
        self.assertEqual([], (await request(b, 'chat-sync', 6))['inbox'])

    async def test_two_offline_calls_keep_times_and_never_replay_ringing(self):
        await self.pair()
        sender = hashlib.sha256(self.a.encode()).hexdigest()
        a = await self.socket(self.a)
        expected = {}
        for kind in ('audio', 'video'):
            await a.send_json(dict(type='ring', kind=kind))
            state = await self.event(a, 'pairState', lambda event: event.get('ring') is not None)
            self.assertFalse(state['ring']['delivered'])
            ring = self.service.pairing.rings[sender]
            expected[ring.id] = ring.expires - 30_000
            with patch('pairing.timestamp', return_value=ring.expires + 1):
                await self.service.pairing.resolve(ring, 'missed')
            self.assertEqual('unavailable', (await self.event(a, 'ringResult'))['outcome'])
        await a.close()
        await self.client.close()
        await self.start_server()
        b = await self.socket(self.b)
        state = await self.event(b, 'pairState')
        self.assertIsNone(state['ring'])
        _, first = await self.post('chat-sync', self.b, conversation=state['conversation'], callCursor='0:')
        self.assertEqual(expected, {call['id']: call['started'] for call in first['calls']})
        self.assertTrue(all(call['incoming'] and call['outcome'] == 'missed' for call in first['calls']))
        _, next_page = await self.post('chat-sync', self.b, conversation=state['conversation'], callCursor=first['callCursor'])
        self.assertEqual([], next_page['calls'])

    async def test_interrupted_ring_never_rings_after_restart_and_is_recorded(self):
        await self.pair()
        a = await self.socket(self.a)
        await a.send_json(dict(type='ring', kind='video'))
        pending = await self.event(a, 'pairState', lambda state: state['ring'] is not None)
        self.assertFalse(pending['ring']['delivered'])
        await a.close()
        await self.client.close()
        await self.start_server()
        for token, outcome, incoming in [(self.a, 'unavailable', False), (self.b, 'missed', True)]:
            _, state = await self.post('state', token)
            self.assertIsNone(state['ring'])
            _, sync = await self.post('chat-sync', token, conversation=state['conversation'])
            history = sync['calls']
            self.assertEqual(1, len(history))
            self.assertEqual(pending['ring']['id'], history[0]['id'])
            self.assertEqual(outcome, history[0]['outcome'])
            self.assertEqual(incoming, history[0]['incoming'])

    async def test_encrypted_attachment_authentication_restart_cleanup_and_expiry(self):
        await self.pair()
        _, state = await self.post('state', self.a)
        conversation = state['conversation']
        envelope = 'eyJhbGciOiJkaXIiLCJlbmMiOiJBMjU2R0NNIn0..AAAAAAAAAAAAAAAA.AAAA.AAAAAAAAAAAAAAAAAAAAAA'
        # A realistic-size ciphertext body verifies the route can exceed the JSON endpoint limit.
        envelope = envelope.replace('.AAAA.', '.' + 'A' * 87000 + '.')

        async def part(method, token, attachment, index=0, transfer=None, room=conversation, total=2):
            path = f'/together/api/pair/attachment/{room}/{attachment}/{index}'
            async with self.client.request(method, path, data=transfer,
                    headers=self.auth(token) | {'X-Part-Count': str(total)}) as response:
                return response.status, await response.text(), response.headers.get('X-Parts-Stored')

        item = secrets.token_hex(16)
        self.assertEqual(400, (await part('PUT', self.a, item, transfer='plaintext'))[0])
        self.assertEqual(409, (await part('PUT', self.a, item, 1, envelope))[0])
        self.assertEqual(200, (await part('PUT', self.a, item, transfer=envelope))[0])
        self.assertEqual(200, (await part('PUT', self.a, item, transfer=envelope))[0])
        self.assertEqual(409, (await part('PUT', self.a, item, transfer=envelope.replace('.AAAA', '.BAAA', 1)))[0])
        self.assertEqual(403, (await part('GET', self.stranger, item))[0])
        self.assertEqual(401, (await part('GET', 'invalid', item))[0])
        self.assertEqual(403, (await part('GET', self.b, item, room=secrets.token_hex(16)))[0])
        self.assertEqual(410, (await part('GET', self.a, item))[0])
        self.assertEqual(409, (await part('DELETE', self.b, item))[0])
        await self.client.close()
        await self.start_server()
        self.assertEqual('1', (await part('HEAD', self.a, item))[2])
        self.assertEqual('0', (await part('HEAD', self.b, item))[2])
        self.assertEqual(200, (await part('PUT', self.a, item, 1, envelope))[0])
        self.assertEqual((200, envelope, None), await part('GET', self.b, item, 1))
        self.assertEqual(200, (await part('DELETE', self.b, item))[0])
        self.assertEqual('2', (await part('HEAD', self.a, item))[2])
        self.assertEqual(410, (await part('GET', self.b, item))[0])
        self.assertEqual(0, self.service.pairing.db.execute('SELECT count(*) FROM chat_attachment_part').fetchone()[0])

        expired = secrets.token_hex(16)
        self.assertEqual(200, (await part('PUT', self.a, expired, transfer=envelope))[0])
        with self.service.pairing.db:
            self.service.pairing.db.execute('UPDATE chat_attachment SET expires=0 WHERE id=?', (expired,))
        self.assertEqual(410, (await part('GET', self.b, expired))[0])
        self.assertEqual('0', (await part('HEAD', self.a, expired))[2])
        self.assertEqual(200, (await self.post('unpair', self.a))[0])
        self.assertEqual(403, (await part('GET', self.b, item))[0])

    async def test_expired_call_cannot_be_acknowledged_or_accepted(self):
        await self.pair()
        a = await self.socket(self.a)
        await self.event(a, 'pairState')
        await a.send_json(dict(type='ring', kind='video'))
        state = await self.event(a, 'pairState', lambda value: value['ring'] is not None)
        ring_id = state['ring']['id']
        self.assertFalse(state['ring']['delivered'])
        with patch('pairing.timestamp', return_value=self.service.pairing.rings[hashlib.sha256(self.a.encode()).hexdigest()].expires + 1):
            _, refreshed = await self.post('state', self.b)
            self.assertIsNone(refreshed['ring'])
            self.assertEqual(200, (await self.post('received', self.b, id=ring_id))[0])
            self.assertEqual(409, (await self.post('answer', self.b, id=ring_id, accepted=True))[0])
        self.assertNotIn(ring_id, self.service.pairing.received)
        self.assertEqual({}, self.service.rooms)
        await a.close()

    async def test_request_waits_for_partner_reconnection(self):
        await self.pair()
        a = await self.socket(self.a)
        await self.event(a, 'pairState')
        await a.send_json(dict(type='ring', kind='video_silent'))
        outgoing = await self.event(a, 'pairState', lambda s: s['ring'] is not None)
        b = await self.socket(self.b)
        incoming = await self.event(b, 'pairState', lambda s: s['ring'] is not None)
        self.assertEqual(outgoing['ring']['id'], incoming['ring']['id'])
        await b.send_json(dict(type='answer', id=incoming['ring']['id'], accepted=True))
        self.assertEqual('video', (await self.event(a, 'pairLaunch'))['kind'])
        await a.close()
        await b.close()

    async def test_standalone_requests_open_a_room_only_after_the_partner_accepts(self):
        await self.pair()
        a, b = await self.socket(self.a), await self.socket(self.b)
        await self.event(a, 'pairState', lambda s: s['online'])
        await a.send_json(dict(type='ring', kind='audio'))
        ring = (await self.event(b, 'pairState', lambda s: s['ring'] is not None))['ring']
        self.assertTrue(ring['incoming'])
        self.assertEqual('audio', ring['kind'])
        await a.send_json(dict(type='answer', id=ring['id'], accepted=True))
        await self.event(a, 'error')
        self.assertEqual({}, self.service.rooms)
        await b.send_json(dict(type='answer', id=ring['id'], accepted=False))
        self.assertEqual('declined', (await self.event(a, 'ringResult'))['outcome'])
        self.assertEqual({}, self.service.rooms)

        for kind in ('audio', 'video', 'watch', 'audio_silent', 'video_silent'):
            await a.send_json(dict(type='ring', kind=kind))
            ring = (await self.event(b, 'pairState', lambda s: s['ring'] is not None))['ring']
            self.assertEqual(kind, ring['kind'])
            await b.send_json(dict(type='answer', id=ring['id'], accepted=True))
            left, right = await self.event(a, 'pairLaunch'), await self.event(b, 'pairLaunch')
            self.assertEqual(kind.removesuffix('_silent'), left['kind'])
            self.assertEqual(kind.removesuffix('_silent'), right['kind'])
            self.assertEqual(left['ticket']['room'], right['ticket']['room'])
            self.assertNotEqual(left['ticket']['token'], right['ticket']['token'])
            for ws, event in ((a, left), (b, right)):
                await ws.send_json(dict(type='ack', id=event['id']))
        self.assertEqual(5, len(self.service.rooms))

    async def test_watch_invite_uses_existing_room_only_with_its_owner_ticket(self):
        await self.pair()
        a, b = await self.socket(self.a), await self.socket(self.b)
        await self.event(a, 'pairState', lambda s: s['online'])
        async with self.client.post('/together/api/rooms', json=dict(name='Alex', key=KEY)) as response:
            host = await response.json()
        room_socket = await self.client.ws_connect('/together/ws')
        await room_socket.send_json(dict(room=host['room'], token=host['token'], protocol=3))
        await self.event(room_socket, 'state')
        await a.send_json(dict(type='ring', kind='watch', room=host['room'], roomToken=self.stranger))
        await self.event(a, 'error')
        self.assertEqual({}, self.service.pairing.rings)
        await a.send_json(dict(type='ring', kind='watch', room=host['room'], roomToken=host['token']))
        ring = (await self.event(b, 'pairState', lambda s: s['ring'] is not None))['ring']
        await b.send_json(dict(type='answer', id=ring['id'], accepted=True))
        left, right = await self.event(a, 'pairLaunch'), await self.event(b, 'pairLaunch')
        self.assertEqual(host, left['ticket'])
        self.assertEqual(host['room'], right['ticket']['room'])
        self.assertEqual(1, len(self.service.rooms))
