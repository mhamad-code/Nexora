package me.rerere.mediagen.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 *
 *
 */
@Serializable
data class MediaGenerationRequest(
    val prompt: String? = null,
    val inputs: List<MediaGenerationInput> = emptyList(),
    val count: Int? = null,
    val resolution: String? = null,
    val aspectRatio: String? = null,
    val durationSeconds: Int? = null,
    val generateAudio: Boolean? = null,
    val watermark: Boolean? = null,
    val seed: Long? = null,
    val promptEnhancement: Boolean? = null,
    val callbackUrl: String? = null,
    val extraParameters: JsonObject = JsonObject(emptyMap()),
) {
    init {
        require(!prompt.isNullOrBlank() || inputs.isNotEmpty()) {
            "prompt and inputs cannot both be empty"
        }
        require(count == null || count > 0) { "count must be positive" }
        require(durationSeconds == null || durationSeconds == -1 || durationSeconds > 0) {
            "durationSeconds must be positive or -1"
        }
        require(seed == null || seed >= 0) { "seed must be non-negative" }
    }
}
