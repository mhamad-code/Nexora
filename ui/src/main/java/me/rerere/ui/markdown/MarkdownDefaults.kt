package me.rerere.ui.markdown

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Image02

@Immutable
data class MarkdownTheme(
    val headings: List<TextStyle>,
    val link: SpanStyle,
    val inlineCode: SpanStyle,
    val blockSpacing: Dp,
    val markerColor: Color,
    val quoteColor: Color,
    val dividerColor: Color,
    val quotedText: SpanStyle? = null,
)

@Immutable
class MarkdownInlineWidget(
    val placeholder: Placeholder,
    val content: @Composable () -> Unit,
)

@Immutable
class MarkdownInlineScope(
    val style: TextStyle,
    val density: Density,
    val contentColor: Color,
)

/**
 *
 */
@Immutable
class MarkdownComponents(
    val codeBlock: @Composable (block: MarkdownBlock.CodeBlock) -> Unit = { MarkdownDefaults.CodeBlock(it) },
    val image: @Composable (image: MarkdownInline.Image, link: String?) -> Unit = { image, link ->
        MarkdownDefaults.Image(image, link)
    },
    val mathBlock: @Composable (formula: String) -> Unit = { MarkdownDefaults.MathBlock(it) },
    val table: @Composable (table: MarkdownBlock.Table) -> Unit = { MarkdownDefaults.Table(it) },
    /**
     */
    val inlineMath: (MarkdownInlineScope.(formula: String) -> List<MarkdownInlineWidget>)? = null,
    val inlineLink: (MarkdownInlineScope.(link: MarkdownInline.Link) -> MarkdownInlineWidget?)? = null,
)

object MarkdownDefaults {
    val components = MarkdownComponents()

    private val HeadingScales = floatArrayOf(1.5f, 1.375f, 1.25f, 1.125f, 1f, 0.875f)

    @Composable
    fun theme(textStyle: TextStyle = LocalTextStyle.current): MarkdownTheme {
        val fontSize = LocalTextStyle.current.merge(textStyle).fontSize
        val colorScheme = MaterialTheme.colorScheme
        return remember(fontSize, colorScheme) {
            MarkdownTheme(
                headings = HeadingScales.map { scale ->
                    if (fontSize.isSp) {
                        TextStyle(
                            fontWeight = FontWeight.Bold,
                            fontSize = fontSize * scale,
                            lineHeight = fontSize * (scale * 1.25f),
                        )
                    } else {
                        TextStyle(fontWeight = FontWeight.Bold)
                    }
                },
                link = SpanStyle(color = colorScheme.primary, textDecoration = TextDecoration.Underline),
                inlineCode = SpanStyle(
                    color = colorScheme.primary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 0.9.em,
                ),
                blockSpacing = 12.dp,
                markerColor = colorScheme.primary,
                quoteColor = colorScheme.outlineVariant,
                dividerColor = colorScheme.outlineVariant,
            )
        }
    }

    @Composable
    fun CodeBlock(block: MarkdownBlock.CodeBlock, modifier: Modifier = Modifier) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                block.language?.let { language ->
                    Text(
                        text = language,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = block.code,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    softWrap = false,
                )
            }
        }
    }

    @Composable
    fun Image(image: MarkdownInline.Image, link: String?, modifier: Modifier = Modifier) {
        val uriHandler = LocalUriHandler.current
        Surface(
            onClick = { runCatching { uriHandler.openUri(link ?: image.url) } },
            modifier = modifier,
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = HugeIcons.Image02,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = image.alt ?: image.url,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    /**
     */
    @Composable
    fun Table(
        table: MarkdownBlock.Table,
        modifier: Modifier = Modifier,
        outerBorder: BorderStroke? = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape: Shape = MaterialTheme.shapes.small,
    ) {
        MarkdownTable(table, modifier, outerBorder, shape)
    }

    @Composable
    fun MathBlock(formula: String, modifier: Modifier = Modifier) {
        Box(
            modifier = modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = formula,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

internal fun MarkdownInlineWidget.toInlineTextContent() = InlineTextContent(placeholder) { content() }
