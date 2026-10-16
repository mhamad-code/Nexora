package me.rerere.mediagen.provider.providers

import me.rerere.mediagen.model.MediaGenerationInput
import java.io.File
import kotlin.io.encoding.Base64

internal fun imageMimeType(format: String?, fallback: String): String = when (format?.lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    else -> fallback
}

internal fun String.urlFileExtension(): String? =
    substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").takeIf(String::isNotEmpty)

/**
 */
internal fun MediaGenerationInput.Image.toUrlOrDataUri(): String {
    if (REMOTE_PREFIXES.any { url.startsWith(it, ignoreCase = true) }) return url
    val file = File(url.removePrefix("file://"))
    require(file.exists()) { "Image file does not exist: $url" }
    val mimeType = LOCAL_IMAGE_MIME_TYPES[file.extension.lowercase()]
        ?: throw IllegalArgumentException("Unsupported image file type: ${file.extension}")
    return "data:$mimeType;base64,${Base64.encode(file.readBytes())}"
}

private val REMOTE_PREFIXES = listOf("http://", "https://", "data:")

private val LOCAL_IMAGE_MIME_TYPES = mapOf(
    "png" to "image/png",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "webp" to "image/webp",
    "bmp" to "image/bmp",
    "gif" to "image/gif",
    "tif" to "image/tiff",
    "tiff" to "image/tiff",
    "heic" to "image/heic",
    "heif" to "image/heif",
)
