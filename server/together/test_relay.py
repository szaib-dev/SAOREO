import asyncio
import base64
import unittest
from aiohttp.test_utils import TestClient, TestServer
from service import Together
from relay import byte_range, CHUNK, READ_AHEAD

KEY = 'range-relay-test-key-at-least-16'


class RangeTests(unittest.TestCase):
    def test_range_semantics(self):
        self.assertEqual((0, 99, False), byte_range(None, 100))
        self.assertEqual((25, 99, True), byte_range('bytes=25-', 100))
        self.assertEqual((95, 99, True), byte_range('bytes=-5', 100))
        self.assertEqual((90, 99, True), byte_range('bytes=90-200', 100))
        for value in ['bytes=100-', 'bytes=3-2', 'bytes=-0', 'bytes=0-1,4-5', 'no', 'bytes=-']:
            with self.assertRaises(ValueError): byte_range(value, 100)


class RelayTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.service = Together(KEY)
        self.client = TestClient(TestServer(self.service.app(), handler_cancellation=True))
        await self.client.start_server()
        self.ws = []
        self.tasks = []

    async def asyncTearDown(self):
        for task in self.tasks: task.cancel()
        await asyncio.gather(*self.tasks, return_exceptions=True)
        for ws in self.ws: await ws.close()
        await self.client.close()

    async def event(self, ws, kind, predicate=lambda x: True):
        async with asyncio.timeout(3):
            while True:
                data = await ws.receive_json()
                if data.get('type') == kind and predicate(data): return data

    async def pair(self, protocol=3):
        a = await (await self.client.post('/together/api/rooms', json=dict(key=KEY, name='A'))).json()
        b = await (await self.client.post('/together/api/join', json=dict(invite=a['invite'], name='B'))).json()
        for ticket in (a, b):
            ws = await self.client.ws_connect('/together/ws')
            self.ws.append(ws)
            await ws.send_json(dict(room=ticket['room'], token=ticket['token'], protocol=protocol))
            await self.event(ws, 'state')
        return a, b, self.ws[-2], self.ws[-1]

    def auth(self, ticket):
        return {'Authorization': 'Bearer ' + ticket['token']}

    async def register(self, ticket, size=500 * 1024 * 1024):
        response = await self.client.post('/together/share/' + ticket['room'], headers=self.auth(ticket), json=dict(size=size, mime='video/mp4'))
        self.assertEqual(200, response.status)
        return (await response.json())['id']

    def producer(self, ws, truncate=False):
        async def run():
            async for msg in ws:
                data = msg.json()
                if data.get('type') != 'fileRead': continue
                length = data['length'] - (1 if truncate else 0)
                payload = bytes((data['offset'] + i) % 251 for i in range(length))
                await ws.send_json(dict(type='fileChunk', request=data['request'], data=base64.b64encode(payload).decode()))
        task = asyncio.create_task(run())
        self.tasks.append(task)
        return task

    async def test_large_file_seeks_directly_without_uploading_or_a_second_copy(self):
        a, b, wa, wb = await self.pair()
        identifier = await self.register(a)
        path = f"/together/share/{a['room']}/{identifier}"
        head = await self.client.head(path, headers=self.auth(b))
        self.assertEqual(200, head.status)
        self.assertEqual(str(500 * 1024 * 1024), head.headers['Content-Length'])
        self.assertEqual({}, self.service.relay.pending)
        self.producer(wa)
        offset = 300_000_000
        response = await self.client.get(path, headers=self.auth(b) | {'Range': f'bytes={offset}-{offset+200}'})
        self.assertEqual(206, response.status)
        self.assertEqual(bytes((offset + i) % 251 for i in range(201)), await response.read())
        self.assertEqual({}, self.service.uploads)
        self.assertEqual({}, self.service.relay.pending)
        self.assertEqual(0, self.service.relay.readers)
        self.assertEqual(401, (await self.client.get(path)).status)
        self.assertEqual(416, (await self.client.get(path, headers=self.auth(b) | {'Range': 'bytes=9999999999-'})).status)

    async def test_multiple_chunks_have_correct_backpressure_and_content_length(self):
        a, b, wa, wb = await self.pair()
        size = CHUNK * 2 + 17
        identifier = await self.register(a, size)
        self.producer(wa)
        response = await self.client.get(f"/together/share/{a['room']}/{identifier}", headers=self.auth(b))
        self.assertEqual(200, response.status)
        self.assertEqual(bytes(i % 251 for i in range(size)), await response.read())
        self.assertEqual({}, self.service.relay.pending)

    async def test_prefetch_overlaps_source_round_trips_and_keeps_range_order(self):
        a, b, wa, _ = await self.pair()
        start = 23
        identifier = await self.register(a, start + READ_AHEAD * CHUNK)
        path = f"/together/share/{a['room']}/{identifier}"
        response_task = asyncio.ensure_future(self.client.get(path, headers=self.auth(b) | {'Range': f'bytes={start}-'}))
        self.tasks.append(response_task)

        # Each read is issued before the source answers any of them.
        requests = [await self.event(wa, 'fileRead') for _ in range(READ_AHEAD)]
        self.assertEqual(list(range(start, start + READ_AHEAD * CHUNK, CHUNK)), [r['offset'] for r in requests])
        for index, request in reversed(list(enumerate(requests))):
            payload = bytes([index]) * request['length']
            await wa.send_json(dict(type='fileChunk', request=request['request'], data=base64.b64encode(payload).decode()))
        response = await response_task
        self.assertEqual(206, response.status)
        self.assertEqual(b''.join(bytes([i]) * CHUNK for i in range(READ_AHEAD)), await response.read())
        self.assertEqual({}, self.service.relay.pending)

    async def test_receiver_disconnect_cancels_prefetched_reads(self):
        a, b, wa, _ = await self.pair()
        identifier = await self.register(a)
        response_task = asyncio.ensure_future(self.client.get(f"/together/share/{a['room']}/{identifier}", headers=self.auth(b)))
        self.tasks.append(response_task)
        requests = [await self.event(wa, 'fileRead') for _ in range(READ_AHEAD)]
        first = requests[0]
        await wa.send_json(dict(type='fileChunk', request=first['request'], data=base64.b64encode(b'x' * first['length']).decode()))
        response = await response_task
        self.assertEqual(b'x' * CHUNK, await response.content.readexactly(CHUNK))
        response.close()

        async with asyncio.timeout(3):
            while self.service.relay.readers:
                await asyncio.sleep(.01)
        self.assertEqual({}, self.service.relay.pending)

    async def test_truncated_or_offline_source_is_not_a_successful_media_response(self):
        a, b, wa, wb = await self.pair()
        identifier = await self.register(a, 300)
        task = self.producer(wa, truncate=True)
        path = f"/together/share/{a['room']}/{identifier}"
        response = await self.client.get(path, headers=self.auth(b))
        self.assertEqual(503, response.status)
        await response.read()
        task.cancel()
        await wa.close()
        response = await self.client.get(path, headers=self.auth(b))
        self.assertEqual(503, response.status)
        self.assertEqual({}, self.service.relay.pending)

    async def test_streams_are_room_scoped_and_require_updated_clients(self):
        a, b, wa, wb = await self.pair()
        identifier = await self.register(a, 300)
        stranger = await (await self.client.post('/together/api/rooms', json=dict(key=KEY, name='C'))).json()
        response = await self.client.get(f"/together/share/{a['room']}/{identifier}", headers=self.auth(stranger))
        self.assertEqual(401, response.status)
        self.service.rooms[a['room']].members[b['member']].protocol = 2
        response = await self.client.post('/together/share/' + a['room'], headers=self.auth(a), json=dict(size=300, mime='video/mp4'))
        self.assertEqual(409, response.status)

    async def test_full_call_changes_on_both_phones_without_pausing_movie(self):
        a, b, wa, wb = await self.pair()
        await wa.send_json(dict(type='callView', enabled=True))
        await self.event(wa, 'error')
        await wa.send_json(dict(type='media', seq=1, media=dict(kind='youtube', source='aqz-KE-bpKQ')))
        state = await self.event(wb, 'state', lambda s: s['media'] is not None)
        mid = state['media']['id']
        for ws in (wa, wb):
            await ws.send_json(dict(type='status', ready=True, mediaId=mid))
            await ws.send_json(dict(type='call', active=True, mic=True, camera=True))
        await self.event(wa, 'state', lambda s: all(m['ready'] and m['call'] for m in s['members']))
        await wa.send_json(dict(type='play', seq=2, mediaId=mid))
        await self.event(wa, 'state', lambda s: s['playing'])
        await wa.send_json(dict(type='callView', enabled=True))
        for ws in (wa, wb):
            state = await self.event(ws, 'state', lambda s: s['callFullscreen'])
            self.assertTrue(state['playing'])
        await wb.send_json(dict(type='callView', enabled=False))
        for ws in (wa, wb):
            state = await self.event(ws, 'state', lambda s: not s['callFullscreen'] and s['playing'])
            self.assertTrue(state['playing'])

    async def test_only_source_phone_can_answer_a_pending_read(self):
        a, b, wa, wb = await self.pair()
        identifier = await self.register(a, 300)
        room = self.service.rooms[a['room']]
        share = self.service.relay.shares[identifier]
        task = asyncio.create_task(self.service.relay.fetch(room, share, identifier, 20, 64))
        self.tasks.append(task)
        request = await self.event(wa, 'fileRead')
        payload = dict(type='fileChunk', request=request['request'], data=base64.b64encode(b'x' * 64).decode())
        with self.assertRaises(ValueError):
            await self.service.relay.chunk(room, room.members[b['member']], payload)
        self.assertFalse(task.done())
        await wa.send_json(payload)
        self.assertEqual(b'x' * 64, await task)
        self.assertEqual({}, self.service.relay.pending)

    async def test_invalid_registration_and_removed_owner_do_not_leak_file_access(self):
        a, b, wa, wb = await self.pair()
        route = '/together/share/' + a['room']
        for size in (-1, 0, True, 30 * 1024**3):
            response = await self.client.post(route, headers=self.auth(a), json=dict(size=size, mime='video/mp4'))
            self.assertEqual(400, response.status)
        identifier = await self.register(a, 300)
        await wa.send_json(dict(type='leave'))
        await self.event(wb, 'state', lambda s: len(s['members']) == 1)
        self.service.relay.cleanup()
        response = await self.client.get(route + '/' + identifier, headers=self.auth(b))
        self.assertEqual(404, response.status)
