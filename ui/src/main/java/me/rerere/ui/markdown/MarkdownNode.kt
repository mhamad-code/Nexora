package me.rerere.ui.markdown

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign

/**
 *
 */
@Immutable
data class MarkdownDocument(
    val source: String,
    val blocks: List<MarkdownBlock>,
)

@Immutable
sealed interface MarkdownBlock {
    @Immutable
    data class Paragraph(
        val content: List<MarkdownInline>,
        val style: TextStyle? = null,
    ) : MarkdownBlock

    @Immutable
    data class Heading(
        val level: Int,
        val content: List<MarkdownInline>,
        val style: TextStyle? = null,
    ) : MarkdownBlock

    @Immutable
    data class CodeBlock(
        val code: String,
        val language: String? = null,
        val closed: Boolean = true,
    ) : MarkdownBlock

    @Immutable
    data class Quote(val children: List<MarkdownBlock>) : MarkdownBlock

    @Immutable
    data class ListBlock(
        val ordered: Boolean,
        val items: List<ListItem>,
        val start: Int = 1,
        val tight: Boolean = true,
    ) : MarkdownBlock

    /**
     */
    @Immutable
    data class Table(
        val header: List<TableCell>,
        val rows: List<List<TableCell>>,
        val alignments: List<TextAlign?> = emptyList(),
        val source: String? = null,
    ) : MarkdownBlock

    @Immutable
    data object Divider : MarkdownBlock

    @Immutable
    data class Details(
        val summary: List<MarkdownInline>,
        val children: List<MarkdownBlock>,
        val open: Boolean = false,
    ) : MarkdownBlock

    @Immutable
    data class Container(
        val children: List<MarkdownBlock>,
        val style: TextStyle,
    ) : MarkdownBlock
}

@Immutable
data class ListItem(
    val children: List<MarkdownBlock>,
    val checked: Boolean? = null,
)

@Immutable
data class TableCell(
    val content: List<MarkdownInline>,
    val style: TextStyle? = null,
)

@Immutable
sealed interface MarkdownInline {
    @Immutable
    data class Text(val text: String) : MarkdownInline

    @Immutable
    data class Styled(
        val style: SpanStyle,
        val children: List<MarkdownInline>,
    ) : MarkdownInline

    @Immutable
    data class Code(val code: String) : MarkdownInline

    @Immutable
    data class Link(
        val destination: String,
        val children: List<MarkdownInline>,
        val title: String? = null,
    ) : MarkdownInline

    @Immutable
    data class Image(
        val url: String,
        val alt: String? = null,
        val title: String? = null,
        val width: Int? = null,
        val height: Int? = null,
    ) : MarkdownInline

    @Immutable
    data class Math(
        val formula: String,
        val display: Boolean = false,
    ) : MarkdownInline
}

fun List<MarkdownInline>.plainText(): String = buildString { appendPlainText(this@plainText) }

private fun StringBuilder.appendPlainText(nodes: List<MarkdownInline>) {
    for (node in nodes) {
        when (node) {
            is MarkdownInline.Text -> append(node.text)
            is MarkdownInline.Styled -> appendPlainText(node.children)
            is MarkdownInline.Code -> append(node.code)
            is MarkdownInline.Link -> appendPlainText(node.children)
            is MarkdownInline.Image -> append(node.alt.orEmpty())
            is MarkdownInline.Math -> append(node.formula)
        }
    }
}

/**
 *
 */
internal sealed interface InlineSegment {
    data class Text(val content: List<MarkdownInline>) : InlineSegment
    data class Image(val image: MarkdownInline.Image, val link: String?) : InlineSegment
    data class Math(val formula: String) : InlineSegment
}

internal fun List<MarkdownInline>.toSegments(): List<InlineSegment> {
    if (none { it.hasEmbedded() }) return listOf(InlineSegment.Text(this))

    val segments = mutableListOf<InlineSegment>()
    val run = mutableListOf<MarkdownInline>()

    fun flush() {
        if (run.any { it !is MarkdownInline.Text || it.text.isNotBlank() }) {
            segments += InlineSegment.Text(run.toList())
        }
        run.clear()
    }

    fun visit(node: MarkdownInline, link: String?, wrap: (MarkdownInline) -> MarkdownInline) {
        when {
            node is MarkdownInline.Image -> {
                flush()
                segments += InlineSegment.Image(node, link)
            }

            node is MarkdownInline.Math && node.display -> {
                flush()
                segments += InlineSegment.Math(node.formula)
            }

            node is MarkdownInline.Styled && node.hasEmbedded() -> node.children.forEach { child ->
                visit(child, link) { wrap(node.copy(children = listOf(it))) }
            }

            node is MarkdownInline.Link && node.hasEmbedded() -> node.children.forEach { child ->
                visit(child, node.destination) { wrap(node.copy(children = listOf(it))) }
            }

            else -> run += wrap(node)
        }
    }

    forEach { node -> visit(node, null) { it } }
    flush()
    return segments
}

private fun MarkdownInline.hasEmbedded(): Boolean = when (this) {
    is MarkdownInline.Image -> true
    is MarkdownInline.Math -> display
    is MarkdownInline.Styled -> children.any { it.hasEmbedded() }
    is MarkdownInline.Link -> children.any { it.hasEmbedded() }
    else -> false
}
