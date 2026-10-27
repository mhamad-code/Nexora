package com.mhamad.nexora.ui.components.richtext

import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.net.URL
import java.util.Locale

private val imageCache = LruCache<String, String?>(64)
private val imageJson = Json { ignoreUnknownKeys = true }

/** External hosts contacted: the supplied HTTPS page host, ${'$'}{locale.language}.wikipedia.org, and upload.wikimedia.org. */
suspend fun resolveNexoraImage(imageQuery: String?, pageUrl: String?, locale: Locale = Locale.getDefault()): String? = withContext(Dispatchers.IO) {
    val key = pageUrl ?: "query:${imageQuery.orEmpty()}:${locale.language}"
    synchronized(imageCache) { imageCache.get(key) }?.let { return@withContext it }
    val result = runCatching {
        pageUrl?.takeIf(::isSafeExternalUrl)?.let(::fetchOgImage)
            ?: imageQuery?.takeIf { it.isNotBlank() }?.let { queryWikipedia(it, locale.language) }
    }.getOrNull()?.takeIf(::isSafeExternalUrl)
    synchronized(imageCache) { imageCache.put(key, result) }
    result
}

private fun fetchOgImage(page: String): String? {
    val body = fetchHttps(page) ?: return null
    Regex("<meta[^>]+property=[\\\"']og:image[\\\"'][^>]+content=[\\\"']([^\\\"']+)", RegexOption.IGNORE_CASE)
        .find(body)?.groupValues?.getOrNull(1)
        ?.let { if (it.startsWith("https://")) it else URI(page).resolve(it).toString() }
        ?.takeIf(::isSafeExternalUrl)
        ?.let { return it }
    return null
}

private fun queryWikipedia(query: String, language: String): String? {
    val lang = language.takeIf { it.matches(Regex("[a-zA-Z]{2,3}")) } ?: "en"
    val api = "https://${lang.lowercase(Locale.US)}.wikipedia.org/w/api.php?action=query&generator=search&gsrsearch=${URLEncoder.encode(query, "UTF-8")}&gsrnamespace=0&gsrlimit=1&prop=pageimages&piprop=thumbnail&pithumbsize=640&format=json"
    val root = imageJson.parseToJsonElement(fetchHttps(api) ?: return null).jsonObject
    val pages = root["query"]?.jsonObject?.get("pages")?.jsonObject ?: return null
    return pages.values.firstOrNull()?.jsonObject?.get("thumbnail")?.jsonObject?.get("source")?.jsonPrimitive?.content
}

private fun fetchHttps(value: String): String? {
    var current = value
    repeat(3) {
        val uri = URI(current)
        if (uri.scheme != "https" || !isSafeExternalUrl(current)) return null
        val connection = URL(current).openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", "Nexora/1.0")
        try {
            if (connection.responseCode in 300..399) {
                current = connection.getHeaderField("Location")?.let { uri.resolve(it).toString() } ?: return null
                return@repeat
            }
            if (connection.responseCode !in 200..299) return null
            val contentLength = connection.contentLengthLong
            if (contentLength >= 0L && contentLength > 512L * 1024L) return null
            return connection.inputStream.bufferedReader().use { it.readText().take(512 * 1024) }
        } finally { connection.disconnect() }
    }
    return null
}

private fun isSafeExternalUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.host.endsWith(".local")) return false
    val address = InetAddress.getByName(uri.host)
    !(address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress)
}.getOrDefault(false)
