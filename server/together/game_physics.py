"""Deterministic arcade simulations. Coordinates are normalized to a 100 x 60 court."""
import math

COURSES = (
    dict(name='Cypress bend', tee=[11, 45], cup=[88, 15], walls=[[31, 23, 37, 60], [60, 0, 66, 32]], sand=[67, 38, 92, 57], slope=[.07, -.1]),
    dict(name='Tidal links', tee=[10, 16], cup=[89, 46], walls=[[31, 0, 37, 29], [59, 32, 65, 60]], sand=[10, 39, 41, 55], slope=[-.06, .09]),
    dict(name='Alpine pass', tee=[11, 30], cup=[89, 30], walls=[[33, 0, 41, 21], [33, 39, 41, 60], [60, 0, 68, 24], [60, 36, 68, 60]], sand=[43, 35, 58, 55], slope=[.17, .03]),
)
POCKETS = ((2.5, 7.5), (50, 7.5), (97.5, 7.5), (2.5, 52.5), (50, 52.5), (97.5, 52.5))
TANK_X = (14, 86)
WEAPONS = ((35, 20, 1.0), (52, 13, .88), (22, 16, 1.0))


def clamp(value, low, high):
    return max(low, min(high, value))


def rounded(items):
    return [[round(value, 2) for value in row] for row in items]


def rack():
    numbers = (1, 9, 2, 10, 8, 3, 11, 4, 12, 5, 13, 6, 14, 7, 15)
    balls = [[0, 25, 30]]
    for col in range(5):
        for row in range(col + 1):
            balls.append([numbers[len(balls) - 1], 67 + col * 3.02, 30 + (row - col / 2) * 3.51])
    return balls


def pool_shot(balls, dx, dy, power, spin):
    # Fixed 120 Hz steps and a bounded trail at 25 frames per second.
    world = [[n, x, y, 0., 0.] for n, x, y in balls]
    cue = next(ball for ball in world if ball[0] == 0)
    cue[3:5] = [dx * (23 + 129 * power), dy * (23 + 129 * power)]
    first = None
    rail = False
    break_rails = set()
    pocketed = []
    frames = [rounded(row[:3] for row in world)]
    for frame in range(165):
        for _ in range(5):
            for ball in world[:]:
                n, x, y, vx, vy = ball
                x, y = x + vx / 125, y + vy / 125
                if any(math.hypot(x - px, y - py) < 3.0 for px, py in POCKETS):
                    pocketed.append(n)
                    world.remove(ball)
                    continue
                touched = False
                if x < 2.9 or x > 97.1:
                    x, vx = clamp(x, 2.9, 97.1), -vx * .85
                    if n == 0:
                        vy += spin * abs(vx) * .12
                    touched = True
                if y < 7.9 or y > 52.1:
                    y, vy = clamp(y, 7.9, 52.1), -vy * .85
                    if n == 0:
                        vx += spin * abs(vy) * .12
                    touched = True
                if touched and first is not None:
                    rail = True
                    if n:
                        break_rails.add(n)
                friction = max(0., 1 - .0072 - .10 / max(.1, math.hypot(vx, vy)))
                ball[1:] = [x, y, vx * friction, vy * friction]
            for i, a in enumerate(world):
                for b in world[i + 1:]:
                    dx, dy = b[1] - a[1], b[2] - a[2]
                    distance = math.hypot(dx, dy)
                    if not .0001 < distance < 3.5:
                        continue
                    nx, ny = dx / distance, dy / distance
                    separation = (3.5 - distance) / 2
                    a[1] -= nx * separation
                    a[2] -= ny * separation
                    b[1] += nx * separation
                    b[2] += ny * separation
                    closing = (a[3] - b[3]) * nx + (a[4] - b[4]) * ny
                    if closing > 0:
                        impulse = closing * .96
                        a[3] -= impulse * nx
                        a[4] -= impulse * ny
                        b[3] += impulse * nx
                        b[4] += impulse * ny
                        if a[0] == 0 and first is None:
                            first = b[0]
        frames.append(rounded(row[:3] for row in world))
        if all(math.hypot(row[3], row[4]) < .45 for row in world):
            break
    return frames, frames[-1], pocketed, first, rail, len(break_rails)


def golf_shot(course, pos, dx, dy, power, turn_number):
    x, y = pos
    vx, vy = dx * (8 + power * 72), dy * (8 + power * 72)
    frames = [[[0, x, y]]]
    for frame in range(140):
        for step in range(4):
            t = (frame * 4 + step) / 100
            # A moving bumper plays from the same phase on both phones.
            gate = [48, 25 + 7 * math.sin(t * 1.55 + turn_number * .7)]
            next_x, next_y = x + vx / 100, y + vy / 100
            if abs(next_x - gate[0]) < 2.1 and abs(next_y - gate[1]) < 4:
                vx = -vx * .77
                next_x = x
            for left, top, right, bottom in course['walls']:
                if left - 1.2 < next_x < right + 1.2 and top - 1.2 < y < bottom + 1.2:
                    vx, next_x = -vx * .75, x
                if left - 1.2 < next_x < right + 1.2 and top - 1.2 < next_y < bottom + 1.2:
                    vy, next_y = -vy * .75, y
            if not 2 < next_x < 98:
                vx = -vx * .77
            if not 2 < next_y < 58:
                vy = -vy * .77
            x, y = clamp(next_x, 2, 98), clamp(next_y, 2, 58)
            if math.dist((x, y), course['cup']) < 2.1 and math.hypot(vx, vy) < 33:
                return frames + [[[0, *course['cup']], [1, *gate]]], course['cup'][:], True
            a, b, c, d = course['sand']
            rolling = .948 if a <= x <= c and b <= y <= d else .984
            sx, sy = course['slope']
            speed = math.hypot(vx, vy)
            ramp = math.sin((x - 42) * math.pi / 16) * .11 if 42 < x < 58 else 0
            vx, vy = vx * rolling + sx * min(speed, 2) * .15 - ramp, vy * rolling + sy * min(speed, 2) * .15
        frames.append(rounded([[0, x, y], [1, *gate]]))
        if frame > 5 and math.hypot(vx, vy) < .55:
            break
    return frames, [round(x, 2), round(y, 2)], False


def wind(round_number):
    return round(math.sin((round_number + 1) * 2.11) * (1.4 + min(round_number, 5) * .85), 2)


def arrow_shot(x, y, power, round_number):
    wx = wind(round_number)
    target_radius = max(8.5, 16 - round_number * 1.3)
    hit = [x + wx / max(.35, power), y + (1 - power) ** 2 * (11 + round_number * 3)]
    radius = math.dist((72, 29), hit)
    points = 0 if radius >= target_radius else 10 - int(10 * radius / target_radius)
    frames = []
    for step in range(25):
        t = step / 24
        frames.append(rounded([[0, 13 + (hit[0] - 13) * t, 43 + (hit[1] - 43) * t - 8 * math.sin(math.pi * t)]]))
    return frames, [clamp(round(v, 2), -10, 110) for v in hit], points


def terrain():
    return [round(48 - 4.5 * math.cos(x * math.tau / 100) - 12 * math.exp(-((x - 43) / 16) ** 2), 2) for x in range(101)]


def tank_shot(ground, side, angle, power, weapon):
    damage, blast, speed_scale = WEAPONS[weapon]
    shots = [-8, 0, 8] if weapon == 2 else [0]
    enemy = 1 - side
    hit = 0
    impacts = []
    tracks = []
    ground = ground[:]
    for spread in shots:
        a = math.radians(clamp(angle + spread, 5, 85))
        speed = (10 + power * 58) * speed_scale
        vx = math.cos(a) * speed * (1 if side == 0 else -1)
        vy = -math.sin(a) * speed
        x, y = TANK_X[side], ground[TANK_X[side]] - 4
        track = [[x, y]]
        for i in range(110):
            vx *= .998
            vy += 22 * .04
            x, y = x + vx * .04, y + vy * .04
            track.append([x, y])
            if not 0 <= x <= 100:
                break
            index = round(x)
            separation = math.dist((x, y), (TANK_X[enemy], ground[TANK_X[enemy]] - 2))
            if (i > 2 and y >= ground[index]) or separation < 3:
                hit += round(damage * max(0, 1 - separation / blast))
                impacts.append([round(x, 1), round(y, 1)])
                for j in range(max(0, index - 6), min(101, index + 7)):
                    if all(abs(j - tx) > 2 for tx in TANK_X):
                        ground[j] = round(min(56., ground[j] + max(0, 4 - abs(j - x) * .6)), 2)
                break
        tracks.append(track)
    frames = [rounded([[j, *track[i]] for j, track in enumerate(tracks) if i < len(track)])
              for i in range(max(map(len, tracks)))]
    return frames, ground, impacts, hit


def soccer_world():
    # Simulation bodies: x, y, vx, vy, heading.
    return [[20., 30., 0., 0., 0.], [80., 30., 0., 0., math.pi], [50., 30., 0., 0., 0.]]


def soccer_step(world, controls, dt):
    for i, (steer, throttle, boost, brake) in enumerate(controls):
        car = world[i]
        car[4] = (car[4] + steer * dt * 3.8) % math.tau
        accel = 102 if boost and throttle > 0 else 64
        damping = math.exp(-dt * (9 if brake else 2.1))
        car[2] = (car[2] + math.cos(car[4]) * throttle * accel * dt) * damping
        car[3] = (car[3] + math.sin(car[4]) * throttle * accel * dt) * damping
    ball = world[2]
    for i, body in enumerate(world):
        body[0] += body[2] * dt
        body[1] += body[3] * dt
        if i == 2:
            body[2] *= .990
            body[3] *= .990
            if 22 < body[1] < 38 and (body[0] <= 0 or body[0] >= 100):
                return 1 if body[0] <= 0 else 0
        margin = 2.3 if i == 2 else 3.3
        outside_goal = i != 2 or not 22 < body[1] < 38
        if outside_goal and not margin <= body[0] <= 100 - margin:
            body[0], body[2] = clamp(body[0], margin, 100 - margin), -body[2] * .72
        if not margin <= body[1] <= 60 - margin:
            body[1], body[3] = clamp(body[1], margin, 60 - margin), -body[3] * .72
    for a, b in ((0, 1), (0, 2), (1, 2)):
        p, q = world[a], world[b]
        x, y = q[0] - p[0], q[1] - p[1]
        dist = math.hypot(x, y)
        limit = 6.4 if b == 1 else 5.8
        if .001 < dist < limit:
            nx, ny = x / dist, y / dist
            overlap = (limit - dist) / 2
            p[0] -= nx * overlap
            p[1] -= ny * overlap
            q[0] += nx * overlap
            q[1] += ny * overlap
            impulse = max(0., (p[2] - q[2]) * nx + (p[3] - q[3]) * ny)
            p[2] -= nx * impulse * (.70 if b == 1 else .32)
            p[3] -= ny * impulse * (.70 if b == 1 else .32)
            q[2] += nx * impulse * (1.13 if b == 2 else .70)
            q[3] += ny * impulse * (1.13 if b == 2 else .70)
    speed = math.hypot(ball[2], ball[3])
    if speed > 75:
        ball[2] *= 75 / speed
        ball[3] *= 75 / speed
    return None
