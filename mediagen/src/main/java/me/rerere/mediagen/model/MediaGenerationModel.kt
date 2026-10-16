package me.rerere.mediagen.model

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
enum class MediaKind {
    IMAGE,
    VIDEO,
}

/**
 */
@Serializable
data class MediaGenerationModel(
    val modelId: String,
    val kind: MediaKind,
    val displayName: String = "",
    val id: Uuid = Uuid.random(),
)
