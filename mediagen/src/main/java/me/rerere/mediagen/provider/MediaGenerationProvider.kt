package me.rerere.mediagen.provider

import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaGenerationRequest
import me.rerere.mediagen.model.MediaGenerationTask

/**
 *
 *
 */
interface MediaGenerationProvider<S : MediaGenerationProviderSetting> {
    val id: String

    suspend fun create(
        setting: S,
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): Result<MediaGenerationTask>

    suspend fun query(
        setting: S,
        model: MediaGenerationModel,
        taskId: String,
    ): Result<MediaGenerationTask> =
        Result.failure(
            UnsupportedOperationException(
                "$id ${model.kind} generation returns results synchronously and has no task to query"
            )
        )
}
