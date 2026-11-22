import asyncio
import secrets
import tempfile
import unittest
from pathlib import Path

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

        for kind in ('audio', 'video', 'watch'):
            await a.send_json(dict(type='ring', kind=kind))
            ring = (await self.event(b, 'pairState', lambda s: s['ring'] is not None))['ring']
            self.assertEqual(kind, ring['kind'])
            await b.send_json(dict(type='answer', id=ring['id'], accepted=True))
            left, right = await self.event(a, 'pairLaunch'), await self.event(b, 'pairLaunch')
            self.assertEqual(kind, left['kind'])
            self.assertEqual(kind, right['kind'])
            self.assertEqual(left['ticket']['room'], right['ticket']['room'])
            self.assertNotEqual(left['ticket']['token'], right['ticket']['token'])
            for ws, event in ((a, left), (b, right)):
                await ws.send_json(dict(type='ack', id=event['id']))
        self.assertEqual(3, len(self.service.rooms))

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
