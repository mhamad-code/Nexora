package com.mhamad.nexora.ui.components.richtext

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.BubbleChatQuestion
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.File02
import com.mhamad.nexora.utils.openUrl
import java.net.InetAddress
import java.net.URI

private const val MAX_ITEMS = 8
private const val MAX_COMPONENTS = 6
private const val MAX_TEXT = 1000
private const val MAX_TREE_DEPTH = 3

@Stable
sealed interface NexoraUi {
    val title: String
    data class Form(override val title: String, val questions: List<Question>) : NexoraUi
    data class Options(override val title: String, val items: List<OptionItem>) : NexoraUi
    data class Cards(override val title: String, val items: List<InfoItem>) : NexoraUi
    data class Links(override val title: String, val items: List<LinkItem>) : NexoraUi
    data class Flow(override val title: String, val steps: List<FlowStep>) : NexoraUi
    data class Tree(override val title: String, val items: List<TreeItem>) : NexoraUi
}

data class Question(val id: String, val question: String, val type: String, val options: List<String>)
data class OptionItem(val title: String, val text: String, val badge: String?, val imageQuery: String?, val imageUrl: String?, val url: String?, val value: String?)
data class InfoItem(val title: String, val text: String, val icon: String?, val badge: String?, val url: String?)
data class LinkItem(val title: String, val text: String, val url: String, val icon: String?, val linkLabel: String?)
data class FlowStep(val title: String, val text: String, val icon: String?)
data class TreeItem(val name: String, val kind: String, val text: String?, val children: List<TreeItem>)

/** Action is installed by the chat screen; the parser and views stay independent of VM state. */
val LocalNexoraUiBridge = compositionLocalOf<NexoraUiBridge> { NexoraUiBridge() }
@Stable
data class NexoraUiBridge(val sendText: (String) -> Unit = {}, val canInteract: Boolean = false)

private val parserJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** Parses only the documented schema. Unknown/malformed blocks return null and remain code. */
fun parseNexoraUi(raw: String, maxComponents: Int = MAX_COMPONENTS): List<NexoraUi>? {
    return runCatching {
        val root = parserJson.parseToJsonElement(raw).jsonObject
        val type = root.string("type") ?: return null
        val title = root.string("title")?.limited() ?: return null
        val parsed = when (type) {
            "form" -> NexoraUi.Form(title, root.array("questions")?.take(MAX_ITEMS)?.mapNotNull { q ->
                val o = q.jsonObject
                val id = o.string("id")?.limited(80) ?: return@mapNotNull null
                val question = o.string("question")?.limited() ?: return@mapNotNull null
                val kind = o.string("type")?.limited(30) ?: "text"
                val options = o.array("options")?.mapNotNull { it.asString()?.limited(200) }?.take(MAX_ITEMS).orEmpty()
                Question(id, question, kind, options)
            } ?: return null)
            "options" -> NexoraUi.Options(title, root.array("items")?.take(MAX_ITEMS)?.mapNotNull { o ->
                val x = o.jsonObject; val itemTitle = x.string("title")?.limited() ?: return@mapNotNull null
                OptionItem(itemTitle, x.string("text")?.limited() ?: "", x.string("badge")?.limited(80), x.string("image_query")?.limited(120), x.string("image_url")?.limited(500), x.string("url")?.limited(500)?.takeIf(::isSafeUrl), x.string("value")?.limited(200))
            } ?: return null)
            "cards" -> NexoraUi.Cards(title, root.array("items")?.take(MAX_ITEMS)?.mapNotNull { o ->
                val x=o.jsonObject; InfoItem(x.string("title")?.limited() ?: return@mapNotNull null, x.string("text")?.limited() ?: "", x.string("icon")?.limited(30), x.string("badge")?.limited(80), x.string("url")?.limited(500)?.takeIf(::isSafeUrl))
            } ?: return null)
            "links" -> NexoraUi.Links(title, root.array("items")?.take(MAX_ITEMS)?.mapNotNull { o ->
                val x=o.jsonObject; val url=x.string("url")?.limited(500)?.takeIf(::isSafeUrl) ?: return@mapNotNull null
                LinkItem(x.string("title")?.limited() ?: return@mapNotNull null, x.string("text")?.limited() ?: "", url, x.string("icon")?.limited(30), x.string("link_label")?.limited(80))
            } ?: return null)
            "flow" -> NexoraUi.Flow(title, root.array("steps")?.take(MAX_ITEMS)?.mapNotNull { o ->
                val x=o.jsonObject; FlowStep(x.string("title")?.limited() ?: return@mapNotNull null, x.string("text")?.limited() ?: "", x.string("icon")?.limited(30))
            } ?: return null)
            "tree" -> NexoraUi.Tree(title, root.array("items")?.take(MAX_ITEMS)?.mapNotNull { parseTree(it, 1) } ?: return null)
            else -> return null
        }
        listOf(parsed).take(maxComponents)
    }.getOrNull()
}

private fun parseTree(element: JsonElement, depth: Int): TreeItem? {
    if (depth > MAX_TREE_DEPTH) return null
    val o = element.jsonObject
    val name = o.string("name")?.limited(200) ?: return null
    val kind = o.string("kind")?.takeIf { it == "file" || it == "folder" } ?: return null
    val children = if (kind == "folder") o.array("children")?.take(MAX_ITEMS)?.mapNotNull { parseTree(it, depth + 1) }.orEmpty() else emptyList()
    return TreeItem(name, kind, o.string("text")?.limited(), children)
}

private fun JsonObject.string(key: String) = this[key]?.asString()
private fun JsonObject.array(key: String): JsonArray? = this[key]?.let { runCatching { it.jsonArray }.getOrNull() }
private fun JsonElement.asString(): String? = runCatching { jsonPrimitive.contentOrNull }.getOrNull()
private fun String.limited(max: Int = MAX_TEXT) = take(max)
private fun isSafeUrl(value: String): Boolean = runCatching {
    val uri = URI(value); (uri.scheme == "http" || uri.scheme == "https") && uri.host != null
}.getOrDefault(false)

@Composable
fun NexoraUiView(block: NexoraUi, modifier: Modifier = Modifier) {
    when (block) {
        is NexoraUi.Form -> FormView(block, modifier)
        is NexoraUi.Options -> OptionsView(block, modifier)
        is NexoraUi.Cards -> CardsView(block, modifier)
        is NexoraUi.Links -> LinksView(block, modifier)
        is NexoraUi.Flow -> FlowView(block, modifier)
        is NexoraUi.Tree -> TreeView(block, modifier)
    }
}

@Composable
fun NexoraUiSkeleton(modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("جارٍ تجهيز الرد التفاعلي…", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable private fun UiCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun FormView(block: NexoraUi.Form, modifier: Modifier) {
    val bridge = LocalNexoraUiBridge.current; var sent by remember { mutableStateOf(false) }; val answers = remember { mutableStateMapOf<String, String>() }
    UiCard(block.title, modifier) {
        block.questions.forEach { q ->
            Text(q.question, style = MaterialTheme.typography.bodyMedium)
            if (q.options.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                q.options.forEach { option -> androidx.compose.material3.FilterChip(selected = answers[q.id] == option, onClick = { if (bridge.canInteract && !sent) answers[q.id] = option }, label = { Text(option) }, modifier = Modifier.semantics { role = Role.Button }) }
            } else if (bridge.canInteract && !sent) {
                OutlinedTextField(value = answers[q.id].orEmpty(), onValueChange = { answers[q.id] = it.take(MAX_TEXT) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
        }
        if (bridge.canInteract && !sent) androidx.compose.material3.Button(onClick = { sent = true; bridge.sendText(block.questions.joinToString("\n") { "${it.id}: ${answers[it.id].orEmpty()}" }) }, modifier = Modifier.semantics { contentDescription = "إرسال الإجابات" }) { Text("إرسال") } else Text("تمت الإجابة", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable private fun OptionsView(block: NexoraUi.Options, modifier: Modifier) {
    val bridge=LocalNexoraUiBridge.current; val context = LocalContext.current; var sent by remember { mutableStateOf(false) }
    UiCard(block.title, modifier) { block.items.forEach { item ->
        val image by produceState<String?>(validateModelImageUrl(item.imageUrl), item.imageQuery, item.url) {
            if (value == null) value = resolveNexoraImage(item.imageQuery, item.url)
        }
        Card(Modifier.fillMaxWidth().clickable(enabled = bridge.canInteract && !sent, role = Role.Button) { if (item.url != null && item.title.isBlank()) context.openUrl(item.url) else { sent=true; bridge.sendText(item.value ?: item.title) } }.semantics { contentDescription = item.title }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { if (image != null) ZoomableAsyncImage(image, item.title, Modifier.size(48.dp)) else Icon(HugeIcons.Code, null, Modifier.size(22.dp)); Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text(item.title, style=MaterialTheme.typography.titleSmall); if(item.text.isNotBlank()) Text(item.text, maxLines=3, overflow=TextOverflow.Ellipsis) }; item.badge?.let { AssistChip(onClick={}, label={Text(it)}) } }
        }
    } }
}

@Composable private fun CardsView(block: NexoraUi.Cards, modifier: Modifier) = UiCard(block.title, modifier) { block.items.forEach { item -> InfoRow(item.title, item.text, item.badge) } }
@Composable private fun InfoRow(title: String, text: String, badge: String?) { Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.Top) { Icon(HugeIcons.BubbleChatQuestion, null, Modifier.size(22.dp)); Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text(title, style=MaterialTheme.typography.titleSmall); Text(text, style=MaterialTheme.typography.bodySmall); badge?.let { Text(it, style=MaterialTheme.typography.labelSmall, color=MaterialTheme.colorScheme.secondary) } } } }
@Composable private fun LinksView(block: NexoraUi.Links, modifier: Modifier) = UiCard(block.title, modifier) { val context=LocalContext.current; block.items.forEach { item -> Row(Modifier.fillMaxWidth().clickable { context.openUrl(item.url) }.padding(vertical=8.dp).semantics { contentDescription=item.linkLabel ?: item.title; role=Role.Button }, verticalAlignment=Alignment.CenterVertically) { Icon(HugeIcons.Download04,null,Modifier.size(22.dp)); Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text(item.title, style=MaterialTheme.typography.titleSmall); Text(item.text, style=MaterialTheme.typography.bodySmall) }; Text(item.linkLabel ?: "فتح", color=MaterialTheme.colorScheme.primary) } } }
@Composable private fun FlowView(block: NexoraUi.Flow, modifier: Modifier) = UiCard(block.title, modifier) { block.steps.forEachIndexed { index, step -> InfoRow(step.title, step.text, null); if(index < block.steps.lastIndex) Icon(HugeIcons.ArrowDown01,null,Modifier.size(20.dp)) } }
@Composable private fun TreeView(block: NexoraUi.Tree, modifier: Modifier) = UiCard(block.title, modifier) { TreeItems(block.items, 0) }
@Composable private fun TreeItems(items: List<TreeItem>, depth: Int) { items.forEach { item -> Row(Modifier.padding(start=(depth*16).dp), verticalAlignment=Alignment.Top) { Icon(if(item.kind=="folder") HugeIcons.Download04 else HugeIcons.File02,null,Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Column { Text(item.name, style=MaterialTheme.typography.titleSmall); item.text?.let { Text(it, style=MaterialTheme.typography.bodySmall) }; if(item.children.isNotEmpty()) TreeItems(item.children, depth+1) } } } }

fun validateModelImageUrl(value: String?): String? = value?.takeIf { isSafeUrl(it) && URI(it).host?.endsWith(".local") != true && !isPrivateHost(URI(it).host) }
private fun isPrivateHost(host: String?): Boolean = runCatching { host == "localhost" || InetAddress.getByName(host).isAnyLocalAddress || InetAddress.getByName(host).isLoopbackAddress || InetAddress.getByName(host).isLinkLocalAddress }.getOrDefault(true)

/** Removes fenced Nexora UI JSON before TTS, copy/share, export, or generated summaries consume text. */
fun stripNexoraUiBlocks(text: String): String = text
    .replace(Regex("(?s)```nexora-ui\\s*.*?```", RegexOption.IGNORE_CASE), "")
    .trim()

/** External hosts contacted by the optional resolver: upload.wikimedia.org, en.wikipedia.org, and the app-selected Wikipedia language host. */
fun NexoraUiPreview() { }
