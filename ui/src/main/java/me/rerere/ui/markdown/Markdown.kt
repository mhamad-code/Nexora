package me.rerere.ui.markdown

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull

/**
 *
 *
 */
@Composable
fun Markdown(
    content: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    theme: MarkdownTheme = MarkdownDefaults.theme(style),
    components: MarkdownComponents = MarkdownDefaults.components,
    softBreakAsLineBreak: Boolean = true,
    onLinkClick: ((url: String) -> Unit)? = null,
) {
    Markdown(
        document = rememberMarkdownDocument(content, softBreakAsLineBreak),
        modifier = modifier,
        style = style,
        theme = theme,
        components = components,
        onLinkClick = onLinkClick,
    )
}

@Composable
fun Markdown(
    document: MarkdownDocument,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    theme: MarkdownTheme = MarkdownDefaults.theme(style),
    components: MarkdownComponents = MarkdownDefaults.components,
    onLinkClick: ((url: String) -> Unit)? = null,
) {
    val currentOnLinkClick by rememberUpdatedState(onLinkClick)
    val handlesLinks = onLinkClick != null
    val linkListener = remember(handlesLinks) {
        if (handlesLinks) {
            LinkInteractionListener { link ->
                if (link is LinkAnnotation.Url) currentOnLinkClick?.invoke(link.url)
            }
        } else {
            null
        }
    }
    val context = remember(theme, components, linkListener) {
        MarkdownContext(theme, components, linkListener)
    }
    CompositionLocalProvider(LocalMarkdownContext provides context) {
        ProvideTextStyle(style) {
            MarkdownBlocks(blocks = document.blocks, modifier = modifier)
        }
    }
}

/**
 *
 */
@Composable
fun rememberMarkdownDocument(content: String, softBreakAsLineBreak: Boolean = true): MarkdownDocument {
    val state = remember(softBreakAsLineBreak) {
        mutableStateOf(parseMarkdown(content, softBreakAsLineBreak))
    }
    val currentContent by rememberUpdatedState(content)
    LaunchedEffect(state) {
        snapshotFlow { currentContent }
            .conflate()
            .filter { it != state.value.source }
            .mapNotNull { runCatching { parseMarkdown(it, softBreakAsLineBreak) }.getOrNull() }
            .flowOn(Dispatchers.Default)
            .collect { state.value = it }
    }
    return state.value
}

@Preview(showBackground = true)
@Composable
private fun MarkdownPreview() {
    MaterialTheme {
        Surface {
            Markdown(
                content = """
                    # Markdown
                    A paragraph with **bold**, *italic*, ~~deleted~~, `code`, a [link](https://example.com)
                    and <span style="color: #e53935">colored <u>HTML</u></span>.

                    1. Ordered item
                       - Nested bullet
                       - [x] Finished task
                       - [ ] Pending task
                    2. Another item

                    > A quote with ${'$'}E = mc^2$ inside.

                    ```kotlin
                    fun main() = println("Hello")
                    ```

                    | Name | Score |
                    |:-----|------:|
                    | Rikka | 100 |

                    <details open>
                    <summary>Details</summary>

                    Hidden *markdown* content.

                    </details>
                """.trimIndent(),
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
