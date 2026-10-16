package me.rerere.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.GeneratingProvider
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.LinkMap
import org.intellij.markdown.parser.MarkdownParser
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Tag

//

private val flavour = GFMFlavourDescriptor(useSafeLinks = true, makeHttpsAutoLinks = true)
private val markdownParser = MarkdownParser(flavour)

/**
 *
 */
fun parseMarkdown(content: String, softBreakAsLineBreak: Boolean = true): MarkdownDocument {
    val text = normalizeLatexDelimiters(content)
    val tree = markdownParser.buildMarkdownTreeFromString(text)
    val providers = flavour.createHtmlGeneratingProviders(LinkMap.buildLinkMap(tree, text), null) +
        (MarkdownTokenTypes.HTML_TAG to InlineHtmlTagProvider)
    val html = HtmlGenerator(text, tree, providers, false).generateHtml(tagRenderer)
    val body = Jsoup.parse(html).body()
    return MarkdownDocument(
        source = content,
        blocks = DomConverter(text, softBreakAsLineBreak).flow(body.childNodes(), markdown = false),
    )
}


private val LATEX_DELIMITER_REGEX =
    Regex("""(```[\s\S]*?(?:```|\z)|`[^`\n]*`)|\\\((.+?)\\\)|\\\[([\s\S]+?)\\]""")
private val LATEX_LINE_BREAK_REGEX = Regex("""[ \t]*\r?\n[ \t]*""")

internal fun normalizeLatexDelimiters(content: String): String {
    if ('\\' !in content) return content
    return LATEX_DELIMITER_REGEX.replace(content) { match ->
        val inline = match.groups[2]?.value?.trim()
        when {
            match.groups[1] != null -> match.value
            inline != null -> if (inline.isEmpty()) match.value else "$$inline$"
            else -> "$$" + match.groupValues[3].trim().replace(LATEX_LINE_BREAK_REGEX, " ") + "$$"
        }
    }
}


private const val MARKDOWN_ATTR = "data-md"
private val MARKDOWN_TEXT_TAGS = setOf("p", "li", "th", "td", "h1", "h2", "h3", "h4", "h5", "h6")

private val tagRenderer = HtmlGenerator.DefaultTagRenderer(
    customizer = { node, tagName, attributes ->
        val tag = tagName.toString()
        when {
            tag == "code" && node.type == MarkdownElementTypes.CODE_FENCE -> {
                val closed = node.children.any { it.type == MarkdownTokenTypes.CODE_FENCE_END }
                attributes + "data-closed=\"$closed\""
            }

            tag == "table" -> attributes + "data-src=\"${node.startOffset},${node.endOffset}\""
            tag in MARKDOWN_TEXT_TAGS -> attributes + MARKDOWN_ATTR
            else -> attributes
        }
    },
    includeSrcPositions = false,
)

private val HTML_TAG_NAME_REGEX = Regex("""^</?([a-zA-Z][a-zA-Z0-9-]*)""")

/**
 */
private object InlineHtmlTagProvider : GeneratingProvider {
    override fun processNode(visitor: HtmlGenerator.HtmlGeneratingVisitor, text: String, node: ASTNode) {
        val tag = node.getTextInNode(text)
        val name = HTML_TAG_NAME_REGEX.find(tag)?.groupValues?.get(1)
        if (name != null && !Tag.isKnownTag(name.lowercase())) {
            visitor.consumeHtml(tag.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
        } else {
            visitor.consumeHtml(tag)
        }
    }
}


private val BLOCK_TAGS = setOf(
    "p", "div", "section", "article", "aside", "header", "footer", "main", "nav", "figure", "figcaption",
    "center", "address", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li", "dl", "dt", "dd", "pre",
    "blockquote", "table", "hr", "details", "summary", "form", "fieldset",
)

private val SKIPPED_TAGS = setOf(
    "script", "style", "head", "title", "meta", "link", "template", "noscript", "svg", "math", "iframe",
    "object", "embed", "canvas", "video", "audio", "input", "select", "textarea", "button",
)

private val CODE_TAGS = setOf("code", "kbd", "samp", "tt")

private val BOLD = SpanStyle(fontWeight = FontWeight.Bold)
private val ITALIC = SpanStyle(fontStyle = FontStyle.Italic)
private val STRIKETHROUGH = SpanStyle(textDecoration = TextDecoration.LineThrough)
private val UNDERLINE = SpanStyle(textDecoration = TextDecoration.Underline)

private val TAG_STYLES = mapOf(
    "b" to BOLD,
    "strong" to BOLD,
    "i" to ITALIC,
    "em" to ITALIC,
    "cite" to ITALIC,
    "var" to ITALIC,
    "dfn" to ITALIC,
    "del" to STRIKETHROUGH,
    "s" to STRIKETHROUGH,
    "strike" to STRIKETHROUGH,
    "u" to UNDERLINE,
    "ins" to UNDERLINE,
    "sub" to SpanStyle(fontSize = 0.75.em, baselineShift = BaselineShift.Subscript),
    "sup" to SpanStyle(fontSize = 0.75.em, baselineShift = BaselineShift.Superscript),
    "small" to SpanStyle(fontSize = 0.85.em),
    "big" to SpanStyle(fontSize = 1.2.em),
    "mark" to SpanStyle(background = Color(0x66FFEB3B)),
)

private val HTML_WHITESPACE_REGEX = Regex("""\s+""")
private val SOFT_BREAK_REGEX = Regex("""[ \t]*\r?\n[ \t]*""")

private class DomConverter(
    private val source: String,
    private val softBreakAsLineBreak: Boolean,
) {
    /**
     *
     */
    fun flow(nodes: List<Node>, markdown: Boolean): List<MarkdownBlock> {
        val blocks = mutableListOf<MarkdownBlock>()
        val run = mutableListOf<Node>()

        fun flush() {
            if (run.isEmpty()) return
            val content = inlines(run, markdown).trimmed()
            if (content.isNotEmpty()) blocks += MarkdownBlock.Paragraph(content)
            run.clear()
        }

        for (node in nodes) {
            if (node is Element && node.normalName() in BLOCK_TAGS) {
                flush()
                block(node, blocks)
            } else {
                run += node
            }
        }
        flush()
        return blocks
    }

    private fun block(element: Element, out: MutableList<MarkdownBlock>) {
        val markdown = element.hasAttr(MARKDOWN_ATTR)
        when (val tag = element.normalName()) {
            "p" -> {
                val content = inlines(element.childNodes(), markdown).trimmed()
                if (content.isNotEmpty()) out += MarkdownBlock.Paragraph(content, element.blockStyle())
            }

            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                val content = inlines(element.childNodes(), markdown).trimmed()
                if (content.isNotEmpty()) {
                    out += MarkdownBlock.Heading(tag[1].digitToInt(), content, element.blockStyle())
                }
            }

            "ul", "ol" -> out += list(element, ordered = tag == "ol")
            "pre" -> out += codeBlock(element)
            "table" -> table(element)?.let { out += it }
            "hr" -> out += MarkdownBlock.Divider
            "details" -> out += details(element)

            "blockquote" -> {
                val children = flow(element.childNodes(), markdown = false)
                if (children.isNotEmpty()) out += MarkdownBlock.Quote(children)
            }

            else -> {
                val children = flow(element.childNodes(), markdown)
                val style = element.blockStyle()
                when {
                    children.isEmpty() -> Unit
                    style == null -> out += children
                    else -> out += MarkdownBlock.Container(children, style)
                }
            }
        }
    }

    private fun list(element: Element, ordered: Boolean): MarkdownBlock.ListBlock {
        val items = element.children().filter { it.normalName() == "li" }
        return MarkdownBlock.ListBlock(
            ordered = ordered,
            items = items.map { item ->
                val checkbox = if (item.hasClass("task-list-item")) item.selectFirst("input[type=checkbox]") else null
                ListItem(
                    children = flow(item.childNodes(), item.hasAttr(MARKDOWN_ATTR)),
                    checked = checkbox?.hasAttr("checked"),
                )
            },
            start = element.attr("start").toIntOrNull() ?: 1,
            tight = items.none { item -> item.children().any { it.normalName() == "p" } },
        )
    }

    private fun codeBlock(element: Element): MarkdownBlock.CodeBlock {
        val code = element.selectFirst("code")
        return MarkdownBlock.CodeBlock(
            code = (code ?: element).wholeText().trimEnd('\n'),
            language = code?.classNames()
                ?.firstOrNull { it.startsWith("language-") }
                ?.removePrefix("language-")
                ?.takeIf { it.isNotBlank() },
            closed = code?.attr("data-closed") != "false",
        )
    }

    private fun table(element: Element): MarkdownBlock.Table? {
        val rows = element.select("tr").filter { it.closest("table") === element }
        if (rows.isEmpty()) return null

        val tableStyle = parseBlockStyle(element.attr("style"))
        fun cells(row: Element): List<TableCell> {
            val rowStyle = tableStyle.mergeWith(row.blockStyle())
            return row.children().filter { it.normalName() == "th" || it.normalName() == "td" }.map { cell ->
                TableCell(
                    content = inlines(cell.childNodes(), cell.hasAttr(MARKDOWN_ATTR)).trimmed(),
                    style = rowStyle.mergeWith(cell.blockStyle()),
                )
            }
        }

        val header = cells(rows.first())
        val sourceRange = element.attr("data-src").split(',').mapNotNull { it.toIntOrNull() }
        return MarkdownBlock.Table(
            header = header,
            rows = rows.drop(1).map(::cells),
            alignments = header.map { cell -> cell.style?.textAlign?.takeIf { it != TextAlign.Unspecified } },
            source = sourceRange.takeIf { it.size == 2 && it[0] in 0..it[1] && it[1] <= source.length }
                ?.let { (start, end) -> source.substring(start, end).trim() },
        )
    }

    private fun details(element: Element): MarkdownBlock.Details {
        val summary = element.children().firstOrNull { it.normalName() == "summary" }
        return MarkdownBlock.Details(
            summary = summary?.let { inlines(it.childNodes(), markdown = false).trimmed() }.orEmpty(),
            children = flow(element.childNodes().filter { it !== summary }, markdown = false),
            open = element.hasAttr("open"),
        )
    }

    private fun inlines(nodes: List<Node>, markdown: Boolean): List<MarkdownInline> {
        val out = mutableListOf<MarkdownInline>()
        nodes.forEach { inline(it, markdown, out) }
        return out
    }

    private fun inline(node: Node, markdown: Boolean, out: MutableList<MarkdownInline>) {
        when (node) {
            is TextNode -> out.appendText(normalizeText(node.wholeText, markdown))
            is Element -> inlineElement(node, markdown, out)
        }
    }

    private fun inlineElement(element: Element, markdown: Boolean, out: MutableList<MarkdownInline>) {
        val tag = element.normalName()
        when {
            tag in SKIPPED_TAGS -> Unit
            tag == "br" -> out.appendText("\n")
            tag in CODE_TAGS -> out += MarkdownInline.Code(element.wholeText())

            tag == "img" -> element.attr("src").takeIf { it.isNotBlank() }?.let { src ->
                out += MarkdownInline.Image(
                    url = src,
                    alt = element.attr("alt").takeIf { it.isNotBlank() },
                    title = element.attr("title").takeIf { it.isNotBlank() },
                    width = element.attr("width").toIntOrNull(),
                    height = element.attr("height").toIntOrNull(),
                )
            }

            tag == "span" && element.hasClass("math") -> out += MarkdownInline.Math(
                formula = element.wholeText().trim(),
                display = element.attr("inline") != "true",
            )

            tag == "a" && element.attr("href").isNotBlank() -> {
                val children = inlines(element.childNodes(), markdown)
                if (children.isNotEmpty()) {
                    out += MarkdownInline.Link(
                        destination = element.attr("href"),
                        children = children,
                        title = element.attr("title").takeIf { it.isNotBlank() },
                    )
                }
            }

            else -> {
                val isBlock = tag in BLOCK_TAGS
                if (isBlock && out.isNotEmpty()) out.appendText("\n")
                val children = inlines(element.childNodes(), if (isBlock) element.hasAttr(MARKDOWN_ATTR) else markdown)
                val style = element.inlineStyle()
                when {
                    children.isEmpty() -> Unit
                    style == null -> children.forEach { if (it is MarkdownInline.Text) out.appendText(it.text) else out += it }
                    else -> out += MarkdownInline.Styled(style, children)
                }
            }
        }
    }

    private fun normalizeText(text: String, markdown: Boolean): String = when {
        !markdown -> text.replace(HTML_WHITESPACE_REGEX, " ")
        '\n' !in text -> text
        else -> text.replace(SOFT_BREAK_REGEX, if (softBreakAsLineBreak) "\n" else " ")
    }
}

private fun Element.blockStyle(): TextStyle? = parseBlockStyle(
    css = attr("style"),
    align = attr("align").ifBlank { if (normalName() == "center") "center" else null },
)

private fun TextStyle?.mergeWith(other: TextStyle?): TextStyle? = when {
    this == null -> other
    other == null -> this
    else -> merge(other)
}

private fun Element.inlineStyle(): SpanStyle? {
    val tag = normalName()
    val tagStyle = when {
        tag == "font" -> parseFontTag(color = attr("color"), size = attr("size"), face = attr("face"))
        tag == "span" && hasClass("user-del") -> STRIKETHROUGH
        else -> TAG_STYLES[tag]
    }
    val cssStyle = parseSpanStyle(attr("style"))
    return when {
        tagStyle == null -> cssStyle
        cssStyle == null -> tagStyle
        else -> tagStyle.merge(cssStyle)
    }
}

private fun MutableList<MarkdownInline>.appendText(text: String) {
    val last = lastOrNull() as? MarkdownInline.Text
    val appended = if (last != null && last.text.endsWith('\n')) text.trimStart() else text
    if (appended.isEmpty()) return
    if (last != null) {
        this[lastIndex] = MarkdownInline.Text(last.text + appended)
    } else {
        this += MarkdownInline.Text(appended)
    }
}

private fun List<MarkdownInline>.trimmed(): List<MarkdownInline> {
    if (isEmpty()) return this
    val result = toMutableList()
    (result.first() as? MarkdownInline.Text)?.let { first ->
        val text = first.text.trimStart()
        if (text.isEmpty()) result.removeAt(0) else result[0] = MarkdownInline.Text(text)
    }
    (result.lastOrNull() as? MarkdownInline.Text)?.let { last ->
        val text = last.text.trimEnd()
        if (text.isEmpty()) result.removeAt(result.lastIndex) else result[result.lastIndex] = MarkdownInline.Text(text)
    }
    return result
}
