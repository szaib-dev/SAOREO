"""Opt-in live integration test. Credentials are read from a local file, never printed."""
import argparse
import asyncio
import json
import pathlib
import aiohttp
from aioice import Connection, TransportPolicy

async def event(ws, kind, predicate=lambda x: True):
    async with asyncio.timeout(15):
        async for msg in ws:
            data = json.loads(msg.data)
            if data.get('type') == kind and predicate(data): return data
    raise AssertionError('Missing ' + kind)

async def main(access_file):
    values = pathlib.Path(access_file).read_text().splitlines()
    server = next(x.removeprefix('Server: ') for x in values if x.startswith('Server: '))
    key = next(x.removeprefix('Room creator access key: ') for x in values if x.startswith('Room creator access key: '))
    async with aiohttp.ClientSession() as http:
        async with http.get(server + '/together/health') as response:
            assert response.status == 200
        async with http.post(server + '/together/api/rooms', json=dict(name='Live test A', key=key)) as response:
            assert response.status == 200, await response.text()
            a = await response.json()
        async with http.post(server + '/together/api/join', json=dict(name='Live test B', invite=a['invite'])) as response:
            assert response.status == 200
            b = await response.json()
        sockets = []
        connections = []
        try:
            for ticket in (a,b):
                ws = await http.ws_connect(server.replace('https://', 'wss://') + '/together/ws')
                sockets.append(ws)
                await ws.send_json(dict(room=ticket['room'], token=ticket['token']))
                await event(ws, 'state')
            wa,wb = sockets
            await wa.send_json(dict(type='media', seq=1, media=dict(kind='youtube', source='aqz-KE-bpKQ', title='Integration test')))
            state = await event(wb, 'state', lambda s: s['media'] is not None)
            media_id = state['media']['id']
            for ws in sockets: await ws.send_json(dict(type='status', ready=True, mediaId=media_id))
            await event(wa, 'state', lambda s: all(m['ready'] for m in s['members']))
            await wb.send_json(dict(type='play', seq=1, mediaId=media_id))
            await event(wa, 'state', lambda s: s['playing'])
            await wa.send_json(dict(type='pause', seq=2, mediaId=media_id))
            await event(wb, 'state', lambda s: not s['wanted'])
            await wa.send_json(dict(type='chat', text='live integration message'))
            await event(wb, 'chat', lambda s: s['text'] == 'live integration message')
            print('PASS: public HTTPS, private room, both-way playback control, live chat')
            for transport in ('udp', 'tcp'):
                connections = []
                for index, ticket in enumerate((a,b)):
                    turn = ticket['iceServers'][1]
                    c = Connection(ice_controlling=index == 0, components=1, use_ipv6=False,
                        turn_server=('193.181.223.127',3478), turn_username=turn['username'], turn_password=turn['credential'],
                        turn_transport=transport, transport_policy=TransportPolicy.RELAY)
                    connections.append(c)
                left,right = connections
                await asyncio.wait_for(asyncio.gather(left.gather_candidates(), right.gather_candidates()), 20)
                assert all(c.local_candidates and all(x.type == 'relay' for x in c.local_candidates) for c in connections)
                for local,remote in [(left,right),(right,left)]:
                    local.remote_username = remote.local_username; local.remote_password = remote.local_password
                    for candidate in remote.local_candidates: await local.add_remote_candidate(candidate)
                    await local.add_remote_candidate(None)
                await asyncio.wait_for(asyncio.gather(left.connect(),right.connect()), 25)
                await left.send(b'SAOREO verified TURN path')
                assert await asyncio.wait_for(right.recv(), 10) == b'SAOREO verified TURN path'
                await right.send(b'partner reply')
                assert await asyncio.wait_for(left.recv(),10) == b'partner reply'
                print('PASS: forced TURN ' + transport.upper() + ' allocations and bidirectional relayed packets')
                await asyncio.gather(*(c.close() for c in connections)); connections = []
        finally:
            await asyncio.gather(*(c.close() for c in connections))
            for ws in sockets:
                if not ws.closed: await ws.send_json(dict(type='leave')); await ws.close()

if __name__ == '__main__':
    parser=argparse.ArgumentParser(); parser.add_argument('access_file'); args=parser.parse_args()
    asyncio.run(main(args.access_file))
