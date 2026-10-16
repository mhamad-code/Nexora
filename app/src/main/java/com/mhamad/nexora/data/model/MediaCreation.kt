package com.mhamad.nexora.data.model

import kotlinx.serialization.Serializable
import me.rerere.mediagen.model.ImageRole
import me.rerere.mediagen.model.MediaGenerationInput
import me.rerere.mediagen.model.MediaGenerationRequest
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.MediaGenerationCapabilities
import me.rerere.mediagen.provider.MediaGenerationParameter
import java.time.Instant
import kotlin.uuid.Uuid

/**
 */
data class MediaCreationSession(
    val id: Uuid = Uuid.random(),
    val title: String = "",
    val draft: MediaCreationDraft = MediaCreationDraft(),
    val createAt: Instant = Instant.now(),
    val updateAt: Instant = Instant.now(),
    val nodeCount: Int = 0,
    val activeCount: Int = 0,
    val cover: MediaCreationOutput? = null,
)

/**
 *
 */
@Serializable
data class MediaCreationDraft(
    val modelId: Uuid? = null,
    val prompt: String = "",
    val assets: List<MediaCreationAsset> = emptyList(),
    val params: MediaCreationParams = MediaCreationParams(),
    val editingNodeId: Uuid? = null,
)

@Serializable
enum class MediaCreationAssetType {
    IMAGE,
    VIDEO,
}

/**
 */
@Serializable
data class MediaCreationAsset(
    val path: String,
    val type: MediaCreationAssetType = MediaCreationAssetType.IMAGE,
    val role: ImageRole = ImageRole.REFERENCE,
)

/**
 */
@Serializable
data class MediaCreationParams(
    val count: Int? = null,
    val resolution: String? = null,
    val aspectRatio: String? = null,
    val durationSeconds: Int? = null,
    val generateAudio: Boolean? = null,
    val watermark: Boolean? = null,
    val seed: Long? = null,
    val promptEnhancement: Boolean? = null,
)

/**
 */
@Serializable
data class MediaCreationOutput(
    val path: String,
    val mimeType: String,
    val width: Int? = null,
    val height: Int? = null,
    val durationSeconds: Double? = null,
    val posterPath: String? = null,
    val lastFramePath: String? = null,
) {
    val isVideo: Boolean
        get() = mimeType.startsWith("video/")
}

enum class MediaCreationStatus {
    PREPARING,
    QUEUED,
    RUNNING,

    DOWNLOADING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    val isActive: Boolean
        get() = this in ACTIVE

    companion object {
        val ACTIVE = setOf(PREPARING, QUEUED, RUNNING, DOWNLOADING)
    }
}

/**
 *
 *
 */
data class MediaCreationRecord(
    val id: Uuid = Uuid.random(),
    val sessionId: Uuid,
    val nodeId: Uuid = Uuid.random(),
    val providerId: Uuid,
    val providerName: String,
    val modelId: String,
    val kind: MediaKind,
    val prompt: String,
    val params: MediaCreationParams = MediaCreationParams(),
    val inputs: List<MediaCreationAsset> = emptyList(),
    val status: MediaCreationStatus = MediaCreationStatus.PREPARING,
    val taskId: String? = null,
    val error: String? = null,
    val outputs: List<MediaCreationOutput> = emptyList(),
    val createAt: Instant = Instant.now(),
    val updateAt: Instant = Instant.now(),
)

/**
 */
data class MediaCreationNode(
    val record: MediaCreationRecord,
    val versionIndex: Int = 0,
    val versionCount: Int = 1,
) {
    val id: Uuid
        get() = record.nodeId
}

/**
 */
fun MediaCreationParams.supportedBy(capabilities: MediaGenerationCapabilities): MediaCreationParams {
    fun <T> T?.takeIfSupported(parameter: MediaGenerationParameter): T? =
        takeIf { parameter in capabilities.parameters }
    return MediaCreationParams(
        count = count.takeIfSupported(MediaGenerationParameter.COUNT),
        resolution = resolution?.takeIf(String::isNotBlank).takeIfSupported(MediaGenerationParameter.RESOLUTION),
        aspectRatio = aspectRatio?.takeIf(String::isNotBlank).takeIfSupported(MediaGenerationParameter.ASPECT_RATIO),
        durationSeconds = durationSeconds.takeIfSupported(MediaGenerationParameter.DURATION),
        generateAudio = generateAudio.takeIfSupported(MediaGenerationParameter.GENERATE_AUDIO),
        watermark = watermark.takeIfSupported(MediaGenerationParameter.WATERMARK),
        seed = seed.takeIfSupported(MediaGenerationParameter.SEED),
        promptEnhancement = promptEnhancement.takeIfSupported(MediaGenerationParameter.PROMPT_ENHANCEMENT),
    )
}

/**
 */
fun MediaCreationParams.withRequired(
    capabilities: MediaGenerationCapabilities,
    defaults: MediaCreationParams,
): MediaCreationParams {
    fun <T> T?.orRequired(parameter: MediaGenerationParameter, default: T?): T? =
        this ?: default.takeIf { parameter in capabilities.requiredParameters }
    return MediaCreationParams(
        count = count.orRequired(MediaGenerationParameter.COUNT, defaults.count),
        resolution = resolution?.takeIf(String::isNotBlank)
            .orRequired(MediaGenerationParameter.RESOLUTION, defaults.resolution),
        aspectRatio = aspectRatio?.takeIf(String::isNotBlank)
            .orRequired(MediaGenerationParameter.ASPECT_RATIO, defaults.aspectRatio),
        durationSeconds = durationSeconds.orRequired(MediaGenerationParameter.DURATION, defaults.durationSeconds),
        generateAudio = generateAudio.orRequired(MediaGenerationParameter.GENERATE_AUDIO, defaults.generateAudio),
        watermark = watermark.orRequired(MediaGenerationParameter.WATERMARK, defaults.watermark),
        seed = seed.orRequired(MediaGenerationParameter.SEED, defaults.seed),
        promptEnhancement = promptEnhancement
            .orRequired(MediaGenerationParameter.PROMPT_ENHANCEMENT, defaults.promptEnhancement),
    )
}

/**
 */
fun List<MediaCreationAsset>.supportedBy(capabilities: MediaGenerationCapabilities): List<MediaCreationAsset> {
    val coerced = mapNotNull { asset ->
        when (asset.type) {
            MediaCreationAssetType.IMAGE -> when {
                capabilities.imageRoles.isEmpty() -> null
                asset.role in capabilities.imageRoles -> asset
                else -> asset.copy(role = ImageRole.REFERENCE)
            }

            MediaCreationAssetType.VIDEO -> asset.takeIf { capabilities.videoInput }
        }
    }
    return coerced.filterIndexed { index, asset ->
        asset.type != MediaCreationAssetType.IMAGE ||
            asset.role == ImageRole.REFERENCE ||
            coerced.indexOfLast { it.type == asset.type && it.role == asset.role } == index
    }
}

/**
 */
fun List<MediaCreationAsset>.mixesFramesWithReferences(capabilities: MediaGenerationCapabilities): Boolean {
    if (!capabilities.framesExcludeReferences) return false
    val (references, frames) = partition { it.type == MediaCreationAssetType.VIDEO || it.role == ImageRole.REFERENCE }
    return references.isNotEmpty() && frames.isNotEmpty()
}

/**
 */
fun MediaCreationDraft.canSubmit(capabilities: MediaGenerationCapabilities): Boolean =
    (prompt.isNotBlank() || (!capabilities.requiresPrompt && assets.isNotEmpty())) &&
        !assets.mixesFramesWithReferences(capabilities)

/**
 */
fun buildMediaGenerationRequest(
    prompt: String,
    params: MediaCreationParams,
    assets: List<MediaCreationAsset>,
    inputUrls: List<String>,
    capabilities: MediaGenerationCapabilities,
): MediaGenerationRequest {
    require(assets.size == inputUrls.size) { "assets and inputUrls must have the same size" }
    val supported = params.supportedBy(capabilities)
    return MediaGenerationRequest(
        prompt = prompt.takeIf(String::isNotBlank),
        inputs = assets.zip(inputUrls) { asset, url ->
            when (asset.type) {
                MediaCreationAssetType.IMAGE -> MediaGenerationInput.Image(url = url, role = asset.role)
                MediaCreationAssetType.VIDEO -> MediaGenerationInput.Video(url = url)
            }
        },
        count = supported.count,
        resolution = supported.resolution,
        aspectRatio = supported.aspectRatio,
        durationSeconds = supported.durationSeconds,
        generateAudio = supported.generateAudio,
        watermark = supported.watermark,
        seed = supported.seed,
        promptEnhancement = supported.promptEnhancement,
    )
}

fun deriveMediaCreationTitle(prompt: String): String =
    prompt.lineSequence().map(String::trim).firstOrNull(String::isNotEmpty).orEmpty().take(TITLE_MAX_LENGTH)

private const val TITLE_MAX_LENGTH = 24
