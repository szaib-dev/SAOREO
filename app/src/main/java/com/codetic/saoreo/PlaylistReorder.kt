package com.codetic.saoreo

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlin.math.abs

internal class PlaylistReorder(private val list: LazyListState) {
    var draft by mutableStateOf<List<String>?>(null)
        private set
    var dragging by mutableStateOf<String?>(null)
        private set
    private var liftedTop by mutableFloatStateOf(0f)
    private var liftedHeight = 0
    private var initialOrder: List<String> = emptyList()

    fun start(id: String, saved: List<String>) {
        if (dragging != null) return
        val row = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id } ?: return
        initialOrder = draft ?: saved
        draft = initialOrder
        liftedTop = row.offset.toFloat()
        liftedHeight = row.size
        dragging = id
    }

    fun drag(amount: Float) {
        if (dragging == null) return
        liftedTop += amount
        shift()
    }

    fun shift() {
        val id = dragging ?: return
        val order = draft ?: return
        val center = liftedTop + liftedHeight / 2f
        val target = list.layoutInfo.visibleItemsInfo
            .filter { (it.key as? String ?: "") in order }
            .minByOrNull { abs(center - (it.offset + it.size / 2f)) }
        if (target?.key == id || target == null) return
        val from = order.indexOf(id)
        val to = order.indexOf(target.key as? String ?: return)
        if (from < 0 || to < 0) return
        val targetCenter = target.offset + target.size / 2f
        if (to > from && center < targetCenter || to < from && center > targetCenter) return
        draft = order.toMutableList().apply { add(to, removeAt(from)) }
    }

    fun scrollAmount(edge: Float): Float {
        if (dragging == null) return 0f
        val layout = list.layoutInfo
        val low = layout.viewportStartOffset + edge
        val high = layout.viewportEndOffset - edge
        val center = liftedTop + liftedHeight / 2f
        return when {
            center < low && list.canScrollBackward -> -((low - center) / 5f).coerceIn(2f, 24f)
            center > high && list.canScrollForward -> ((center - high) / 5f).coerceIn(2f, 24f)
            else -> 0f
        }
    }

    fun finish(): List<String>? {
        if (dragging == null) return null
        dragging = null
        return draft?.takeIf { it != initialOrder }
    }

    fun cancel() {
        if (dragging == null) return
        dragging = null
        draft = initialOrder
    }

    fun acceptSaved(saved: List<String>) {
        if (dragging == null && draft == saved) draft = null
    }

    fun discard(order: List<String>) {
        if (dragging == null && draft == order) draft = null
    }

    fun move(id: String, delta: Int, saved: List<String>): List<String>? {
        if (dragging != null) return null
        val order = draft ?: saved
        val index = order.indexOf(id)
        val next = index + delta
        if (index < 0 || next !in order.indices) return null
        return order.toMutableList().apply { add(next, removeAt(index)) }.also { draft = it }
    }

    fun modifier(id: String): Modifier = if (dragging == id) Modifier.zIndex(1f).graphicsLayer {
        val rowOffset = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }?.offset
        if (rowOffset != null) translationY = liftedTop - rowOffset
    } else Modifier
}

@Composable
internal fun PlaylistDragHandle(state: PlaylistReorder, id: String, saved: List<String>, onSave: (List<String>) -> Unit) {
    val latestOrder by rememberUpdatedState(saved)
    val save by rememberUpdatedState(onSave)
    androidx.compose.material3.Icon(
        painterResource(R.drawable.ic_playlist_drag), "Drag to reorder",
        Modifier.size(44.dp).pointerInput(state, id) {
            detectVerticalDragGestures(onDragStart = { state.start(id, latestOrder) },
                onDragEnd = { state.finish()?.let(save) }, onDragCancel = state::cancel) { change, amount ->
                if (state.dragging == id) {
                    change.consume()
                    state.drag(amount)
                }
            }
        }.padding(11.dp),
    )
}

@Composable
internal fun PlaylistDragScroll(list: LazyListState, state: PlaylistReorder, edge: Float) {
    LaunchedEffect(state.dragging, edge) {
        while (state.dragging != null) {
            val delta = state.scrollAmount(edge)
            if (delta != 0f && list.scrollBy(delta) != 0f) state.shift()
            delay(16)
        }
    }
}
