package me.rerere.mediagen.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
sealed class MediaGenerationInput {
    abstract val extra: JsonObject

    /**
     */
    @Serializable
    @SerialName("image")
    data class Image(
        val url: String,
        val role: ImageRole = ImageRole.REFERENCE,
        override val extra: JsonObject = JsonObject(emptyMap()),
    ) : MediaGenerationInput()

    @Serializable
    @SerialName("video")
    data class Video(
        val url: String,
        override val extra: JsonObject = JsonObject(emptyMap()),
    ) : MediaGenerationInput()

    @Serializable
    @SerialName("audio")
    data class Audio(
        val url: String,
        override val extra: JsonObject = JsonObject(emptyMap()),
    ) : MediaGenerationInput()

    @Serializable
    @SerialName("document")
    data class Document(
        val url: String,
        override val extra: JsonObject = JsonObject(emptyMap()),
    ) : MediaGenerationInput()

    @Serializable
    @SerialName("web_page")
    data class WebPage(
        val url: String,
        override val extra: JsonObject = JsonObject(emptyMap()),
    ) : MediaGenerationInput()

    /**
     */
    @Serializable
    @SerialName("raw")
    data class Raw(
        val value: JsonObject,
        override val extra: JsonObject = JsonObject(emptyMap()),
    ) : MediaGenerationInput()
}

@Serializable
enum class ImageRole {
    @SerialName("first_frame")
    FIRST_FRAME,

    @SerialName("last_frame")
    LAST_FRAME,

    @SerialName("reference")
    REFERENCE,
}
