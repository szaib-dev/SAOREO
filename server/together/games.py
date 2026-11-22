"""Room-scoped game invitations, commands and authoritative match clocks."""
import asyncio
from dataclasses import dataclass, field
import math
import secrets
import time

from game_physics import (
    COURSES, POCKETS, arrow_shot, clamp, golf_shot, pool_shot, rack, rounded,
    soccer_step, soccer_world, tank_shot, terrain, wind,
)

SLOTS = {'pool': 45, 'golf': 45, 'archery': 20, 'tank': 30, 'soccer': 180}
NEUTRAL = (0., 0., False, False)
FRAME_MS = 40


def clock():
    return int(time.time() * 1000)


def bounded(data, key, low, high, default=None):
    value = data.get(key, default)
    if type(value) not in (int, float) or not low <= value <= high:
        raise ValueError('Invalid game control')
    return float(value)


def ball_group(number):
    return 1 if 1 <= number < 8 else 2 if 8 < number <= 15 else 0


@dataclass
class Match:
    kind: str
    players: tuple
    inviter: str
    id: str = field(default_factory=lambda: secrets.token_hex(10))
    phase: str = 'inviting'
    revision: int = 0
    deadline: int = field(default_factory=lambda: clock() + 45000)
    turn: int = 0
    turn_number: int = 0
    scores: list = field(default_factory=lambda: [0, 0])
    ready: set = field(default_factory=set)
    sequences: dict = field(default_factory=dict)
    pause_votes: set = field(default_factory=set)
    resume_votes: set = field(default_factory=set)
    board: dict = field(default_factory=dict)
    replay: dict | None = None
    aim: dict | None = None
    winner: int | None = None
    notice: str = ''
    inputs: list = field(default_factory=lambda: [NEUTRAL, NEUTRAL])
    input_times: list = field(default_factory=lambda: [0, 0])
    disconnect_times: dict = field(default_factory=dict)
    pending: object = None
    pause_remaining: int = 0
    next_sim: float = 0.
    next_snapshot: int = 0
    overtime: bool = False

    def state(self, *, include_replay=True):
        board = self.board
        if self.kind == 'soccer' and 'bodies' in board:
            board = {**board, 'bodies': rounded(board['bodies'])}
        return dict(
            type='gameState', id=self.id, kind=self.kind, phase=self.phase, revision=self.revision,
            inviter=self.inviter, players=self.players, deadline=self.deadline, stamp=clock(),
            turn=self.turn, turnNumber=self.turn_number, scores=self.scores, ready=sorted(self.ready),
            board=board, replay=self.replay if include_replay else None, aim=self.aim,
            pauseVotes=sorted(self.pause_votes), resumeVotes=sorted(self.resume_votes),
            winner=self.winner, notice=self.notice, ack=self.sequences, overtime=self.overtime,
        )

    def begin(self, stamp):
        self.phase = 'active'
        self.deadline = stamp + SLOTS[self.kind] * 1000
        if self.kind == 'pool':
            self.board = dict(balls=rack(), groups=[0, 0], breakShot=True, ballInHand=False)
        elif self.kind == 'golf':
            self.golf_hole(0)
        elif self.kind == 'archery':
            self.board = dict(round=0, arrows=[0, 0], wind=wind(0), hit=None)
        elif self.kind == 'tank':
            self.scores = [100, 100]
            self.board = dict(terrain=terrain(), heavy=[2, 2], multi=[2, 2], impacts=[])
        else:
            self.board = dict(bodies=soccer_world(), goal=None, kickoff=stamp + 1500)
            self.next_sim = time.monotonic()

    def golf_hole(self, hole):
        course = COURSES[hole]
        self.board = dict(hole=hole, course=course, balls=[course['tee'][:], course['tee'][:]],
                          strokes=[0, 0], sunk=[False, False], bumper=[48, 25])

    def advance(self, stamp, keep=False):
        self.turn_number += 1
        if not keep:
            self.turn = 1 - self.turn
        self.aim = None
        self.deadline = stamp + SLOTS[self.kind] * 1000

    def finish(self, winner, notice, phase='finished'):
        self.phase, self.winner, self.notice = phase, winner, notice
        self.deadline = 0
        self.replay = None
        self.aim = None
        self.pending = None
        self.inputs[:] = [NEUTRAL, NEUTRAL]

    def place(self, data):
        x = bounded(data, 'x', 3, 97)
        y = bounded(data, 'y', 8, 52)
        if any(math.dist((x, y), ball[1:3]) < 3.55 for ball in self.board['balls'] if ball[0] != 0):
            raise ValueError('Place the cue ball clear of other balls')
        if any(math.dist((x, y), pocket) < 3.5 for pocket in POCKETS):
            raise ValueError('Place the cue ball away from pockets')
        self.board['balls'] = [[0, x, y]] + [ball for ball in self.board['balls'] if ball[0] != 0]
        self.board['ballInHand'] = False

    def controls(self, data):
        x = bounded(data, 'x', -1, 100)
        y = bounded(data, 'y', -1, 60)
        power = bounded(data, 'power', .05, 1)
        if self.kind in ('pool', 'golf') and (abs(x) > 1 or abs(y) > 1 or math.hypot(x, y) < .1):
            raise ValueError('Choose an aiming direction')
        spin = bounded(data, 'spin', -1, 1, 0)
        weapon = int(bounded(data, 'weapon', 0, 2, 0))
        return dict(x=x, y=y, power=power, spin=spin, weapon=weapon)

    def shoot(self, aim, stamp):
        x, y, power = aim['x'], aim['y'], aim['power']
        side = self.turn
        if self.kind in ('pool', 'golf'):
            length = math.hypot(x, y)
            x, y = x / length, y / length
        if self.kind == 'pool':
            if self.board['ballInHand']:
                raise ValueError('Place the cue ball first')
            frames, balls, sunk, first, rail, rails = pool_shot(self.board['balls'], x, y, power, aim['spin'])
            self.pending = (balls, sunk, first, rail, rails)
        elif self.kind == 'golf':
            frames, pos, sunk = golf_shot(self.board['course'], self.board['balls'][side], x, y, power, self.turn_number)
            self.pending = pos, sunk, frames[-1][-1][1:3] if len(frames[-1]) > 1 else [48, 25]
        elif self.kind == 'archery':
            frames, hit, points = arrow_shot(clamp(x, 0, 100), clamp(y, 0, 60), power, self.board['round'])
            self.pending = hit, points
        else:
            weapon = aim['weapon']
            if weapon:
                supply = self.board['heavy' if weapon == 1 else 'multi']
                if supply[side] <= 0:
                    raise ValueError('That shell is out of stock')
                supply[side] -= 1
            frames, ground, impacts, damage = tank_shot(self.board['terrain'], side, clamp(x, 5, 85), power, weapon)
            self.pending = ground, impacts, damage
        self.aim = aim
        self.replay = dict(at=stamp + 150, step=FRAME_MS, frames=frames, side=side)
        self.phase = 'resolving'
        self.deadline = stamp + 150 + len(frames) * FRAME_MS + 280

    def pool_result(self, stamp):
        balls, sunk, first, rail, rails = self.pending
        side, other = self.turn, 1 - self.turn
        group = self.board['groups'][side]
        is_break = self.board['breakShot']
        own_remaining = any(ball_group(n) == group for n, _, _ in self.board['balls'] if n) if group else True
        wrong = first is None or (
            not is_break and (first == 8 if group == 0 else
                              ball_group(first) != group if own_remaining else first != 8))
        foul = 0 in sunk or wrong or (not rail and not any(n > 0 for n in sunk))
        if is_break:
            foul = 0 in sunk or not any(n > 0 for n in sunk) and rails < 4
        self.board['balls'] = balls
        self.board['breakShot'] = False
        if 8 in sunk:
            if is_break:
                if not any(math.dist((75, 30), ball[1:3]) < 3.6 for ball in balls):
                    spot = [75, 30]
                else:
                    spot = next([x, y] for y in (30, 26, 34, 22, 38) for x in range(72, 5, -4)
                                if not any(math.dist((x, y), b[1:3]) < 3.6 for b in balls))
                self.board['balls'].append([8, *spot])
            else:
                self.finish(side if not foul and not own_remaining and first == 8 else other,
                            'Eight ball pocketed' if not foul and not own_remaining and first == 8 else 'Early eight ball or foul')
                return
        if not is_break and not foul and group == 0:
            group = next((ball_group(n) for n in sunk if ball_group(n)), 0)
            if group:
                self.board['groups'][side] = group
                self.board['groups'][other] = 3 - group
        for i, assigned in enumerate(self.board['groups']):
            self.scores[i] = 7 - sum(ball_group(ball[0]) == assigned for ball in balls) if assigned else 0
        self.board['ballInHand'] = foul
        self.notice = 'Foul · ball in hand' if foul else ('Break complete' if is_break else 'Shot complete')
        self.advance(stamp, keep=not foul and any(n not in (0, 8) and (not group or ball_group(n) == group) for n in sunk))

    def resolve(self, stamp):
        self.phase, self.replay = 'active', None
        side = self.turn
        if self.kind == 'pool':
            self.pool_result(stamp)
        elif self.kind == 'golf':
            pos, sunk, bumper = self.pending
            self.board['bumper'] = bumper
            self.board['balls'][side] = pos
            self.golf_stroke(stamp, side, sunk)
        elif self.kind == 'archery':
            hit, points = self.pending
            self.board['hit'] = hit
            self.archery_score(stamp, points)
        else:
            ground, impacts, damage = self.pending
            self.board.update(terrain=ground, impacts=impacts)
            self.scores[1 - side] = max(0, self.scores[1 - side] - damage)
            self.notice = f'{damage} damage' if damage else 'Miss'
            if not self.scores[1 - side]:
                self.finish(side, 'Tank disabled')
            else:
                self.advance(stamp)
        self.pending = None

    def golf_stroke(self, stamp, side, sunk=False):
        strokes, complete = self.board['strokes'], self.board['sunk']
        strokes[side] += 1
        self.scores[side] += 1
        complete[side] = sunk or strokes[side] >= 8
        self.notice = 'In the cup' if sunk else ('Stroke limit' if complete[side] else 'Next putt')
        if all(complete):
            hole = self.board['hole'] + 1
            if hole == len(COURSES):
                a, b = self.scores
                self.finish(None if a == b else int(a > b), 'Course complete')
                return
            self.golf_hole(hole)
            self.notice = f'Hole {hole + 1} · {self.board["course"]["name"]}'
        self.advance(stamp, keep=self.board['sunk'][1 - side])

    def archery_score(self, stamp, points):
        arrows = self.board['arrows']
        arrows[self.turn] += 1
        self.scores[self.turn] += points
        self.notice = 'Bullseye' if points == 10 else f'{points} points'
        round_number = self.board['round']
        if min(arrows) >= (5 if round_number < 3 else 1):
            if round_number >= 2 and self.scores[0] != self.scores[1]:
                self.finish(int(self.scores[1] > self.scores[0]), 'Archery results')
                return
            round_number += 1
            self.board.update(round=round_number, arrows=[0, 0], wind=wind(round_number))
            self.notice = 'Shoot-off · one arrow each' if round_number >= 3 else f'Round {round_number + 1}'
        self.advance(stamp, keep=False)

    def tick(self, stamp, online):
        changed = False
        for index, player in enumerate(self.players):
            if player in online:
                changed |= self.disconnect_times.pop(player, None) is not None
            elif player not in self.disconnect_times:
                self.disconnect_times[player] = stamp
                self.inputs[index] = NEUTRAL
                changed = True
        if self.phase in ('finished', 'abandoned'):
            return False
        absent = [self.players.index(p) for p, at in self.disconnect_times.items() if stamp - at >= 90000]
        if absent:
            self.finish(1 - absent[0] if len(absent) == 1 and self.phase in ('active', 'resolving', 'paused') else None,
                        'Reconnect window expired', phase='abandoned')
            return True
        if self.phase == 'resolving' and stamp >= self.deadline:
            self.resolve(stamp)
            return True
        if self.phase == 'active' and self.kind == 'soccer':
            if stamp >= self.deadline:
                a, b = self.scores
                if a == b and not self.overtime:
                    self.overtime, self.deadline = True, stamp + 30000
                    self.notice = 'Golden goal'
                else:
                    self.finish(None if a == b else int(b > a), 'Final whistle')
                    return True
            current = time.monotonic()
            if stamp < self.board['kickoff']:
                self.next_sim = current
            steps = 0
            while self.phase == 'active' and stamp >= self.board['kickoff'] and self.next_sim < current and steps < 6:
                effective = [self.inputs[i] if stamp - self.input_times[i] <= 360 else NEUTRAL for i in range(2)]
                goal = soccer_step(self.board['bodies'], effective, 1 / 30)
                self.next_sim += 1 / 30
                steps += 1
                if goal is not None:
                    self.scores[goal] += 1
                    self.notice = 'Goal!'
                    if self.overtime:
                        self.finish(goal, 'Golden goal')
                    else:
                        self.board.update(bodies=soccer_world(), goal=goal, kickoff=stamp + 1400)
                    return True
            if current - self.next_sim > .3:
                self.next_sim = current
            if stamp - self.next_snapshot >= 100:
                self.next_snapshot = stamp
                return True
        if self.deadline and stamp >= self.deadline and self.phase != 'paused':
            if self.phase in ('inviting', 'lobby'):
                self.finish(None, 'Invitation or lobby expired', phase='abandoned')
            elif self.kind == 'archery':
                self.board['hit'] = None
                self.archery_score(stamp, 0)
            elif self.kind == 'golf':
                self.golf_stroke(stamp, self.turn)
            elif self.kind == 'pool':
                self.board.update(breakShot=False, ballInHand=True)
                self.notice = 'Shot clock expired · ball in hand'
                self.advance(stamp)
            else:
                self.notice = 'Turn clock expired'
                self.advance(stamp)
            return True
        return changed


class Games:
    def __init__(self, server):
        self.server = server
        self.matches = {}

    @staticmethod
    def online(room):
        return {m.id for m in room.members.values() if m.ws is not None and not m.ws.closed}

    async def send(self, room, match):
        match.revision += 1
        await self.broadcast(room, match, match.state())

    @staticmethod
    async def broadcast(room, match, event):
        async def deliver(socket):
            try:
                await asyncio.wait_for(socket.send_json(event), 1)
            except (ConnectionError, asyncio.TimeoutError, RuntimeError):
                return
        sockets = [member.ws for member in room.members.values()
                   if member.id in match.players and member.game_client and member.ws is not None and not member.ws.closed]
        await asyncio.gather(*(deliver(socket) for socket in sockets))

    async def sync(self, room, member):
        if member.game_client:
            match = self.matches.get(room.id)
            await member.ws.send_json(dict(type='gamesAvailable', version=1))
            if match and member.id in match.players:
                await member.ws.send_json(match.state())

    async def command(self, room, member, data):
        op = data.get('action')
        if not member.game_client or not isinstance(op, str):
            raise ValueError('Games requires the updated app')
        match = self.matches.get(room.id)
        player = member.id
        stamp = clock()
        if op == 'invite':
            kind = data.get('kind')
            if kind not in SLOTS or len(room.members) != 2 or any(not m.game_client for m in room.members.values()):
                raise ValueError('Both players need the latest Games update')
            if len(self.online(room)) != 2:
                raise ValueError('Wait for your partner to reconnect')
            if match and (match.phase not in ('finished', 'abandoned') or data.get('id') != match.id):
                raise ValueError('A game invitation or match already exists')
            players = tuple(m.id for m in sorted(room.members.values(), key=lambda m: m.ordinal))
            match = self.matches[room.id] = Match(kind, players, player)
            await self.send(room, match)
            return
        if not match or player not in match.players or data.get('id') != match.id:
            raise ValueError('This match has changed')
        side = match.players.index(player)
        seq = bounded(data, 'gameSeq', 1, 2**53)
        if seq <= match.sequences.get(player, 0):
            return
        phase = match.phase
        if match.tick(stamp, self.online(room)):
            await self.send(room, match)
            phase = match.phase
        if op == 'input' and phase == 'active' and match.kind == 'soccer':
            match.inputs[side] = (bounded(data, 'x', -1, 1), bounded(data, 'y', -1, 1),
                                  data.get('boost') is True, data.get('brake') is True)
            match.input_times[side] = stamp
            match.sequences[player] = seq
            return
        if op in ('accept', 'decline') and phase == 'inviting' and player != match.inviter:
            if op == 'accept':
                match.phase, match.deadline = 'lobby', stamp + 180000
            else:
                match.finish(None, 'Invitation declined', phase='abandoned')
        elif op == 'cancel' and phase == 'inviting' and player == match.inviter:
            match.finish(None, 'Invitation cancelled', phase='abandoned')
        elif op == 'ready' and phase == 'lobby':
            if data.get('enabled') is True:
                match.ready.add(player)
            else:
                match.ready.discard(player)
        elif op == 'start' and phase == 'lobby' and len(match.ready) == len(self.online(room)) == 2:
            match.begin(stamp)
        elif op in ('aim', 'shoot', 'place') and phase == 'active' and side == match.turn and match.kind != 'soccer':
            if data.get('turnNumber') != match.turn_number:
                return
            if op == 'place':
                if match.kind != 'pool' or not match.board['ballInHand']:
                    raise ValueError('The cue ball is not in hand')
                match.place(data)
            else:
                aim = match.controls(data)
                if op == 'aim':
                    match.aim = aim
                    match.sequences[player] = seq
                    await self.broadcast(room, match, dict(type='gameAim', id=match.id, turnNumber=match.turn_number,
                                                           sender=player, aim=aim))
                    return
                match.shoot(aim, stamp)
        elif op == 'pause' and phase == 'active':
            match.pause_votes.add(player)
            if len(match.pause_votes) == 2:
                match.phase, match.pause_remaining = 'paused', max(0, match.deadline - stamp)
                match.pause_votes.clear()
                match.deadline = 0
                match.inputs[:] = [NEUTRAL, NEUTRAL]
        elif op == 'pauseCancel' and phase == 'active':
            match.pause_votes.clear()
        elif op == 'resume' and phase == 'paused':
            match.resume_votes.add(player)
            if len(match.resume_votes) == 2:
                remaining = match.pause_remaining
                match.phase, match.deadline = 'active', stamp + remaining
                match.resume_votes.clear()
                match.next_sim = time.monotonic()
        elif op == 'forfeit' and phase in ('active', 'resolving', 'paused', 'lobby'):
            match.finish(1 - side if phase != 'lobby' else None, 'Opponent forfeited' if phase != 'lobby' else 'Lobby closed')
        else:
            raise ValueError('This action is unavailable now')
        match.sequences[player] = seq
        await self.send(room, match)

    async def run(self):
        while True:
            await asyncio.sleep(1 / 30)
            stamp = clock()
            snapshots = []
            for room_id, match in list(self.matches.items()):
                room = self.server.rooms.get(room_id)
                if room is None or not all(player in room.members for player in match.players):
                    self.matches.pop(room_id, None)
                    if room:
                        snapshots.append(self.broadcast(room, match, dict(type='gameClosed', id=match.id)))
                elif match.tick(stamp, self.online(room)):
                    snapshots.append(self.send(room, match))
            if snapshots:
                await asyncio.gather(*snapshots)
