package me.rerere.ui.sketch

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.unit.dp

private val HandleReach = 24.dp

private const val MIN_SCALE = 0.1f
private const val MAX_SCALE = 10f

internal data class SketchPlacement(val scale: Float = 1f, val offset: Offset = Offset.Zero) {
    fun then(next: SketchPlacement) = SketchPlacement(scale * next.scale, offset * next.scale + next.offset)

    fun apply(point: Offset) = point * scale + offset

    fun apply(rect: Rect) = Rect(apply(rect.topLeft), apply(rect.bottomRight))

    fun invert(rect: Rect) = Rect((rect.topLeft - offset) / scale, (rect.bottomRight - offset) / scale)

    companion object {
        val None = SketchPlacement()

        fun move(by: Offset) = SketchPlacement(offset = by)

        fun scale(by: Float, around: Offset) = SketchPlacement(by, around * (1 - by))
    }
}

private val Rect.corners: List<Offset> get() = listOf(topLeft, topRight, bottomRight, bottomLeft)

private suspend fun AwaitPointerEventScope.drag(
    down: PointerInputChange,
    toPaper: (Offset) -> Offset,
    onMove: (Offset) -> Unit,
) {
    while (true) {
        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
        if (change == null) break
        change.consume()
        if (!change.pressed) break
        onMove(toPaper(change.position))
    }
}

/**
 *
 */
internal suspend fun AwaitPointerEventScope.selectGesture(
    state: SketchState,
    down: PointerInputChange,
    scale: Float,
    toPaper: (Offset) -> Offset,
) {
    val start = toPaper(down.position)
    val reach = HandleReach.toPx() / scale
    val bounds = state.selectionBounds
    val corner = bounds?.corners
        ?.filter { (it - start).getDistance() <= reach }
        ?.minByOrNull { (it - start).getDistance() }
    try {
        when {
            bounds != null && corner != null -> {
                val pivot = bounds.center * 2f - corner
                val from = (start - pivot).getDistance()
                drag(down, toPaper) { point ->
                    if (from > 0f) {
                        val by = ((point - pivot).getDistance() / from).coerceIn(MIN_SCALE, MAX_SCALE)
                        state.moving = SketchPlacement.scale(by, pivot)
                    }
                }
            }

            bounds != null && bounds.contains(start) -> drag(down, toPaper) {
                state.moving = SketchPlacement.move(it - start)
            }

            else -> {
                state.deselect()
                drag(down, toPaper) { point ->
                    if (state.marquee != null || (point - start).getDistance() * scale > viewConfiguration.touchSlop) {
                        state.marquee = Rect(
                            minOf(start.x, point.x),
                            minOf(start.y, point.y),
                            maxOf(start.x, point.x),
                            maxOf(start.y, point.y),
                        )
                    }
                }
                val marquee = state.marquee
                state.select(marquee ?: Rect(center = start, radius = reach / 2), topmost = marquee == null)
            }
        }
    } finally {
        state.marquee = null
        state.settle()
    }
}

internal fun DrawScope.drawSelection(state: SketchState, scale: Float, color: Color) {
    val marquee = state.marquee
    val bounds = state.selectionBounds
    if (marquee == null && bounds == null) return
    translate(size.width / 2, size.height / 2) {
        scale(scale, pivot = Offset.Zero) {
            val outline = Stroke(
                width = 1.5.dp.toPx() / scale,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx() / scale, 4.dp.toPx() / scale)),
            )
            if (marquee != null) {
                drawRect(color.copy(alpha = 0.12f), marquee.topLeft, marquee.size)
                drawRect(color, marquee.topLeft, marquee.size, style = outline)
            }
            if (bounds != null) {
                drawRect(color, bounds.topLeft, bounds.size, style = outline)
                val radius = 5.dp.toPx() / scale
                bounds.corners.forEach {
                    drawCircle(Color.White, radius, it)
                    drawCircle(color, radius, it, style = Stroke(2.dp.toPx() / scale))
                }
            }
        }
    }
}
