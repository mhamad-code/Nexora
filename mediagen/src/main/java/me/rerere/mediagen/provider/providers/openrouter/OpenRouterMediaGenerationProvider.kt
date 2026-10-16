package me.rerere.mediagen.provider.providers.openrouter

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaGenerationRequest
import me.rerere.mediagen.model.MediaGenerationTask
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.MediaGenerationProvider
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.mediagen.provider.providers.bearerRequest
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 */
class OpenRouterMediaGenerationProvider(
    client: OkHttpClient,
) : MediaGenerationProvider<MediaGenerationProviderSetting.OpenRouter> {
    override val id: String = PROVIDER_ID

    private val image = OpenRouterImageGeneration(client)
    private val video = OpenRouterVideoGeneration(client)

    override suspend fun create(
        setting: MediaGenerationProviderSetting.OpenRouter,
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): Result<MediaGenerationTask> = runCatching {
        when (model.kind) {
            MediaKind.IMAGE -> image.create(setting, model, request)
            MediaKind.VIDEO -> video.create(setting, model, request)
        }
    }

    override suspend fun query(
        setting: MediaGenerationProviderSetting.OpenRouter,
        model: MediaGenerationModel,
        taskId: String,
    ): Result<MediaGenerationTask> = when (model.kind) {
        MediaKind.IMAGE -> super.query(setting, model, taskId)
        MediaKind.VIDEO -> runCatching { video.query(setting, model, taskId) }
    }

    internal companion object {
        const val PROVIDER_ID = "openrouter"
    }
}

internal fun MediaGenerationProviderSetting.OpenRouter.request(path: String): Request.Builder =
    bearerRequest("${baseUrl.trimEnd('/')}$path", apiKey)
        .addHeader("X-Title", "Nexora")
        .addHeader("HTTP-Referer", "https://github.com/mhamad-code/Nexora")

internal fun JsonObjectBuilder.putResolution(resolution: String?) {
    if (resolution.isNullOrBlank()) return
    put(if (PIXEL_SIZE.matches(resolution)) "size" else "resolution", resolution)
}

internal fun imageUrlPart(url: String, extra: JsonObject): JsonObject = buildJsonObject {
    extra.forEach { (key, value) -> put(key, value) }
    put("type", "image_url")
    put("image_url", buildJsonObject { put("url", url) })
}

private val PIXEL_SIZE = Regex("""\d+[xX]\d+""")
