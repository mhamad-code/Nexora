package me.rerere.mediagen.provider.providers.aliyun

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.mediagen.model.MediaGenerationError
import me.rerere.mediagen.model.MediaGenerationInput
import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaGenerationOutput
import me.rerere.mediagen.model.MediaGenerationRequest
import me.rerere.mediagen.model.MediaGenerationStatus
import me.rerere.mediagen.model.MediaGenerationTask
import me.rerere.mediagen.model.MediaGenerationUsage
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.mediagen.provider.providers.bearerRequest
import me.rerere.mediagen.provider.providers.executeJson
import me.rerere.mediagen.provider.providers.imageMimeType
import me.rerere.mediagen.provider.providers.int
import me.rerere.mediagen.provider.providers.long
import me.rerere.mediagen.provider.providers.obj
import me.rerere.mediagen.provider.providers.postJson
import me.rerere.mediagen.provider.providers.string
import me.rerere.mediagen.provider.providers.toUrlOrDataUri
import me.rerere.mediagen.provider.providers.urlFileExtension
import okhttp3.OkHttpClient
import kotlin.uuid.Uuid

/**
 */
internal class AliyunImageGeneration(
    private val client: OkHttpClient,
) {
    private val id = AliyunMediaGenerationProvider.PROVIDER_ID

    suspend fun create(
        setting: MediaGenerationProviderSetting.Aliyun,
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): MediaGenerationTask {
        val body = withContext(Dispatchers.IO) { buildBody(model, request) }
        val httpRequest = bearerRequest(
            "${setting.resolvedBaseUrl()}/services/aigc/multimodal-generation/generation",
            setting.apiKey,
        ).postJson(body).build()
        return parseTask(client.executeJson(httpRequest, id), model.modelId)
    }

    internal fun buildBody(
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): JsonObject = buildJsonObject {
        validate(request)
        put("model", model.modelId)
        put("input", buildJsonObject {
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject { put("text", request.prompt) })
                        request.inputs.forEach { input ->
                            require(input is MediaGenerationInput.Image) {
                                "Aliyun image generation does not support ${input::class.simpleName} input"
                            }
                            add(buildJsonObject { put("image", input.toUrlOrDataUri()) })
                        }
                    })
                })
            })
        })
        put("parameters", buildJsonObject {
            request.extraParameters.forEach { (key, value) -> put(key, value) }
            request.resolution?.takeIf(String::isNotBlank)?.let { put("size", it.toAliyunSize()) }
            request.count?.let { put("n", it) }
            request.watermark?.let { put("watermark", it) }
            request.seed?.let { put("seed", it) }
            request.promptEnhancement?.let { put("prompt_extend", it) }
        })
    }

    internal fun parseTask(root: JsonObject, fallbackModel: String? = null): MediaGenerationTask {
        val usageObject = root.obj("usage")
        val resolution = usageObject?.resolution()
        val urls = (root.obj("output")?.get("choices") as? JsonArray).orEmpty()
            .flatMap { choice -> ((choice as? JsonObject)?.obj("message")?.get("content") as? JsonArray).orEmpty() }
            .mapNotNull { (it as? JsonObject)?.string("image") }
        return MediaGenerationTask(
            id = Uuid.random().toString(),
            provider = id,
            model = fallbackModel,
            status = if (urls.isEmpty()) MediaGenerationStatus.FAILED else MediaGenerationStatus.SUCCEEDED,
            outputs = urls.map { url ->
                MediaGenerationOutput(
                    url = url,
                    mimeType = imageMimeType(url.urlFileExtension(), "image/png"),
                    resolution = resolution,
                )
            },
            error = if (urls.isEmpty()) {
                MediaGenerationError(root.string("code"), root.string("message") ?: "Aliyun returned no image")
            } else {
                null
            },
            usage = usageObject?.let {
                MediaGenerationUsage(totalTokens = it.long("total_tokens"))
            },
            metadata = root,
        )
    }

    private fun validate(request: MediaGenerationRequest) {
        require(!request.prompt.isNullOrBlank()) { "Aliyun image generation requires a non-empty prompt" }
        require(request.aspectRatio == null) { "Aliyun image generation uses resolution instead of aspectRatio" }
        require(request.durationSeconds == null) { "Aliyun image generation does not expose duration" }
        require(request.generateAudio == null) { "Aliyun image generation does not expose generateAudio" }
        require(request.callbackUrl == null) { "Aliyun image generation returns results synchronously" }
    }

    private fun String.toAliyunSize(): String =
        PIXEL_SIZE.matchEntire(trim())?.let { "${it.groupValues[1]}*${it.groupValues[2]}" } ?: this

    private fun JsonObject.resolution(): String? {
        string("size")?.let { return it.replace('*', 'x') }
        val width = int("output_width") ?: int("width") ?: return null
        val height = int("output_height") ?: int("height") ?: return null
        return "${width}x$height"
    }

    private companion object {
        val PIXEL_SIZE = Regex("""(\d+)\s*[xX*]\s*(\d+)""")
    }
}
