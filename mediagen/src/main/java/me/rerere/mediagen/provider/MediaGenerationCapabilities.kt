package me.rerere.mediagen.provider

import me.rerere.mediagen.model.ImageRole
import me.rerere.mediagen.model.MediaGenerationRequest
import me.rerere.mediagen.model.MediaKind

enum class MediaGenerationParameter {
    COUNT,

    RESOLUTION,

    ASPECT_RATIO,

    DURATION,

    GENERATE_AUDIO,

    WATERMARK,

    SEED,

    PROMPT_ENHANCEMENT,
}

/**
 *
 */
data class MediaGenerationCapabilities(
    val parameters: Set<MediaGenerationParameter>,
    val requiredParameters: Set<MediaGenerationParameter> = emptySet(),
    val imageRoles: Set<ImageRole>,
    val videoInput: Boolean = false,
    val framesExcludeReferences: Boolean = false,
    val requiresPrompt: Boolean = false,
    val requiresRemoteInputs: Boolean = false,
)

fun MediaGenerationProviderSetting.capabilities(kind: MediaKind): MediaGenerationCapabilities? =
    when (this) {
        is MediaGenerationProviderSetting.OpenAI -> when (kind) {
            MediaKind.IMAGE -> OPENAI_IMAGE
            MediaKind.VIDEO -> null
        }

        is MediaGenerationProviderSetting.Aliyun -> when (kind) {
            MediaKind.IMAGE -> ALIYUN_IMAGE
            MediaKind.VIDEO -> ALIYUN_VIDEO
        }

        is MediaGenerationProviderSetting.Volcengine -> when (kind) {
            MediaKind.IMAGE -> VOLCENGINE_IMAGE
            MediaKind.VIDEO -> VOLCENGINE_VIDEO
        }

        is MediaGenerationProviderSetting.MiniMax -> when (kind) {
            MediaKind.IMAGE -> null
            MediaKind.VIDEO -> MINIMAX_VIDEO
        }

        is MediaGenerationProviderSetting.OpenRouter -> when (kind) {
            MediaKind.IMAGE -> OPENROUTER_IMAGE
            MediaKind.VIDEO -> OPENROUTER_VIDEO
        }
    }

private val REFERENCE_ONLY = setOf(ImageRole.REFERENCE)
private val ALL_IMAGE_ROLES = ImageRole.entries.toSet()

private val OPENAI_IMAGE = MediaGenerationCapabilities(
    parameters = setOf(MediaGenerationParameter.COUNT, MediaGenerationParameter.RESOLUTION),
    imageRoles = REFERENCE_ONLY,
    requiresPrompt = true,
)

private val ALIYUN_IMAGE = MediaGenerationCapabilities(
    parameters = setOf(
        MediaGenerationParameter.COUNT,
        MediaGenerationParameter.RESOLUTION,
        MediaGenerationParameter.WATERMARK,
        MediaGenerationParameter.SEED,
        MediaGenerationParameter.PROMPT_ENHANCEMENT,
    ),
    imageRoles = REFERENCE_ONLY,
    requiresPrompt = true,
)

private val ALIYUN_VIDEO = MediaGenerationCapabilities(
    parameters = setOf(
        MediaGenerationParameter.RESOLUTION,
        MediaGenerationParameter.ASPECT_RATIO,
        MediaGenerationParameter.DURATION,
        MediaGenerationParameter.GENERATE_AUDIO,
        MediaGenerationParameter.WATERMARK,
        MediaGenerationParameter.SEED,
        MediaGenerationParameter.PROMPT_ENHANCEMENT,
    ),
    imageRoles = ALL_IMAGE_ROLES,
    videoInput = true,
    framesExcludeReferences = true,
    requiresRemoteInputs = true,
)

private val VOLCENGINE_IMAGE = MediaGenerationCapabilities(
    parameters = setOf(MediaGenerationParameter.RESOLUTION, MediaGenerationParameter.WATERMARK),
    imageRoles = REFERENCE_ONLY,
    requiresPrompt = true,
)

private val VOLCENGINE_VIDEO = MediaGenerationCapabilities(
    parameters = setOf(
        MediaGenerationParameter.RESOLUTION,
        MediaGenerationParameter.ASPECT_RATIO,
        MediaGenerationParameter.DURATION,
        MediaGenerationParameter.GENERATE_AUDIO,
        MediaGenerationParameter.WATERMARK,
        MediaGenerationParameter.SEED,
    ),
    imageRoles = ALL_IMAGE_ROLES,
    videoInput = true,
    framesExcludeReferences = true,
    requiresRemoteInputs = true,
)

private val MINIMAX_VIDEO = MediaGenerationCapabilities(
    parameters = setOf(
        MediaGenerationParameter.RESOLUTION,
        MediaGenerationParameter.ASPECT_RATIO,
        MediaGenerationParameter.DURATION,
        MediaGenerationParameter.WATERMARK,
    ),
    requiredParameters = setOf(
        MediaGenerationParameter.RESOLUTION,
        MediaGenerationParameter.ASPECT_RATIO,
        MediaGenerationParameter.DURATION,
    ),
    imageRoles = ALL_IMAGE_ROLES,
    videoInput = true,
    framesExcludeReferences = true,
    requiresPrompt = true,
    requiresRemoteInputs = true,
)

private val OPENROUTER_IMAGE = MediaGenerationCapabilities(
    parameters = setOf(
        MediaGenerationParameter.COUNT,
        MediaGenerationParameter.RESOLUTION,
        MediaGenerationParameter.ASPECT_RATIO,
        MediaGenerationParameter.SEED,
    ),
    imageRoles = REFERENCE_ONLY,
    requiresPrompt = true,
)

private val OPENROUTER_VIDEO = MediaGenerationCapabilities(
    parameters = setOf(
        MediaGenerationParameter.RESOLUTION,
        MediaGenerationParameter.ASPECT_RATIO,
        MediaGenerationParameter.DURATION,
        MediaGenerationParameter.GENERATE_AUDIO,
        MediaGenerationParameter.SEED,
    ),
    imageRoles = ALL_IMAGE_ROLES,
    videoInput = true,
    framesExcludeReferences = true,
    requiresRemoteInputs = true,
)
