package com.mhamad.nexora.data.model

import com.mhamad.nexora.data.skills.SkillFrontmatterParser
import java.security.MessageDigest

/**
 *
 */
data class MemoryFile(
    val path: String,
    val content: String,
    val updatedAt: Long,
) {
    val version: String by lazy { memoryVersionOf(content) }

    private val frontmatter by lazy { SkillFrontmatterParser.parse(content) }

    val description: String get() = frontmatter["description"]?.trim().orEmpty()

    val aliases: List<String> get() = frontmatter.getList("aliases")

    val body: String get() = SkillFrontmatterParser.extractBody(content)

    val displayBody: String get() = body.replace(statedMarkerRegex, "$1")

    /**
     */
    val editableBody: String get() = if (frontmatter.toMap().isEmpty()) content else body

    val directory: String get() = path.substringBeforeLast('/').ifEmpty { "/" }

    val name: String get() = path.substringAfterLast('/').removeSuffix(".md")

    val title: String get() = frontmatter["title"]?.trim()?.ifEmpty { null } ?: memoryTitleOf(name)

    /**
     *
     */
    fun contentWith(title: String, description: String, aliases: List<String>, body: String): String {
        val values = linkedMapOf<String, Any?>("name" to name)
        title.toSingleLine().takeIf { it.isNotEmpty() }?.let { values["title"] = it }
        description.toSingleLine().takeIf { it.isNotEmpty() }?.let { values["description"] = it }
        aliases.takeIf { it.isNotEmpty() }?.let { values["aliases"] = it }
        frontmatter.toMap().forEach { (key, value) -> if (key !in editableKeys) values[key] = value }
        return SkillFrontmatterParser.compose(values, body)
    }

    companion object {
        const val PROFILE_PATH = "/profile.md"
        const val PREFERENCES_PATH = "/preferences.md"
        const val MAX_BYTES = 48 * 1024
        private const val MAX_PATH_LENGTH = 128

        fun normalizePath(raw: String): String? {
            val path = raw.trim().let { if (it.startsWith("/")) it else "/$it" }
            if (path.length > MAX_PATH_LENGTH || !path.endsWith(".md")) return null
            if (path.any { it.isISOControl() || it == '\\' }) return null
            val segments = path.removePrefix("/").split("/")
            if (segments.any { it.isBlank() || it != it.trim() || it.startsWith(".") }) return null
            return path
        }
    }
}

private val editableKeys = setOf("name", "title", "description", "aliases")

private fun String.toSingleLine(): String = replace(Regex("\\s+"), " ").trim()

private val statedMarkerRegex = Regex("""(?m)^(\s*[-*+]\s+)\[stated\]\s*""", RegexOption.IGNORE_CASE)

private val pinnedPathOrder = listOf(MemoryFile.PROFILE_PATH, MemoryFile.PREFERENCES_PATH)
private val directoryOrder = listOf("/", "/topics", "/areas", "/people")

private fun <T> List<T>.orderOf(item: T) = indexOf(item).let { if (it < 0) size else it }

fun memoryTitleOf(name: String): String =
    name.split('-', '_', ' ')
        .filter { it.isNotEmpty() }
        .joinToString(" ") { word -> word.replaceFirstChar { it.titlecase() } }

fun List<MemoryFile>.groupByDirectory(): List<Pair<String, List<MemoryFile>>> =
    sortedWith(compareBy({ pinnedPathOrder.orderOf(it.path) }, { it.path }))
        .groupBy { it.directory }
        .toList()
        .sortedWith(compareBy({ directoryOrder.orderOf(it.first) }, { it.first }))

private fun memoryVersionOf(content: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(12)
