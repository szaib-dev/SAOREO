import asyncio
import base64
import hashlib
import hmac
import unittest
from unittest.mock import patch
from aiohttp.test_utils import TestClient, TestServer
from service import Together, Room, Member, media_item

KEY = 'test-private-access-key-at-least-16'
VIDEO = dict(kind='youtube', source='aqz-KE-bpKQ', title='Reference video')

class TimelineTests(unittest.TestCase):
    def test_pause_freezes_position_and_resume_waits_for_both(self):
        room = Room('room', 'invite', position=1000, anchor=1000, playing=True, wanted=True, media=VIDEO)
        room.members = {'a': Member('a', 'a', 'A', 0, ws=object(), ready=True),
                        'b': Member('b', 'b', 'B', 1, ws=object(), ready=True)}
        with patch('service.now', return_value=3000): room.settle()
        self.assertEqual(3000, room.position)
        self.assertTrue(room.playing)
        room.members['b'].buffering = True
        with patch('service.now', return_value=4000): room.settle()
        self.assertEqual(4000, room.position)
        self.assertFalse(room.playing)
        self.assertEqual(4000, room.position_at(9000))
        room.members['b'].buffering = False
        with patch('service.now', return_value=10000): room.settle()
        self.assertTrue(room.playing)
        self.assertEqual(5000, room.position_at(11000))

    def test_media_validation(self):
        for source in ['file:///etc/passwd', 'http://example.com/movie', 'https://a:b@example.com/video']:
            with self.assertRaises(ValueError): media_item(dict(kind='stream', source=source))
        with self.assertRaises(ValueError): media_item(dict(kind='youtube', source='invalid'))
        with self.assertRaises(ValueError): media_item(dict(kind='stream', source='https://example.com', duration=float('nan')))

    def test_turn_credentials_expire_and_are_signed(self):
        svc = Together(KEY, 'turn-test-secret', 'example.com')
        server = svc.ice(Member('id', 'token', 'A', 0))[1]
        expected = base64.b64encode(hmac.new(b'turn-test-secret', server['username'].encode(), hashlib.sha1).digest()).decode()
        self.assertEqual(expected, server['credential'])

class RoomTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.service = Together(KEY)
        self.client = TestClient(TestServer(self.service.app()))
        await self.client.start_server()
        self.sockets = []
    async def asyncTearDown(self):
        for ws in self.sockets: await ws.close()
        await self.client.close()
    async def ticket(self):
        response = await self.client.post('/together/api/rooms', json=dict(key=KEY, name='A'))
        self.assertEqual(200, response.status)
        return await response.json()
    async def attach(self, ticket, protocol=2):
        ws = await self.client.ws_connect('/together/ws')
        self.sockets.append(ws)
        await ws.send_json(dict(room=ticket['room'], token=ticket['token'], protocol=protocol))
        await self.event(ws, 'state')
        return ws
    async def event(self, ws, kind, predicate=lambda x: True):
        async with asyncio.timeout(3):
            while True:
                data = await ws.receive_json()
                if data.get('type') == kind and predicate(data): return data
    async def pair(self, protocol=2):
        a = await self.ticket()
        response = await self.client.post('/together/api/join', json=dict(invite=a['invite'], name='B'))
        b = await response.json()
        return a, b, await self.attach(a, protocol), await self.attach(b, protocol)

    async def test_private_access_and_two_person_limit(self):
        response = await self.client.post('/together/api/rooms', json=dict(key='wrong'))
        self.assertEqual(401, response.status)
        a = await self.ticket()
        response = await self.client.post('/together/api/join', json=dict(invite=a['invite']))
        self.assertEqual(200, response.status)
        response = await self.client.post('/together/api/join', json=dict(invite=a['invite']))
        self.assertEqual(409, response.status)
        response = await self.client.post('/together/api/join', json=dict(invite=a['room'] + '.wrong'))
        self.assertEqual(404, response.status)

    async def test_both_participants_can_navigate_and_select_iptv(self):
        _, _, a, b = await self.pair(protocol=4)
        ref = 'c' * 64
        await a.send_json(dict(type='iptvBrowse', view=dict(kind='MOVIE', category='', focused='')))
        await self.event(b, 'state', lambda s: (s['iptvBrowse'] or {}).get('kind') == 'MOVIE')
        await b.send_json(dict(type='iptvBrowse', view=dict(kind='MOVIE', category='', focused=ref)))
        await self.event(a, 'state', lambda s: (s['iptvBrowse'] or {}).get('focused') == ref)
        await a.send_json(dict(type='media', seq=1, media=dict(kind='iptv', source='http://provider/private')))
        await self.event(a, 'error')
        await b.send_json(dict(type='media', seq=1, media=dict(kind='iptv', source=ref)))
        selected = await self.event(a, 'state', lambda s: s['media'] is not None)
        self.assertEqual(ref, selected['media']['source'])
        self.assertIsNone(selected['iptvBrowse'])

    async def test_mutual_play_pause_seek_and_duplicate_order(self):
        a,b,wa,wb = await self.pair()
        await wa.send_json(dict(type='media', seq=1, media=VIDEO))
        state = await self.event(wb, 'state', lambda s: s['media'] is not None)
        mid = state['media']['id']
        for ws in [wa,wb]: await ws.send_json(dict(type='status', ready=True, mediaId=mid))
        await self.event(wb, 'state', lambda s: all(m['ready'] for m in s['members']))
        await wb.send_json(dict(type='play', seq=1, mediaId=mid))
        state = await self.event(wa, 'state', lambda s: s['playing'])
        self.assertTrue(state['playing'])
        await wa.send_json(dict(type='seek', seq=2, mediaId=mid, position=120000))
        await self.event(wb, 'state', lambda s: s['position'] >= 120000)
        await wa.send_json(dict(type='pause', seq=3, mediaId=mid))
        state = await self.event(wb, 'state', lambda s: not s['wanted'] and s['position'] >= 120000)
        self.assertFalse(state['playing'])
        await wa.send_json(dict(type='play', seq=2, mediaId=mid))
        state = await self.event(wa, 'state', lambda s: s['revision'] >= state['revision'])
        self.assertFalse(state['playing'])

    async def test_socket_reconnect_keeps_call_identity_and_resumes_playback(self):
        a, b, wa, wb = await self.pair()
        await wa.send_json(dict(type='media', seq=1, media=VIDEO))
        snapshot = await self.event(wb, 'state', lambda state: state['media'] is not None)
        media_id = snapshot['media']['id']
        for ws in (wa, wb):
            await ws.send_json(dict(type='call', active=True, mic=True, camera=True))
            await ws.send_json(dict(type='status', ready=True, mediaId=media_id))
        await self.event(wa, 'state', lambda state: all(m['call'] and m['ready'] for m in state['members']))
        await wa.send_json(dict(type='play', seq=2, mediaId=media_id))
        await self.event(wa, 'state', lambda state: state['playing'])
        await wb.close()
        paused = await self.event(wa, 'state', lambda state: any(not m['online'] for m in state['members']))
        self.assertFalse(paused['playing'])
        self.assertTrue(paused['wanted'])
        self.assertTrue(all(m['call'] for m in paused['members']))
        wb = await self.attach(b)
        await wb.send_json(dict(type='status', ready=True, mediaId=media_id))
        resumed = await self.event(wa, 'state', lambda state: state['playing'])
        self.assertEqual([a['member'], b['member']], [m['id'] for m in resumed['members']])
        self.assertTrue(all(m['call'] and m['camera'] for m in resumed['members']))
        self.assertGreaterEqual(resumed['position'], paused['position'])
        await wb.send_json(dict(type='call', active=False))
        await self.event(wa, 'state', lambda state: not state['members'][1]['call'])

    async def test_network_reports_identify_the_member_without_changing_playback(self):
        a, b, wa, wb = await self.pair()
        await wa.send_json(dict(type='ping', sent=12, slowConnection=True, member=b['member']))
        self.assertEqual(12, (await self.event(wa, 'pong'))['sent'])
        self.assertEqual(dict(type='network', member=a['member'], slow=True), await self.event(wb, 'network'))
        room = self.service.rooms[a['room']]
        revision = room.revision
        await wa.send_json(dict(type='ping', sent=13, slowConnection=False))
        self.assertEqual(dict(type='network', member=a['member'], slow=False), await self.event(wb, 'network'))
        self.assertEqual(revision, room.revision)

    async def test_finished_video_advances_shared_queue_once_and_waits_for_both_players(self):
        _, _, left, right = await self.pair()
        await left.send_json(dict(type='media', seq=1, media=VIDEO))
        start = await self.event(right, 'state', lambda s: s['media'] is not None)
        self.assertTrue(start['autoAdvanceQueue'])
        original = start['media']['id']
        for seq, title in enumerate(['Second', 'Third'], start=2):
            await left.send_json(dict(type='queueAdd', seq=seq, media=dict(VIDEO, title=title)))
        await self.event(right, 'state', lambda s: len(s['queue']) == 2)
        for ws in (left, right):
            await ws.send_json(dict(type='status', ready=True, mediaId=original))
        await self.event(right, 'state', lambda s: all(m['ready'] for m in s['members']))
        await left.send_json(dict(type='play', seq=4, mediaId=original))
        await self.event(right, 'state', lambda s: s['playing'])
        await left.send_json(dict(type='finished', seq=5, mediaId=original))
        queued = await self.event(right, 'state', lambda s: s['media']['title'] == 'Second')
        self.assertTrue(queued['wanted'])
        self.assertFalse(queued['playing'])
        next_id = queued['media']['id']
        await right.send_json(dict(type='finished', seq=1, mediaId=original))
        await right.send_json(dict(type='status', ready=True, mediaId=next_id))
        waiting = await self.event(left, 'state', lambda s: s['media'] and
                                   s['media']['id'] == next_id and s['members'][1]['ready'])
        self.assertFalse(waiting['playing'])
        self.assertEqual(['Third'], [item['title'] for item in waiting['queue']])
        await left.send_json(dict(type='status', ready=True, mediaId=next_id))
        playing = await self.event(right, 'state', lambda s: s['media']['id'] == next_id and s['playing'])
        self.assertEqual('Second', playing['media']['title'])

    async def test_chat_reactions_and_call_signals(self):
        a,b,wa,wb = await self.pair()
        await wa.send_json(dict(type='chat', text='Hello partner'))
        self.assertEqual('Hello partner', (await self.event(wb, 'chat'))['text'])
        await wb.send_json(dict(type='reaction', emoji='heart'))
        self.assertEqual('heart', (await self.event(wa, 'reaction'))['emoji'])
        for ws in [wa,wb]: await ws.send_json(dict(type='call', active=True, mic=True, camera=False))
        await self.event(wa, 'state', lambda s: all(m['call'] for m in s['members']))
        await wa.send_json(dict(type='signal', payload=dict(type='offer', sdp='test-sdp')))
        self.assertEqual('test-sdp', (await self.event(wb, 'signal'))['payload']['sdp'])

    async def test_shared_local_video_is_private_and_seekable(self):
        a, b, wa, wb = await self.pair()
        path = '/together/media/' + a['room']
        headers = {'Authorization': 'Bearer ' + a['token']}
        payload = b'\x00\x00\x00\x18ftypmp42test-payload'
        response = await self.client.post(path, data=payload)
        self.assertEqual(401, response.status)
        response = await self.client.post(path, data=payload, headers=headers)
        self.assertEqual(200, response.status)
        video = (await response.json())['id']
        await wa.send_json(dict(type='media', seq=1, media=dict(kind='upload', source=video)))
        await self.event(wb, 'state', lambda s: s['media'] is not None)
        response = await self.client.get(path + '/' + video, headers={
            'Authorization': 'Bearer ' + b['token'], 'Range': 'bytes=4-7',
        })
        self.assertEqual(206, response.status)
        self.assertEqual(b'ftyp', await response.read())
        outsider = await self.ticket()
        response = await self.client.get(path + '/' + video, headers={'Authorization': 'Bearer ' + outsider['token']})
        self.assertEqual(401, response.status)

    async def test_early_play_does_not_start_when_partner_becomes_ready(self):
        a, b, wa, wb = await self.pair()
        await wa.send_json(dict(type='media', seq=1, media=VIDEO))
        state = await self.event(wb, 'state', lambda s: s['media'] is not None)
        mid = state['media']['id']
        await wa.send_json(dict(type='play', seq=2, mediaId=mid))
        self.assertIn('both players', (await self.event(wa, 'error'))['message'])
        for ws in (wa, wb):
            await ws.send_json(dict(type='status', ready=True, mediaId=mid))
        ready = await self.event(wb, 'state', lambda s: all(m['ready'] for m in s['members']))
        self.assertFalse(ready['wanted'])
        self.assertFalse(ready['playing'])

    async def test_queue_and_stale_media_controls(self):
        a,b,wa,wb = await self.pair()
        await wa.send_json(dict(type='queueAdd', seq=1, media=VIDEO))
        state = await self.event(wb, 'state', lambda s: len(s['queue']) == 1)
        await wb.send_json(dict(type='queueSelect', seq=1, id=state['queue'][0]['id']))
        state = await self.event(wa, 'state', lambda s: s['media'] is not None)
        self.assertEqual([], state['queue'])
        await wa.send_json(dict(type='seek', seq=2, mediaId='old', position=1000))
        self.assertIn('changed', (await self.event(wa, 'error'))['message'])

    async def test_socket_cannot_join_with_invitation_instead_of_member_token(self):
        a = await self.ticket()
        ws = await self.client.ws_connect('/together/ws')
        self.sockets.append(ws)
        await ws.send_json(dict(room=a['room'], token=a['invite']))
        result = await ws.receive()
        self.assertEqual(4003, result.data)

if __name__ == '__main__': unittest.main()
