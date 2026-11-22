import asyncio
import unittest
from aiohttp.test_utils import TestClient, TestServer

from game_physics import (
    COURSES, arrow_shot, golf_shot, pool_shot, rack, soccer_step, soccer_world, tank_shot, terrain, wind,
)
from games import Match, clock
from service import Together
from test_service import KEY


class GamePhysicsTests(unittest.TestCase):
    def test_pool_break_moves_the_rack_and_remains_deterministic(self):
        shot = pool_shot(rack(), 1, 0, .84, 0)
        self.assertEqual(shot, pool_shot(rack(), 1, 0, .84, 0))
        frames, balls, sunk, first, _, _ = shot
        self.assertGreater(len(frames), 10)
        self.assertEqual(1, first)
        self.assertEqual(16, len(balls) + len(sunk))
        self.assertNotEqual(rack(), balls)

    def test_pool_eight_and_scratches_require_legal_contact(self):
        match = Match('pool', ('a', 'b'), 'a')
        match.begin(clock())
        match.board.update(breakShot=False, groups=[1, 2], balls=[[0, 20, 30], [8, 60, 30], [9, 70, 30]])
        match.pending = ([[0, 23, 31], [9, 70, 30]], [8], 8, True, 0)
        match.resolve(clock())
        self.assertEqual(('finished', 0), (match.phase, match.winner))
        match.begin(clock())
        match.board.update(breakShot=False, groups=[1, 2])
        match.pending = ([[8, 15, 20]], [0], 1, True, 0)
        match.resolve(clock())
        self.assertTrue(match.board['ballInHand'])
        self.assertEqual(1, match.turn)

    def test_golf_cup_and_score_count_once(self):
        course = COURSES[0]
        frames, pos, sunk = golf_shot(course, [course['cup'][0] - 2.3, course['cup'][1]], 1, 0, .18, 0)
        self.assertTrue(sunk)
        self.assertEqual(course['cup'], pos)
        self.assertGreater(len(frames), 1)
        match = Match('golf', ('a', 'b'), 'a')
        match.begin(clock())
        match.shoot(dict(x=1, y=0, power=.65, spin=0, weapon=0), clock())
        self.assertEqual([0, 0], match.scores)
        deadline = match.deadline
        match.tick(deadline, {'a', 'b'})
        match.tick(deadline + 1, {'a', 'b'})
        self.assertEqual([1, 0], match.scores)

    def test_wind_scoring_and_authoritative_tank_damage(self):
        _, hit, score = arrow_shot(72 - wind(0), 29, 1, 0)
        self.assertGreaterEqual(score, 9)
        self.assertAlmostEqual(hit[0], 72, delta=.08)
        self.assertEqual(0, arrow_shot(0, 0, .7, 0)[2])
        ground = terrain()
        best = max((tank_shot(ground, 0, angle, power, 0) for angle in range(20, 76, 2)
                    for power in (.45, .55, .70, .85, .95, 1)),
                   key=lambda shot: shot[3])
        self.assertGreater(best[3], 0)
        self.assertNotEqual(ground, best[1])

    def test_simultaneous_soccer_inputs_ball_collision_and_goal(self):
        world = soccer_world()
        for _ in range(48):
            soccer_step(world, [(0, 1, False, False), (0, 1, False, False)], 1 / 30)
        self.assertGreater(world[0][0], 20)
        self.assertLess(world[1][0], 80)
        world[0][:] = [40, 30, 22, 0, 0]
        world[2][:] = [45, 30, 0, 0, 0]
        soccer_step(world, [(0, 1, True, False), (0, 0, False, False)], 1 / 30)
        self.assertGreater(world[2][2], 0)
        world[2][:] = [99.9, 30, 15, 0, 0]
        self.assertEqual(0, soccer_step(world, [(0, 0, False, False)] * 2, 1 / 30))


class GameSocketTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.service = Together(KEY)
        self.client = TestClient(TestServer(self.service.app()))
        await self.client.start_server()
        self.sockets = []
        seed = await self.client.post('/together/api/rooms', json={'key': KEY, 'name': 'Aster'})
        self.a = await seed.json()
        joined = await self.client.post('/together/api/join', json={'name': 'Cypress', 'invite': self.a['invite']})
        self.b = await joined.json()
        self.left, self.right = await self.attach(self.a), await self.attach(self.b)
        self.seq = 0

    async def asyncTearDown(self):
        for socket in self.sockets:
            await socket.close()
        await self.client.close()

    async def attach(self, ticket):
        socket = await self.client.ws_connect('/together/ws')
        self.sockets.append(socket)
        await socket.send_json(dict(room=ticket['room'], token=ticket['token'], protocol=3, games=1))
        await self.event(socket, 'gamesAvailable')
        return socket

    async def event(self, socket, name, predicate=lambda event: True):
        async with asyncio.timeout(5):
            while True:
                event = await socket.receive_json()
                if event.get('type') == name and predicate(event):
                    return event

    async def game(self, socket, action, identifier=None, **kwargs):
        self.seq += 1
        await socket.send_json(dict(type='game', action=action, id=identifier, gameSeq=self.seq, **kwargs))

    async def begin(self, kind, previous=None):
        await self.game(self.left, 'invite', previous, kind=kind)
        invite = await self.event(self.right, 'gameState', lambda e: e['phase'] == 'inviting' and e['id'] != previous)
        self.assertEqual('inviting', invite['phase'])
        identifier = invite['id']
        await self.game(self.left, 'accept', identifier)
        await self.event(self.left, 'gameError')
        await self.game(self.right, 'accept', identifier)
        await self.event(self.right, 'gameState', lambda e: e['phase'] == 'lobby')
        await self.game(self.left, 'ready', identifier, enabled=True)
        await self.game(self.right, 'ready', identifier, enabled=True)
        await self.event(self.left, 'gameState', lambda e: len(e['ready']) == 2)
        await self.game(self.left, 'start', identifier)
        a = await self.event(self.left, 'gameState', lambda e: e['phase'] == 'active')
        b = await self.event(self.right, 'gameState', lambda e: e['phase'] == 'active')
        self.assertEqual(a['board'], b['board'])
        return identifier

    async def test_shot_turn_ownership_aim_replay_chat_pause_and_reconnect(self):
        identifier = await self.begin('archery')
        shot = dict(x=70.6, y=29, power=1, turnNumber=0)
        await self.game(self.right, 'shoot', identifier, **shot)
        await self.event(self.right, 'gameError')
        await self.game(self.left, 'aim', identifier, **shot)
        preview = await self.event(self.right, 'gameAim')
        self.assertEqual(shot['x'], preview['aim']['x'])
        await self.game(self.left, 'shoot', identifier, **shot)
        a = await self.event(self.left, 'gameState', lambda e: e['phase'] == 'resolving')
        b = await self.event(self.right, 'gameState', lambda e: e['phase'] == 'resolving')
        self.assertEqual(a['replay'], b['replay'])
        await self.left.send_json(dict(type='chat', text='Nice shot'))
        self.assertEqual('Nice shot', (await self.event(self.right, 'chat'))['text'])
        await self.left.send_json(dict(type='reaction', emoji='clap'))
        self.assertEqual('clap', (await self.event(self.right, 'reaction'))['emoji'])
        result = await self.event(self.right, 'gameState', lambda e: e['turnNumber'] == 1)
        self.assertGreaterEqual(result['scores'][0], 9)
        self.assertEqual(1, result['turn'])
        await self.game(self.left, 'pause', identifier)
        await self.event(self.left, 'gameState', lambda e: len(e['pauseVotes']) == 1)
        self.assertEqual('active', self.service.games.matches[self.a['room']].phase)
        await self.game(self.right, 'pause', identifier)
        await self.event(self.right, 'gameState', lambda e: e['phase'] == 'paused')
        await self.right.close()
        self.right = await self.attach(self.b)
        restored = await self.event(self.right, 'gameState')
        self.assertEqual((identifier, 'paused', result['scores']), (restored['id'], restored['phase'], restored['scores']))
        for socket in (self.left, self.right):
            await self.game(socket, 'resume', identifier)
        await self.event(self.right, 'gameState', lambda e: e['phase'] == 'active' and e['turnNumber'] == 1)
        await self.game(self.right, 'forfeit', identifier)
        final = await self.event(self.left, 'gameState', lambda e: e['phase'] == 'finished')
        self.assertEqual(0, final['winner'])
        await self.game(self.left, 'invite', identifier, kind='archery')
        rematch = await self.event(self.right, 'gameState', lambda e: e['phase'] == 'inviting')
        self.assertNotEqual(identifier, rematch['id'])

    async def test_two_real_time_clients_have_same_world_and_neutral_input_expires(self):
        identifier = await self.begin('soccer')
        match = self.service.games.matches[self.a['room']]
        match.board['kickoff'] = clock() - 1
        async def drive(socket, player):
            for i in range(8):
                await self.game(socket, 'input', identifier, x=0, y=1, boost=player == 0)
                await asyncio.sleep((.065, .125, .09)[(i + player) % 3])
        await asyncio.gather(drive(self.left, 0), drive(self.right, 1))
        left = await self.event(self.left, 'gameState', lambda e: e['board']['bodies'][0][0] > 25 and e['board']['bodies'][1][0] < 77)
        right = await self.event(self.right, 'gameState', lambda e: e['revision'] == left['revision'])
        self.assertEqual(left['board'], right['board'])
        await self.left.close()
        await asyncio.sleep(.18)
        self.assertEqual(0, match.inputs[0][1])
        self.assertEqual('active', match.phase)

    async def test_pool_golf_and_tank_share_authoritative_replays(self):
        previous = None
        for kind, direction in [('pool', (1, 0)), ('golf', (1, 0)), ('tank', (44, 0))]:
            identifier = await self.begin(kind, previous)
            await self.game(self.left, 'shoot', identifier, x=direction[0], y=direction[1], power=.75, turnNumber=0)
            a = await self.event(self.left, 'gameState', lambda e: e['id'] == identifier and e['phase'] == 'resolving')
            b = await self.event(self.right, 'gameState', lambda e: e['id'] == identifier and e['phase'] == 'resolving')
            self.assertEqual(a['replay'], b['replay'])
            self.assertGreater(len(a['replay']['frames']), 2)
            # Let the shared match clock commit the previously simulated turn.
            match = self.service.games.matches[self.a['room']]
            match.deadline = clock()
            done = await self.event(self.right, 'gameState', lambda e: e['id'] == identifier and e['turnNumber'] == 1)
            if kind == 'golf':
                self.assertEqual([1, 0], done['scores'])
            await self.game(self.right, 'forfeit', identifier)
            await self.event(self.left, 'gameState', lambda e: e['id'] == identifier and e['phase'] == 'finished')
            previous = identifier
