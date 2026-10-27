package com.mhamad.nexora.ui.components.richtext

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.util.Locale

/**
 * Bridge between the rendered block and the chat screen.
 * ChatPage sets [sendText] while it is on screen; the form calls it on submit.
 */
object NexoraUiBridge {
    @Volatile
    var sendText: ((String) -> Unit)? = null
}

internal sealed interface NexoraUi

internal data class NexoraForm(
    val title: String,
    val submit: String?,
    val questions: List<NexoraQuestion>,
) : NexoraUi

internal data class NexoraQuestion(
    val id: String,
    val label: String,
    val kind: String, // single | multi | text
    val options: List<String>,
    val allowText: Boolean,
)

private val lenientJson = Json {
    isLenient = true
    ignoreUnknownKeys = true
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** Returns null when the code is not a valid nexora-ui block, so the caller falls back to a normal code block. */
internal fun parseNexoraUi(code: String): NexoraUi? = runCatching {
    val root = lenientJson.parseToJsonElement(code.trim()) as? JsonObject ?: return@runCatching null
    when (root.str("type")) {
        "form" -> parseForm(root)
        else -> null
    }
}.getOrNull()

private fun parseForm(root: JsonObject): NexoraForm? {
    val questions = (root["questions"] as? JsonArray)?.mapIndexedNotNull { index, element ->
        val obj = element as? JsonObject ?: return@mapIndexedNotNull null
        val label = obj.str("label")?.trim()?.take(240)?.takeIf { it.isNotEmpty() }
            ?: return@mapIndexedNotNull null
        val options = (obj["options"] as? JsonArray)
            ?.mapNotNull { item ->
                (item as? JsonPrimitive)?.contentOrNull?.trim()?.take(160)?.takeIf { it.isNotEmpty() }
            }
            ?.take(8)
            .orEmpty()
        val rawKind = obj.str("kind").orEmpty().trim().lowercase()
        val kind = if (rawKind == "single" || rawKind == "multi" || rawKind == "text") {
            rawKind
        } else if (options.isEmpty()) {
            "text"
        } else {
            "single"
        }
        NexoraQuestion(
            id = "q$index",
            label = label,
            kind = kind,
            options = options,
            allowText = (obj["allow_text"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
    }?.take(6).orEmpty()
    if (questions.isEmpty()) return null
    return NexoraForm(
        title = root.str("title")?.trim()?.take(160).orEmpty(),
        submit = root.str("submit")?.trim()?.take(40)?.takeIf { it.isNotEmpty() },
        questions = questions,
    )
}

private class NexoraFormState {
    val selected = mutableStateMapOf<String, Set<String>>()
    val texts = mutableStateMapOf<String, String>()
    var submitted by mutableStateOf(false)
}

// Keeps answers while the message scrolls in and out of the list (not saved across app restarts).
private val formStates = HashMap<Int, NexoraFormState>()

@Composable
internal fun NexoraUiView(ui: NexoraUi, key: Int, modifier: Modifier = Modifier) {
    when (ui) {
        is NexoraForm -> NexoraFormView(form = ui, key = key, modifier = modifier)
    }
}

@Composable
private fun NexoraFormView(form: NexoraForm, key: Int, modifier: Modifier) {
    val st = remember(key) { formStates.getOrPut(key) { NexoraFormState() } }
    val ar = remember { Locale.getDefault().language == "ar" }
    val separator = if (ar) "، " else ", "

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (form.title.isNotEmpty()) {
                Text(text = form.title, style = MaterialTheme.typography.titleMedium)
            }

            form.questions.forEach { q ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = q.label, style = MaterialTheme.typography.bodyLarge)

                    if (q.kind != "text") {
                        q.options.forEach { opt ->
                            val picked = opt in (st.selected[q.id] ?: emptySet())
                            val toggle: () -> Unit = {
                                if (!st.submitted) {
                                    val current = st.selected[q.id] ?: emptySet()
                                    st.selected[q.id] = if (q.kind == "multi") {
                                        if (opt in current) current - opt else current + opt
                                    } else {
                                        setOf(opt)
                                    }
                                }
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (q.kind == "multi") {
                                            Modifier.toggleable(
                                                value = picked,
                                                enabled = !st.submitted,
                                                role = Role.Checkbox,
                                                onValueChange = { toggle() },
                                            )
                                        } else {
                                            Modifier.selectable(
                                                selected = picked,
                                                enabled = !st.submitted,
                                                role = Role.RadioButton,
                                                onClick = toggle,
                                            )
                                        }
                                    )
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (q.kind == "multi") {
                                    Checkbox(checked = picked, onCheckedChange = null, enabled = !st.submitted)
                                } else {
                                    RadioButton(selected = picked, onClick = null, enabled = !st.submitted)
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = opt, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }

                    if (q.kind == "text" || q.allowText) {
                        OutlinedTextField(
                            value = st.texts[q.id].orEmpty(),
                            onValueChange = { if (!st.submitted) st.texts[q.id] = it },
                            enabled = !st.submitted,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                Text(
                                    if (q.kind == "text") {
                                        if (ar) "اكتب إجابتك" else "Type your answer"
                                    } else {
                                        if (ar) "أخرى" else "Other"
                                    }
                                )
                            },
                            maxLines = 4,
                        )
                    }
                }
            }

            val hasAnswer = form.questions.any { q ->
                !st.selected[q.id].isNullOrEmpty() || !st.texts[q.id].isNullOrBlank()
            }
            Button(
                onClick = {
                    val message = buildNexoraAnswer(form, st, separator)
                    val send = NexoraUiBridge.sendText
                    if (send != null && message.isNotEmpty()) {
                        send(message)
                        st.submitted = true
                    }
                },
                enabled = hasAnswer && !st.submitted,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        st.submitted -> if (ar) "تم الإرسال" else "Sent"
                        form.submit != null -> form.submit
                        else -> if (ar) "إرسال" else "Submit"
                    }
                )
            }
        }
    }
}

private fun buildNexoraAnswer(form: NexoraForm, st: NexoraFormState, separator: String): String {
    val sb = StringBuilder()
    if (form.title.isNotEmpty()) sb.append(form.title).append('\n')
    form.questions.forEach { q ->
        val chosen = st.selected[q.id] ?: emptySet()
        val parts = mutableListOf<String>()
        parts += q.options.filter { it in chosen }
        st.texts[q.id]?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += it }
        if (parts.isNotEmpty()) {
            sb.append("• ").append(q.label).append(": ").append(parts.joinToString(separator)).append('\n')
        }
    }
    return sb.toString().trim()
}
