package me.rerere.ui.sketch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import androidx.core.graphics.withTranslation
import androidx.ink.authoring.compose.InProgressStrokes
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import kotlin.math.roundToInt

/**
 *
 */
@Composable
internal fun SketchCanvas(state: SketchState, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.onSizeChanged {
            if (it.width > 0 && it.height > 0) state.fit(it.toSize())
        },
        contentAlignment = Alignment.Center,
    ) {
        val paper = state.paperSize
        if (paper.isSpecified) {
            var canvasSize by remember { mutableStateOf(IntSize.Zero) }
            Box(
                modifier = Modifier
                    .aspectRatio(paper.width / paper.height)
                    .onSizeChanged { canvasSize = it }
                    .clip(MaterialTheme.shapes.medium)
                    .systemGestureExclusion()
                    .pointerInput(state, paper) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            state.pickingColor = false
                            if (state.tool == SketchTool.Brush) return@awaitEachGesture
                            down.consume()
                            val scale = size.width / paper.width
                            val center = Offset(size.width / 2f, size.height / 2f)
                            if (state.tool == SketchTool.Text) {
                                val up = waitForUpOrCancellation(PointerEventPass.Initial) ?: return@awaitEachGesture
                                up.consume()
                                state.beginText((up.position - center) / scale, state.width.toPx() / scale)
                                return@awaitEachGesture
                            }
                            if (state.tool == SketchTool.Select) {
                                selectGesture(state, down, scale) { (it - center) / scale }
                                return@awaitEachGesture
                            }
                            val line = state.erase((down.position - center) / scale, state.width.toPx() / scale)
                            try {
                                while (true) {
                                    val change = awaitPointerEvent(PointerEventPass.Initial)
                                        .changes.firstOrNull { it.id == down.id }
                                    if (change == null || !change.pressed) break
                                    change.historical.forEach { state.extend(line, (it.position - center) / scale) }
                                    state.extend(line, (change.position - center) / scale)
                                    change.consume()
                                }
                            } finally {
                                state.finish(line)
                            }
                        }
                    },
            ) {
                val renderer = remember { CanvasStrokeRenderer.create() }
                val selectionColor = MaterialTheme.colorScheme.primary
                Canvas(modifier = Modifier.fillMaxSize()) {
                    state.revision
                    drawSketch(state, size.width / paper.width, renderer)
                    drawSelection(state, size.width / paper.width, selectionColor)
                }
                if (canvasSize != IntSize.Zero) {
                    val scale = canvasSize.width / paper.width
                    val density = LocalDensity.current
                    val brush = remember(state.brush, state.color, state.width, scale, density) {
                        state.brush.create(state.color, with(density) { state.width.toPx() } / scale)
                    }
                    val toPaper = remember(canvasSize, scale) {
                        Matrix().apply {
                            scale(1 / scale, 1 / scale)
                            translate(-canvasSize.width / 2f, -canvasSize.height / 2f)
                        }
                    }
                    val currentBrush by rememberUpdatedState(if (state.tool == SketchTool.Brush) brush else null)
                    InProgressStrokes(
                        defaultBrush = currentBrush,
                        nextBrush = { currentBrush },
                        pointerEventToWorldTransform = toPaper,
                        onStrokesFinished = state::draw,
                    )
                }
            }
        }
    }
}

private val LayerPaint = Paint()

internal fun DrawScope.drawSketch(state: SketchState, scale: Float, renderer: CanvasStrokeRenderer) {
    drawRect(SketchDefaults.PaperColor)
    state.background?.let {
        drawImage(image = it, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
    }
    val steps = state.steps
    val first = steps.indexOfLast { it is SketchStep.Clear } + 1
    val placements = state.placements()
    val strokeTransform = android.graphics.Matrix()
    drawIntoCanvas { it.saveLayer(Rect(Offset.Zero, size), LayerPaint) }
    translate(size.width / 2, size.height / 2) {
        scale(scale, pivot = Offset.Zero) {
            for (index in first until steps.size) {
                when (val step = steps[index]) {
                    is SketchStep.Item -> {
                        val placement = placements[step] ?: continue
                        translate(placement.offset.x, placement.offset.y) {
                            scale(placement.scale, pivot = Offset.Zero) {
                                drawIntoCanvas {
                                    when (step) {
                                        is SketchStep.Ink -> {
                                            strokeTransform.setScale(scale * placement.scale, scale * placement.scale)
                                            renderer.draw(it.nativeCanvas, step.stroke, strokeTransform)
                                        }

                                        is SketchStep.Text -> it.nativeCanvas.withTranslation(
                                            step.position.x,
                                            step.position.y,
                                        ) { step.layout.draw(this) }
                                    }
                                }
                            }
                        }
                    }

                    is SketchStep.Erase -> if (step.moved) {
                        drawPath(
                            path = step.path,
                            color = Color.Black,
                            style = Stroke(width = step.width, cap = StrokeCap.Round, join = StrokeJoin.Round),
                            blendMode = BlendMode.Clear,
                        )
                    } else {
                        drawCircle(
                            color = Color.Black,
                            radius = step.width / 2,
                            center = step.start,
                            blendMode = BlendMode.Clear,
                        )
                    }

                    is SketchStep.Transform, SketchStep.Clear -> {}
                }
            }
        }
    }
    drawIntoCanvas { it.restore() }
}
