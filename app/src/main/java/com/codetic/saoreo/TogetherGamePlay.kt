package com.codetic.saoreo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.delay
import kotlin.math.*

private data class GameObject(val id: Int, val x: Float, val y: Float, val vx: Float = 0f, val vy: Float = 0f, val angle: Float = 0f) {
    val point get() = Offset(x, y)
}

private fun JSONArray?.gameObjects(indexed: Boolean = true): List<GameObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { i ->
        val item = optJSONArray(i) ?: return@mapNotNull null
        val at = if (indexed) 1 else 0
        GameObject(if (indexed) item.optInt(0) else i, item.optDouble(at, 0.0).toFloat(), item.optDouble(at + 1, 0.0).toFloat(),
            item.optDouble(at + 2, 0.0).toFloat(), item.optDouble(at + 3, 0.0).toFloat(), item.optDouble(at + 4, 0.0).toFloat())
    }
}

private fun JSONArray?.point(fallback: Offset): Offset = if (this == null) fallback else
    Offset(optDouble(0, fallback.x.toDouble()).toFloat(), optDouble(1, fallback.y.toDouble()).toFloat())

private data class GameAim(val point: Offset, val power: Float, val spin: Float = 0f, val weapon: Int = 0) {
    fun json() = JSONObject().put("x", point.x.toDouble()).put("y", point.y.toDouble())
        .put("power", power.toDouble()).put("spin", spin.toDouble()).put("weapon", weapon)
}
private data class Drive(val steer: Float = 0f, val throttle: Float = 0f, val boost: Boolean = false, val brake: Boolean = false) {
    fun json() = JSONObject().put("x", steer.toDouble()).put("y", throttle.toDouble()).put("boost", boost).put("brake", brake)
}

@Composable
internal fun GamePlay(
    game: TogetherGame, state: JSONObject, remoteAim: JSONObject?, side: Int, ready: Boolean,
    send: (String, JSONObject) -> Boolean, now: () -> Long, modifier: Modifier,
    toggleHud: () -> Unit,
) {
    val match = state.optString("id")
    val turnNumber = state.optInt("turnNumber")
    val turn = state.optInt("turn")
    val myTurn = ready && side == turn
    val board = state.optJSONObject("board") ?: remember { JSONObject() }
    val needsCue = game == TogetherGame.POOL && board.optBoolean("ballInHand")
    val sideSign = if (turn == 1) -1f else 1f
    val default = when (game) {
        TogetherGame.ARCHERY -> Offset(72f, 29f)
        TogetherGame.TANK -> Offset(45f, 0f)
        TogetherGame.POOL, TogetherGame.GOLF -> Offset(1f, 0f)
        else -> Offset.Zero
    }
    var aimPoint by remember(match, turnNumber) { mutableStateOf(default) }
    var power by remember(match, turnNumber) { mutableFloatStateOf(.62f) }
    var spin by remember(match, turnNumber) { mutableFloatStateOf(0f) }
    var weapon by remember(match, turnNumber) { mutableIntStateOf(0) }
    var submitted by remember(match, turnNumber, state.optString("phase")) { mutableStateOf(false) }
    var drive by remember(match) { mutableStateOf(Drive()) }
    val emit by rememberUpdatedState(send)
    val latestReady by rememberUpdatedState(ready && side in 0..1)
    val localAim by rememberUpdatedState(GameAim(aimPoint, power, spin, weapon))
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(match, game, lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP && game.realTime) {
                drive = Drive()
                emit("input", Drive().json())
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (game.realTime) emit("input", Drive().json())
        }
    }
    LaunchedEffect(match, turnNumber, myTurn, ready, game) {
        if (game.realTime && latestReady) {
            while (true) {
                emit("input", drive.json())
                delay(115)
            }
        } else if (myTurn && !needsCue) {
            var previous: GameAim? = null
            while (true) {
                if (localAim != previous && !submitted) {
                    previous = localAim
                    emit("aim", localAim.json())
                }
                delay(135)
            }
        }
    }
    LaunchedEffect(submitted) { if (submitted) { delay(1200); submitted = false } }

    val pools = remember(board) { board.optJSONArray("balls").gameObjects() }
    val golfers = remember(board) { board.optJSONArray("balls").gameObjects(false) }
    val receiver = remoteAim ?: state.optJSONObject("aim")
    val watchAim = receiver?.let {
        GameAim(Offset(it.optDouble("x", 0.0).toFloat(), it.optDouble("y", 0.0).toFloat()),
            it.optDouble("power", .62).toFloat(), it.optDouble("spin", 0.0).toFloat(), it.optInt("weapon"))
    }
    val angle = if (side == turn && state.optString("phase") == "active") localAim else watchAim
    val replay = state.optJSONObject("replay")
    val frames = remember(replay?.optLong("at")) {
        val raw = replay?.optJSONArray("frames")
        if (raw == null) emptyList() else List(raw.length()) { raw.optJSONArray(it).gameObjects() }
    }
    var animationTime by remember { mutableLongStateOf(now()) }
    val bodies = remember(board, game) { if (game.realTime) board.optJSONArray("bodies").gameObjects(false) else emptyList() }
    var fromBodies by remember(match) { mutableStateOf(bodies) }
    var toBodies by remember(match) { mutableStateOf(bodies) }
    var arrived by remember(match) { mutableLongStateOf(now()) }
    LaunchedEffect(bodies) {
        if (bodies.isNotEmpty()) {
            val oldTarget = toBodies
            fromBodies = if (board.optLong("kickoff") > now()) bodies else blend(fromBodies, oldTarget,
                ((now() - arrived) / 105f).coerceIn(0f, 1f))
            toBodies = bodies
            arrived = now()
        }
    }
    LaunchedEffect(replay?.optLong("at"), game, ready) {
        if (replay != null || game.realTime && ready) {
            while (true) {
                withFrameNanos { animationTime = now() }
                if (!game.realTime && animationTime > replay!!.optLong("at") + frames.size * 40 + 350) break
            }
        } else animationTime = now()
    }
    val step = replay?.optInt("step", 40)?.coerceAtLeast(1) ?: 40
    val progress = ((animationTime - (replay?.optLong("at") ?: animationTime)) / step.toFloat()).coerceAtLeast(0f)
    val frame = if (frames.isEmpty()) emptyList() else {
        val index = progress.toInt().coerceAtMost(frames.lastIndex)
        blend(frames[index], frames[(index + 1).coerceAtMost(frames.lastIndex)], (progress - index).coerceIn(0f, 1f))
    }
    val cars = if (game.realTime) {
        val mix = ((animationTime - arrived) / 100f).coerceIn(0f, 1f)
        val blended = blend(fromBodies, toBodies, mix)
        if (ready && animationTime >= board.optLong("kickoff")) blended.map { body ->
            if (body.id != side) body else {
                val prediction = ((animationTime - arrived) / 1000f).coerceIn(0f, .11f)
                val heading = body.angle + drive.steer * 3.8f * prediction
                val thrust = drive.throttle * (if (drive.boost) 102f else 64f)
                body.copy(x = (body.x + (body.vx + cos(heading) * thrust * prediction) * prediction * .55f).coerceIn(3.3f, 96.7f),
                    y = (body.y + (body.vy + sin(heading) * thrust * prediction) * prediction * .55f).coerceIn(3.3f, 56.7f), angle = heading)
            }
        } else blended
    } else emptyList()

    fun targeted(point: Offset, w: Float, h: Float): Offset {
        val location = Offset((point.x / w * 100).coerceIn(0f, 100f), (point.y / h * 60).coerceIn(0f, 60f))
        return when (game) {
            TogetherGame.POOL, TogetherGame.GOLF -> {
                val origin = if (game == TogetherGame.POOL) pools.firstOrNull { it.id == 0 }?.point else golfers.getOrNull(turn)?.point
                val delta = location - (origin ?: Offset(25f, 30f))
                val length = delta.getDistance()
                if (length >= .3f) delta / length else aimPoint
            }
            TogetherGame.ARCHERY -> location
            TogetherGame.TANK -> Offset((atan2((46f - location.y).coerceAtLeast(1f),
                ((location.x - if (turn == 0) 14f else 86f) * sideSign).coerceAtLeast(1f)) * 180 / PI).toFloat().coerceIn(5f, 85f), 0f)
            else -> location
        }
    }
    val updatedTarget by rememberUpdatedState(::targeted)
    val controls = if (!game.realTime && myTurn && !submitted) Modifier.pointerInput(match, turnNumber, needsCue) {
        detectTapGestures { point ->
            if (needsCue) {
                val x = (point.x / size.width * 100).coerceIn(3f, 97f)
                val y = (point.y / size.height * 60).coerceIn(8f, 52f)
                emit("place", JSONObject().put("x", x.toDouble()).put("y", y.toDouble()))
            } else aimPoint = updatedTarget(point, size.width.toFloat(), size.height.toFloat())
        }
    }.pointerInput(match, turnNumber, needsCue) {
        if (!needsCue) detectDragGestures { change, _ ->
            change.consume()
            aimPoint = updatedTarget(change.position, size.width.toFloat(), size.height.toFloat())
        }
    } else Modifier
    Column(modifier, verticalArrangement = Arrangement.SpaceBetween) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val width = minOf(maxWidth, maxHeight * (100f / 60f))
            Box(Modifier.fillMaxSize().clickable(onClickLabel = "Show or hide game interface", onClick = toggleHud))
            Canvas(Modifier.width(width).aspectRatio(100f / 60f).clip(RoundedCornerShape(15.dp))
                .then(controls).semantics { contentDescription = "${game.title} playing field" }) {
                scene(game, board, pools, golfers, cars, angle, turn, frame, replay?.optInt("side") ?: turn)
            }
        }
        val status = state.optString("notice")
        if (status.isNotBlank()) Text(status, Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 2.dp),
            color = GameGold, style = MaterialTheme.typography.labelMedium)
        if (game.realTime) {
            DriveControls(drive, ready && side >= 0) { drive = it }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(when {
                        needsCue && myTurn -> "Tap the felt to place the cue ball"
                        !myTurn -> if (state.optString("phase") == "resolving") "Shot in play" else "Watching partner"
                        else -> "Power  ${(power * 100).roundToInt()}%"
                    }, color = Color.White, style = MaterialTheme.typography.labelMedium)
                    Slider(power, { power = it }, enabled = myTurn && !needsCue, valueRange = .05f..1f,
                        modifier = Modifier.height(37.dp).semantics { contentDescription = "Shot power" })
                }
                Button({
                    if (emit("shoot", localAim.json())) submitted = true
                }, enabled = myTurn && !needsCue && !submitted) {
                    Text(when (game) {
                        TogetherGame.POOL -> "Shoot"
                        TogetherGame.GOLF -> "Putt"
                        TogetherGame.ARCHERY -> "Release"
                        else -> "Fire"
                    })
                }
            }
            if (game == TogetherGame.POOL && myTurn && !needsCue) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("Spin", color = Color.White, style = MaterialTheme.typography.labelMedium)
                    Slider(spin, { spin = it }, Modifier.weight(1f).height(32.dp).semantics { contentDescription = "Cue spin" }, valueRange = -1f..1f)
                    val group = board.optJSONArray("groups")?.optInt(side) ?: 0
                    Text(when (group) { 1 -> "Solids"; 2 -> "Stripes"; else -> "Open table" },
                        color = GameGold, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (game == TogetherGame.ARCHERY) Text(
                "Round ${board.optInt("round") + 1}  ·  Wind ${board.optDouble("wind", 0.0)}",
                color = GameGold, style = MaterialTheme.typography.labelMedium)
            if (game == TogetherGame.GOLF) Text("Hole ${board.optInt("hole") + 1}  ·  ${board.optJSONObject("course")?.optString("name").orEmpty()}",
                color = GameGold, style = MaterialTheme.typography.labelMedium)
            if (game == TogetherGame.TANK && myTurn) Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                val heavy = board.optJSONArray("heavy")?.optInt(side) ?: 0
                val multi = board.optJSONArray("multi")?.optInt(side) ?: 0
                listOf("Standard", "Heavy $heavy", "Scatter $multi").forEachIndexed { i, label ->
                    FilterChip(weapon == i, { weapon = i }, { Text(label) }, enabled = i == 0 || i == 1 && heavy > 0 || i == 2 && multi > 0)
                }
            }
        }
    }
}

private fun blend(a: List<GameObject>, b: List<GameObject>, t: Float): List<GameObject> {
    if (t <= 0f) return a.ifEmpty { b }
    if (t >= 1f || a.isEmpty()) return b
    return a.mapNotNull { old ->
        val next = b.firstOrNull { it.id == old.id } ?: return@mapNotNull old
        val turn = atan2(sin(next.angle - old.angle), cos(next.angle - old.angle))
        fun mix(x: Float, y: Float) = x + (y - x) * t
        GameObject(old.id, mix(old.x, next.x), mix(old.y, next.y), mix(old.vx, next.vx), mix(old.vy, next.vy), old.angle + turn * t)
    } + b.filter { next -> a.none { it.id == next.id } }
}

@Composable
private fun DriveControls(value: Drive, enabled: Boolean, update: (Drive) -> Unit) {
    val latest = rememberUpdatedState(value)
    val change = rememberUpdatedState(update)
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(92.dp).semantics { contentDescription = "Drive joystick. Up accelerates, down reverses, left and right steer." }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var point = down
                    down.consume()
                    do {
                        val delta = (point.position - Offset(size.width / 2f, size.height / 2f)) / (size.width * .36f)
                        change.value(latest.value.copy(steer = delta.x.coerceIn(-1f, 1f), throttle = (-delta.y).coerceIn(-1f, 1f)))
                        point = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        point.consume()
                    } while (point.pressed)
                    change.value(latest.value.copy(steer = 0f, throttle = 0f))
                }
            }) {
            drawCircle(Color(0xFF303748), size.minDimension * .47f)
            drawCircle(GameGold.copy(alpha = .36f), size.minDimension * .46f, style = Stroke(1.dp.toPx()))
            drawCircle(GameGold, size.minDimension * .19f,
                center + Offset(value.steer, -value.throttle) * (size.minDimension * .24f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            FilterChip(value.brake, { update(value.copy(brake = !value.brake)) }, { Text("Brake") }, enabled = enabled)
            FilterChip(value.boost, { update(value.copy(boost = !value.boost)) }, { Text("Boost") }, enabled = enabled)
        }
    }
}

@Composable
internal fun GameArtwork(game: TogetherGame, modifier: Modifier) {
    Canvas(modifier.semantics { contentDescription = "${game.title} artwork" }) {
        scene(game, JSONObject(), emptyList(), emptyList(), emptyList(), null, 0, emptyList(), 0, true)
    }
}

private fun DrawScope.scene(game: TogetherGame, board: JSONObject, pool: List<GameObject>, golf: List<GameObject>,
                            cars: List<GameObject>, aim: GameAim?, side: Int, moving: List<GameObject>,
                            shootingSide: Int, cover: Boolean = false) {
    scale(size.width / 100f, size.height / 60f, Offset.Zero) {
        val grass = Color(0xFF224B3C)
        val mint = Color(0xFFA3CFAC)
        when (game) {
            TogetherGame.POOL -> {
                drawRect(Color(0xFF192724), size = Size(100f, 60f))
                drawRoundRect(Brush.linearGradient(listOf(Color(0xFFB48656), Color(0xFF483026)), Offset.Zero, Offset(100f, 60f)),
                    Offset(0f, 4f), Size(100f, 52f), CornerRadius(5f))
                drawRoundRect(Brush.linearGradient(listOf(Color(0xFF367860), grass), Offset(0f, 7f), Offset(100f, 55f)),
                    Offset(2f, 7f), Size(96f, 46f), CornerRadius(3f))
                drawRoundRect(mint.copy(alpha = .35f), Offset(3f, 8f), Size(94f, 44f), CornerRadius(2f), style = Stroke(.4f))
                listOf(2.5f, 50f, 97.5f).forEach { x -> listOf(7.5f, 52.5f).forEach { y ->
                    drawCircle(Color(0xFF101416), 2.65f, Offset(x, y))
                } }
                val balls = if (moving.isNotEmpty()) moving else if (cover) listOf(
                    GameObject(0, 25f, 33f), GameObject(1, 57f, 26f), GameObject(8, 63f, 32f),
                    GameObject(10, 69f, 28f), GameObject(3, 55f, 34f), GameObject(13, 72f, 34f),
                ) else pool
                val cue = balls.firstOrNull { it.id == 0 }
                val direction = if (cover) Offset(.93f, -.22f) else aim?.point
                if (cue != null && direction != null && moving.isEmpty()) {
                    dash(cue.point, cue.point + direction * 23.5f, Color.White.copy(alpha = .8f))
                    drawLine(Color(0xFFDEAC68), cue.point - direction * 5f, cue.point - direction * 21f,
                        1.05f, cap = StrokeCap.Round)
                }
                balls.forEach { ball ->
                    val colors = listOf(GameGold, GameBlue, Color(0xFFEC726B), Color(0xFF9E79C8), Color(0xFFEE9B5D),
                        Color(0xFF63B889), Color(0xFF9E5669))
                    val color = when (ball.id) { 0 -> Color.White; 8 -> Color(0xFF242231); else -> colors[(ball.id - 1) % colors.size] }
                    marble(ball.point, 1.75f, if (ball.id > 8) Color(0xFFE4DED3) else color)
                    if (ball.id > 8) drawLine(color, ball.point - Offset(1.35f, 0f), ball.point + Offset(1.35f, 0f), 1.2f)
                    if (ball.id != 0) drawCircle(Color.White.copy(alpha = .72f), .4f, ball.point)
                }
            }
            TogetherGame.GOLF -> {
                drawRect(Brush.verticalGradient(listOf(Color(0xFF34563C), Color(0xFF183E36)), 0f, 60f), size = Size(100f, 60f))
                for (x in 0..100 step 7) drawRect(Color.White.copy(alpha = .032f), Offset(x.toFloat(), 0f), Size(3.5f, 60f))
                val course = board.optJSONObject("course")
                val walls = course?.optJSONArray("walls")
                val count = walls?.length() ?: 2
                repeat(count) { index ->
                    val wall = walls?.optJSONArray(index)
                    val x = (wall?.optDouble(0) ?: if (index == 0) 34.0 else 64.0).toFloat()
                    val y = (wall?.optDouble(1) ?: if (index == 0) 20.0 else 0.0).toFloat()
                    val w = (wall?.optDouble(2)?.toFloat() ?: (x + 5)) - x
                    val h = (wall?.optDouble(3)?.toFloat() ?: if (index == 0) 60f else 32f) - y
                    drawRoundRect(Color(0xFF172D2A), Offset(x + 1, y + 1), Size(w, h), CornerRadius(1f))
                    drawRoundRect(Color(0xFF8A8461), Offset(x, y), Size(w, h), CornerRadius(1f))
                }
                val sand = course?.optJSONArray("sand")
                drawOval(Color(0xFFB8AA72), Offset(sand?.optInt(0)?.toFloat() ?: 67f, sand?.optInt(1)?.toFloat() ?: 38f),
                    Size(if (sand == null) 24f else (sand.optInt(2) - sand.optInt(0)).toFloat(),
                        if (sand == null) 16f else (sand.optInt(3) - sand.optInt(1)).toFloat()))
                val cup = course?.optJSONArray("cup").point(Offset(87f, 15f))
                drawCircle(Color(0xFF0C2522), 2f, cup, style = Stroke(.8f))
                drawCircle(Color(0xFF0C2522), 1.55f, cup)
                drawLine(Color.White, cup, cup - Offset(0f, 9f), .35f)
                drawPath(Path().apply { moveTo(cup.x, cup.y - 9); lineTo(cup.x + 6, cup.y - 7); lineTo(cup.x, cup.y - 5); close() }, GameGold)
                val movingBall = moving.firstOrNull { it.id == 0 }
                val gate = moving.firstOrNull { it.id == 1 }?.point ?: board.optJSONArray("bumper").point(Offset(48f, 25f))
                drawRoundRect(GameBlue.copy(alpha = .85f), gate - Offset(1.5f, 4f), Size(3f, 8f), CornerRadius(1f))
                val here = if (cover) listOf(GameObject(0, 13f, 45f), GameObject(1, 18f, 39f)) else golf
                here.forEach { ball ->
                    val point = if (ball.id == shootingSide && movingBall != null) movingBall.point else ball.point
                    if (aim != null && ball.id == side && movingBall == null) dash(point, point + aim.point * 19f, Color.White)
                    marble(point, 1.55f, if (ball.id == 0) GameGold else GameBlue)
                }
            }
            TogetherGame.ARCHERY -> {
                hills(Color(0xFF203447), Color(0xFFBE9A77), Color(0xFF345A51))
                drawRect(Color(0xFF31564C), Offset(0f, 43f), Size(100f, 17f))
                val target = Offset(72f, 29f)
                val radius = max(8.5f, 16f - board.optInt("round") * 1.3f)
                drawLine(Color(0xFF704F39), target + Offset(0f, 8f), target + Offset(-7f, 27f), 1.4f)
                drawLine(Color(0xFF704F39), target + Offset(0f, 8f), target + Offset(7f, 27f), 1.4f)
                listOf(Color(0xFFEDE5D4), Color(0xFF202633), Color(0xFF639EC4), Color(0xFFCC6866), GameGold)
                    .forEachIndexed { i, tint -> drawCircle(tint, radius * (1 - .2f * i), target) }
                val bow = Offset(13f, 40f)
                drawArc(GameGold, -90f, 180f, false, bow - Offset(4f, 11f), Size(8f, 18f), style = Stroke(.9f))
                val draw = (aim?.power ?: .6f) * 4f
                drawLine(Color.White, bow - Offset(0f, 11f), bow - Offset(draw, 2f), .36f)
                drawLine(Color.White, bow - Offset(draw, 2f), bow + Offset(0f, 7f), .36f)
                aim?.point?.takeIf { moving.isEmpty() }?.let { point ->
                    drawCircle(Color.White, 2.0f, point, style = Stroke(.4f))
                    drawLine(Color.White, point - Offset(3f, 0f), point + Offset(3f, 0f), .25f)
                    drawLine(Color.White, point - Offset(0f, 3f), point + Offset(0f, 3f), .25f)
                }
                moving.firstOrNull()?.point?.let { point ->
                    drawLine(Color.White, point - Offset(5f, 1.1f), point, .6f, cap = StrokeCap.Round)
                    drawCircle(GameGold, .72f, point)
                } ?: board.optJSONArray("hit")?.point(target)?.let { hit ->
                    drawCircle(GameInk, .95f, hit)
                    drawCircle(GameGold, 2.2f, hit, style = Stroke(.33f))
                }
            }
            TogetherGame.TANK -> {
                hills(Color(0xFF252C48), Color(0xFFC58D71), Color(0xFF37445D))
                val terrain = board.optJSONArray("terrain")
                fun height(x: Int): Float = terrain?.optDouble(x, 45.0)?.toFloat()
                    ?: (48 - 4.5 * cos(x * PI / 50) - 12 * exp(-((x - 43) / 16.0).pow(2))).toFloat()
                val path = Path().apply {
                    moveTo(0f, 60f)
                    for (x in 0..100) lineTo(x.toFloat(), height(x))
                    lineTo(100f, 60f)
                    close()
                }
                drawPath(path, Brush.verticalGradient(listOf(Color(0xFF8C886A), Color(0xFF343641)), 25f, 60f))
                listOf(14, 86).forEachIndexed { i, x ->
                    val point = Offset(x.toFloat(), height(x) - 1)
                    val color = if (i == 0) GameGold else GameBlue
                    drawRoundRect(GameInk, point - Offset(4.7f, 1.5f), Size(9.4f, 2.5f), CornerRadius(.9f))
                    drawRoundRect(color, point - Offset(3.7f, 4.4f), Size(7.4f, 3.6f), CornerRadius(1f))
                    val degrees = if (i == side) aim?.point?.x ?: 40f else 35f
                    val rad = (degrees * PI / 180).toFloat()
                    val direction = Offset(cos(rad) * if (i == 0) 1f else -1f, -sin(rad))
                    val from = point - Offset(0f, 4f)
                    drawLine(color, from, from + direction * 7f, 1.2f, cap = StrokeCap.Round)
                    if (i == side && aim != null && moving.isEmpty()) {
                        val speed = (10 + aim.power * 58) * if (aim.weapon == 1) .88f else 1f
                        repeat(7) { j ->
                            val t = (j + 2) * .095f
                            drawCircle(GameGold.copy(alpha = .55f - j * .04f), .35f,
                                from + direction * speed * t + Offset(0f, 11 * t * t))
                        }
                    }
                }
                moving.forEach { projectile -> marble(projectile.point, 1f, GameGold) }
                val impacts = board.optJSONArray("impacts")
                if (moving.isEmpty()) repeat(impacts?.length() ?: 0) {
                    val point = impacts?.optJSONArray(it).point(Offset.Zero)
                    drawCircle(Color(0xFFF58A68).copy(alpha = .45f), 2.6f, point, style = Stroke(.5f))
                }
            }
            TogetherGame.SOCCER -> {
                drawRect(Color(0xFF23483E), size = Size(100f, 60f))
                for (i in 0..9) drawRect(Color.White.copy(alpha = if (i % 2 == 0) .033f else 0f), Offset(i * 10f, 0f), Size(10f, 60f))
                drawRoundRect(mint.copy(alpha = .68f), Offset(2f, 2f), Size(96f, 56f), CornerRadius(3f), style = Stroke(.4f))
                drawLine(mint, Offset(50f, 2f), Offset(50f, 58f), .4f)
                drawCircle(mint, 8f, Offset(50f, 30f), style = Stroke(.4f))
                drawRect(GameGold.copy(alpha = .55f), Offset(0f, 22f), Size(4f, 16f))
                drawRect(GameBlue.copy(alpha = .55f), Offset(96f, 22f), Size(4f, 16f))
                val vehicles = if (cars.isEmpty()) listOf(GameObject(0, 29f, 34f, angle = -.35f),
                    GameObject(1, 76f, 25f, angle = 2.82f), GameObject(2, 51f, 29f)) else cars
                vehicles.forEach { body ->
                    val color = if (body.id == 0) GameGold else GameBlue
                    if (body.id == 2) {
                        marble(body.point, 2.3f, Color(0xFFF3EEE6))
                        drawCircle(Color(0xFF353942), .65f, body.point)
                    } else rotate(body.angle * 180f / PI.toFloat(), body.point) {
                        drawRoundRect(GameInk, body.point - Offset(3.8f, 2.7f), Size(7.8f, 5.6f), CornerRadius(1.2f))
                        drawRoundRect(color, body.point - Offset(3.3f, 2.1f), Size(6.9f, 4.2f), CornerRadius(1.15f))
                        drawRoundRect(Color(0xFF2D414B), body.point - Offset(1.7f, 1.5f), Size(2.9f, 3f), CornerRadius(.65f))
                        drawLine(Color.White, body.point + Offset(2.8f, -1.25f), body.point + Offset(2.8f, 1.25f), .45f)
                    }
                }
            }
        }
    }
}

private fun DrawScope.hills(top: Color, horizon: Color, mountain: Color) {
    drawRect(Brush.verticalGradient(listOf(top, horizon), 0f, 60f), size = Size(100f, 60f))
    drawCircle(GameGold.copy(alpha = .7f), 7f, Offset(83f, 13f))
    drawPath(Path().apply {
        moveTo(0f, 32f); lineTo(15f, 15f); lineTo(25f, 26f); lineTo(43f, 12f)
        lineTo(61f, 29f); lineTo(74f, 18f); lineTo(100f, 32f); lineTo(100f, 60f); lineTo(0f, 60f); close()
    }, mountain)
}

private fun DrawScope.marble(point: Offset, radius: Float, color: Color) {
    drawCircle(Color.Black.copy(alpha = .38f), radius * 1.15f, point + Offset(.28f, .53f))
    drawCircle(Brush.radialGradient(listOf(color, color.copy(red = color.red * .60f, green = color.green * .60f, blue = color.blue * .60f)),
        point - Offset(radius * .28f, radius * .38f), radius * 1.5f), radius, point)
    drawCircle(Color.White.copy(alpha = .53f), radius * .19f, point - Offset(radius * .35f, radius * .44f))
}

private fun DrawScope.dash(from: Offset, to: Offset, color: Color) {
    val delta = (to - from) / 12f
    repeat(12) { i ->
        val start = from + delta * i.toFloat()
        drawLine(color.copy(alpha = color.alpha * (1 - i / 15f)), start, start + delta * .46f, .34f, cap = StrokeCap.Round)
    }
}
