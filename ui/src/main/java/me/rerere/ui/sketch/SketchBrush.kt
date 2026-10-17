package me.rerere.ui.sketch

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushBehavior
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushTip
import androidx.ink.brush.InputToolType
import androidx.ink.brush.SelfOverlap
import androidx.ink.brush.StockBrushes
import androidx.ink.brush.behavior.BinaryOpNode
import androidx.ink.brush.behavior.DampingNode
import androidx.ink.brush.behavior.EasingFunction
import androidx.ink.brush.behavior.ProgressDomain
import androidx.ink.brush.behavior.ResponseNode
import androidx.ink.brush.behavior.SourceNode
import androidx.ink.brush.behavior.SourceNode.Source
import androidx.ink.brush.behavior.TargetNode
import androidx.ink.brush.behavior.TargetNode.Target
import androidx.ink.brush.behavior.ToolTypeFilterNode
import androidx.ink.brush.behavior.ValueNode
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.DashedLine01
import me.rerere.hugeicons.stroke.Highlighter
import me.rerere.hugeicons.stroke.PaintBrush01
import me.rerere.hugeicons.stroke.Pen01
import me.rerere.hugeicons.stroke.PenTool03
import me.rerere.hugeicons.stroke.QuillWrite01
import me.rerere.ui.R

private const val BRUSH_EPSILON = 0.1f

/**
 *
 */
internal enum class SketchBrush(
    @param:StringRes val label: Int,
    val icon: ImageVector,
    private val widthScale: Float = 1f,
    private val alpha: Float = 1f,
) {
    Pen(R.string.sketch_dialog_brush_pen, HugeIcons.QuillWrite01),

    Marker(R.string.sketch_dialog_brush_marker, HugeIcons.Pen01),

    Flat(R.string.sketch_dialog_brush_flat, HugeIcons.PenTool03, widthScale = 2.5f),

    Calligraphy(R.string.sketch_dialog_brush_calligraphy, HugeIcons.PaintBrush01, widthScale = 2.5f),

    Highlighter(R.string.sketch_dialog_brush_highlighter, HugeIcons.Highlighter, widthScale = 4f, alpha = 0.4f),

    Dashed(R.string.sketch_dialog_brush_dashed, HugeIcons.DashedLine01);

    private val family: BrushFamily
        get() = when (this) {
            Pen -> StockBrushes.pressurePen()
            Marker -> StockBrushes.marker()
            Flat -> FlatFamily
            Calligraphy -> CalligraphyFamily
            Highlighter -> StockBrushes.highlighter(SelfOverlap.DISCARD)
            Dashed -> StockBrushes.dashedLine()
        }

    fun create(color: Color, width: Float): Brush = Brush.createWithColorIntArgb(
        family = family,
        colorIntArgb = color.copy(alpha = alpha).toArgb(),
        size = width * widthScale,
        epsilon = BRUSH_EPSILON,
    )
}

private fun size(from: Float, to: Float, input: ValueNode) =
    BrushBehavior(TargetNode(Target.SIZE_MULTIPLIER, from, to, input))

private val FlatFamily: BrushFamily by lazy {
    BrushFamily(tip = BrushTip(scaleX = 0.2f, scaleY = 1f, cornerRounding = 0.2f, rotationDegrees = 45f))
}

private val CalligraphyFamily: BrushFamily by lazy {
    fun smooth(seconds: Float, input: ValueNode) = DampingNode(ProgressDomain.TIME_IN_SECONDS, seconds, input)

    fun eased(input: ValueNode) = ResponseNode(EasingFunction.Predefined.EASE_OUT, input)

    BrushFamily(
        tip = BrushTip(
            behaviors = listOf(
                size(0.6f, 1f, eased(SourceNode(Source.DISTANCE_TRAVELED_IN_MULTIPLES_OF_BRUSH_SIZE, 0f, 1f))),
                size(
                    from = 0.12f,
                    to = 1f,
                    input = eased(
                        BinaryOpNode(
                            BinaryOpNode.BinaryOp.MAX,
                            SourceNode(Source.DISTANCE_REMAINING_IN_MULTIPLES_OF_BRUSH_SIZE, 0f, 2.5f),
                            SourceNode(Source.DISTANCE_TRAVELED_IN_MULTIPLES_OF_BRUSH_SIZE, 2f, 0f),
                        ),
                    ),
                ),
                size(
                    from = 0.3f,
                    to = 1.3f,
                    input = smooth(
                        seconds = 0.03f,
                        input = ToolTypeFilterNode(
                            setOf(InputToolType.STYLUS),
                            SourceNode(Source.NORMALIZED_PRESSURE, 0f, 1f),
                        ),
                    ),
                ),
                size(
                    from = 1.2f,
                    to = 0.45f,
                    input = smooth(
                        seconds = 0.08f,
                        input = ToolTypeFilterNode(
                            setOf(InputToolType.TOUCH, InputToolType.MOUSE, InputToolType.UNKNOWN),
                            SourceNode(Source.SPEED_IN_CENTIMETERS_PER_SECOND, 3f, 30f),
                        ),
                    ),
                ),
            ),
        ),
    )
}
