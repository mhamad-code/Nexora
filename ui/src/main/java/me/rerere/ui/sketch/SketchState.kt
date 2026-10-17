package me.rerere.ui.sketch

import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.ink.geometry.ImmutableBox
import androidx.ink.geometry.ImmutableVec
import androidx.ink.strokes.Stroke
import kotlin.math.ceil
import kotlin.math.min

internal object SketchDefaults {
    val PaperColor = Color.White

    val BrushColors = listOf(
        Color(0xFF000000),
        Color(0xFFE53935),
        Color(0xFFFB8C00),
        Color(0xFF43A047),
        Color(0xFF1E88E5),
        Color(0xFF8E24AA),
    )

    val HueColors = List(7) { Color.hsv(it * 60f, 1f, 1f) }

    val CustomColor = Color(0xFFE91E63)

    val BrushWidths = listOf(3.dp, 6.dp, 12.dp)

    val AspectRatios = listOf(1 to 1, 4 to 3, 3 to 4, 16 to 9, 9 to 16)
}

private const val ERASER_WIDTH_SCALE = 4f

private const val TEXT_SIZE_SCALE = 4f

internal enum class SketchTool { Brush, Eraser, Text, Select }

internal sealed interface SketchStep {
    sealed interface Item : SketchStep {
        val bounds: Rect

        fun touches(area: Rect): Boolean
    }

    class Ink(val stroke: Stroke) : Item {
        override val bounds: Rect =
            stroke.shape.computeBoundingBox()?.let { Rect(it.xMin, it.yMin, it.xMax, it.yMax) } ?: Rect.Zero

        override fun touches(area: Rect): Boolean = bounds.overlaps(area) &&
            stroke.shape.computeCoverageIsGreaterThan(
                ImmutableBox.fromTwoPoints(ImmutableVec(area.left, area.top), ImmutableVec(area.right, area.bottom)),
                0f,
            )
    }

    class Transform(val items: List<Item>, val placement: SketchPlacement) : SketchStep

    class Erase(val start: Offset, val width: Float) : SketchStep {
        val path = Path().apply { moveTo(start.x, start.y) }
        private var last = start

        var moved = false
            private set

        fun extendTo(point: Offset) {
            val middle = (last + point) / 2f
            path.quadraticTo(last.x, last.y, middle.x, middle.y)
            last = point
            moved = true
        }

        fun finish() {
            if (moved) path.lineTo(last.x, last.y)
        }
    }

    /**
     *
     */
    class Text(val text: String, start: Offset, color: Color, size: Float, maxWidth: Float) : Item {
        val layout: StaticLayout = run {
            val flags = TextPaint.ANTI_ALIAS_FLAG or TextPaint.SUBPIXEL_TEXT_FLAG or TextPaint.LINEAR_TEXT_FLAG
            val paint = TextPaint(flags).apply {
                textSize = size
                this.color = color.toArgb()
            }
            val width = ceil(min(maxWidth, Layout.getDesiredWidth(text, paint))).toInt().coerceAtLeast(1)
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width).build()
        }

        val position = Offset(start.x, start.y - layout.getLineBottom(0) / 2f)

        override val bounds = Rect(position, Size(layout.width.toFloat(), layout.height.toFloat()))

        override fun touches(area: Rect): Boolean = bounds.overlaps(area)
    }

    data object Clear : SketchStep
}

@Stable
internal class SketchState(aspectRatio: Float? = null) {
    private var available = Size.Unspecified

    var paperSize by mutableStateOf(Size.Unspecified)
        private set

    var aspectRatio by mutableStateOf(aspectRatio)
        private set

    var background by mutableStateOf<ImageBitmap?>(null)
        private set

    val steps = mutableStateListOf<SketchStep>()
    private val undone = mutableStateListOf<SketchStep>()

    var color by mutableStateOf(SketchDefaults.BrushColors.first())
        private set

    var customColor by mutableStateOf<Color?>(null)
        private set

    var usingCustomColor by mutableStateOf(false)
        private set

    var pickingColor by mutableStateOf(false)

    var brush by mutableStateOf(SketchBrush.Pen)
        private set

    var width by mutableStateOf(SketchDefaults.BrushWidths[1])

    var tool by mutableStateOf(SketchTool.Brush)
        private set
    val erasing: Boolean get() = tool == SketchTool.Eraser

    var writingAt by mutableStateOf<Offset?>(null)
        private set
    private var writingSize = 0f

    var selection by mutableStateOf<List<SketchStep.Item>>(emptyList())
        private set

    var moving by mutableStateOf(SketchPlacement.None)

    var marquee by mutableStateOf<Rect?>(null)

    var revision by mutableIntStateOf(0)
        private set

    val isBlank: Boolean get() = steps.isEmpty() || steps.last() is SketchStep.Clear

    private val hasSteps: Boolean get() = steps.isNotEmpty() || undone.isNotEmpty()
    val canUndo: Boolean get() = steps.isNotEmpty()
    val canRedo: Boolean get() = undone.isNotEmpty()

    fun usePreset(color: Color) {
        this.color = color
        usingCustomColor = false
        if (erasing) tool = SketchTool.Brush
        pickingColor = false
    }

    fun useBrush(brush: SketchBrush) {
        this.brush = brush
        use(SketchTool.Brush)
    }

    fun useEraser(enabled: Boolean) = use(if (enabled) SketchTool.Eraser else SketchTool.Brush)

    fun useText(enabled: Boolean) = use(if (enabled) SketchTool.Text else SketchTool.Brush)

    fun useSelect(enabled: Boolean) = use(if (enabled) SketchTool.Select else SketchTool.Brush)

    private fun use(tool: SketchTool) {
        this.tool = tool
        pickingColor = false
        deselect()
    }

    fun useCustom(color: Color) {
        customColor = color
        this.color = color
        usingCustomColor = true
        if (erasing) tool = SketchTool.Brush
    }

    fun fit(available: Size) {
        this.available = available
        if (!hasSteps) layout()
    }

    fun useAspectRatio(aspectRatio: Float?) {
        this.aspectRatio = aspectRatio
        layout()
    }

    fun useBackground(image: ImageBitmap?) {
        background = image
        layout()
    }

    private fun layout() {
        if (available.isUnspecified) return
        val ratio = background?.let { it.width.toFloat() / it.height }
            ?: aspectRatio
            ?: (available.width / available.height)
        val scale = if (hasSteps && paperSize.isSpecified) {
            available.fit(paperSize.width / paperSize.height).width / paperSize.width
        } else {
            1f
        }
        paperSize = available.fit(ratio) / scale
    }

    fun draw(strokes: List<Stroke>) {
        strokes.forEach { steps += SketchStep.Ink(it) }
        undone.clear()
    }

    fun beginText(point: Offset, width: Float) {
        writingAt = point
        writingSize = width * TEXT_SIZE_SCALE
    }

    fun write(text: String) {
        val start = writingAt ?: return
        writingAt = null
        if (text.isBlank()) return
        steps += SketchStep.Text(
            text = text.trim(),
            start = start,
            color = color,
            size = writingSize,
            maxWidth = (paperSize.width / 2 - start.x).coerceAtLeast(writingSize),
        )
        undone.clear()
    }

    fun cancelText() {
        writingAt = null
    }

    fun erase(point: Offset, width: Float): SketchStep.Erase {
        val line = SketchStep.Erase(point, width * ERASER_WIDTH_SCALE)
        steps += line
        undone.clear()
        return line
    }

    fun extend(line: SketchStep.Erase, point: Offset) {
        line.extendTo(point)
        revision++
    }

    fun finish(line: SketchStep.Erase) {
        line.finish()
        revision++
    }

    fun placements(): Map<SketchStep.Item, SketchPlacement> {
        val placements = LinkedHashMap<SketchStep.Item, SketchPlacement>()
        for (index in steps.indexOfLast { it is SketchStep.Clear } + 1 until steps.size) {
            when (val step = steps[index]) {
                is SketchStep.Item -> placements[step] = SketchPlacement.None
                is SketchStep.Transform -> step.items.forEach { item ->
                    placements[item]?.let { placements[item] = it.then(step.placement) }
                }

                else -> {}
            }
        }
        if (moving != SketchPlacement.None) {
            selection.forEach { item -> placements[item]?.let { placements[item] = it.then(moving) } }
        }
        return placements
    }

    val selectionBounds: Rect?
        get() {
            if (selection.isEmpty()) return null
            val placements = placements()
            return selection
                .mapNotNull { item -> placements[item]?.apply(item.bounds) }
                .reduceOrNull { all, next ->
                    Rect(
                        minOf(all.left, next.left),
                        minOf(all.top, next.top),
                        maxOf(all.right, next.right),
                        maxOf(all.bottom, next.bottom),
                    )
                }
        }

    fun select(area: Rect, topmost: Boolean) {
        val touched = placements().filter { (item, placement) -> item.touches(placement.invert(area)) }.keys.toList()
        selection = if (topmost) listOfNotNull(touched.lastOrNull()) else touched
    }

    fun deselect() {
        selection = emptyList()
        moving = SketchPlacement.None
        marquee = null
    }

    fun settle() {
        val placement = moving
        moving = SketchPlacement.None
        if (placement == SketchPlacement.None || selection.isEmpty()) return
        steps += SketchStep.Transform(selection, placement)
        undone.clear()
    }

    fun clear() {
        if (isBlank) return
        deselect()
        steps += SketchStep.Clear
        undone.clear()
    }

    fun undo() {
        deselect()
        steps.removeLastOrNull()?.let { undone += it }
    }

    fun redo() {
        deselect()
        undone.removeLastOrNull()?.let { steps += it }
    }
}

internal fun Size.fit(aspectRatio: Float): Size =
    if (width / height > aspectRatio) Size(height * aspectRatio, height) else Size(width, width / aspectRatio)
