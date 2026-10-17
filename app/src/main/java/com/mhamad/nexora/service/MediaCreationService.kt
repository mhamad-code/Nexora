package com.mhamad.nexora.service

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.common.http.await
import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaGenerationOutput
import me.rerere.mediagen.model.MediaGenerationStatus
import me.rerere.mediagen.model.MediaGenerationTask
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.MediaGenerationManager
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.mediagen.provider.capabilities
import me.rerere.mediagen.provider.providers.MediaGenerationApiException
import com.mhamad.nexora.AppScope
import com.mhamad.nexora.MEDIA_CREATION_NOTIFICATION_CHANNEL_ID
import com.mhamad.nexora.R
import com.mhamad.nexora.data.datastore.SettingsStore
import com.mhamad.nexora.data.files.MediaCreationFiles
import com.mhamad.nexora.data.remote.RemoteFileStore
import com.mhamad.nexora.data.model.MediaCreationAsset
import com.mhamad.nexora.data.model.MediaCreationOutput
import com.mhamad.nexora.data.model.MediaCreationParams
import com.mhamad.nexora.data.model.MediaCreationRecord
import com.mhamad.nexora.data.model.MediaCreationStatus
import com.mhamad.nexora.data.model.buildMediaGenerationRequest
import com.mhamad.nexora.data.model.deriveMediaCreationTitle
import com.mhamad.nexora.data.model.supportedBy
import com.mhamad.nexora.data.repository.MediaCreationRepository
import com.mhamad.nexora.utils.sendNotification
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlin.uuid.Uuid

private const val TAG = "MediaCreationService"

/**
 *
 */
data class MediaCreationSubmission(
    val sessionId: Uuid,
    val nodeId: Uuid? = null,
    val provider: MediaGenerationProviderSetting,
    val model: MediaGenerationModel,
    val prompt: String,
    val params: MediaCreationParams,
    val assets: List<MediaCreationAsset>,
)

/**
 *
 */
class MediaCreationService(
    private val context: Application,
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val repository: MediaCreationRepository,
    private val manager: MediaGenerationManager,
    private val remoteFileStore: RemoteFileStore,
    private val okHttpClient: OkHttpClient,
) {
    private val jobs = ConcurrentHashMap<Uuid, Job>()

    /**
     */
    suspend fun submit(submission: MediaCreationSubmission): MediaCreationRecord = inAppScope {
        val capabilities = submission.provider.capabilities(submission.model.kind)
            ?: error(context.getString(R.string.media_creation_error_kind_unsupported, submission.provider.name))
        val recordId = Uuid.random()
        val assets = submission.assets.supportedBy(capabilities)
        val record = MediaCreationRecord(
            id = recordId,
            sessionId = submission.sessionId,
            nodeId = submission.nodeId ?: Uuid.random(),
            providerId = submission.provider.id,
            providerName = submission.provider.name,
            modelId = submission.model.modelId,
            kind = submission.model.kind,
            prompt = submission.prompt.trim(),
            params = submission.params.supportedBy(capabilities),
            inputs = copyInputs(submission.sessionId, recordId, assets),
        )
        val job = register(record.id, record.sessionId)
            ?: error(context.getString(R.string.media_creation_error_task_exists))
        try {
            repository.insertRecord(record)
            repository.updateSession(record.sessionId) { session ->
                session.copy(
                    title = session.title.ifBlank { deriveMediaCreationTitle(record.prompt) },
                    updateAt = Instant.now(),
                )
            }
        } catch (e: Throwable) {
            jobs.remove(record.id, job)
            job.cancel()
            withContext(Dispatchers.IO) { repository.recordDir(record.sessionId, record.id).deleteRecursively() }
            throw e
        }
        job.start()
        record
    }

    /**
     */
    suspend fun retry(recordId: Uuid) = inAppScope {
        val sessionId = repository.getRecord(recordId)?.sessionId ?: return@inAppScope
        val job = register(recordId, sessionId) ?: return@inAppScope
        var started = false
        try {
            val record = repository.updateRecord(recordId) { record ->
                if (record.status == MediaCreationStatus.SUCCEEDED) {
                    record
                } else {
                    record.copy(
                        status = if (record.taskId != null) MediaCreationStatus.RUNNING else MediaCreationStatus.PREPARING,
                        error = null,
                        outputs = emptyList(),
                    )
                }
            }
            if (record != null && record.status != MediaCreationStatus.SUCCEEDED) {
                job.start()
                started = true
            }
        } finally {
            if (!started) {
                jobs.remove(recordId, job)
                job.cancel()
            }
        }
    }

    /**
     */
    suspend fun cancel(recordId: Uuid) = inAppScope {
        jobs[recordId]?.cancelAndJoin()
        repository.updateRecord(recordId) { record ->
            if (record.status.isActive) record.copy(status = MediaCreationStatus.CANCELLED) else record
        }
    }

    suspend fun deleteRecord(recordId: Uuid) = inAppScope {
        jobs[recordId]?.cancelAndJoin()
        repository.getRecord(recordId)?.let { repository.deleteRecord(it) }
    }

    suspend fun deleteSession(sessionId: Uuid) = inAppScope {
        repository.getRecordsOfSession(sessionId).forEach { jobs[it.id]?.cancelAndJoin() }
        repository.deleteSession(sessionId)
    }

    /**
     */
    fun resumePending() {
        appScope.launch {
            runCatching {
                repository.getActiveRecords().forEach { record ->
                    if (jobs.containsKey(record.id)) return@forEach
                    if (record.taskId != null) {
                        register(record.id, record.sessionId)?.start()
                    } else {
                        repository.updateRecord(record.id) {
                            it.copy(
                                status = MediaCreationStatus.FAILED,
                                error = context.getString(R.string.media_creation_error_interrupted),
                            )
                        }
                    }
                }
            }.onFailure {
                Log.e(TAG, "resumePending failed", it)
            }
        }
    }

    private fun register(recordId: Uuid, sessionId: Uuid): Job? {
        val job = appScope.launch(start = CoroutineStart.LAZY) {
            val foreground = MediaCreationForegroundService.acquire(context, recordId, sessionId)
            try {
                run(recordId)
            } finally {
                jobs.remove(recordId, coroutineContext.job)
                if (foreground) MediaCreationForegroundService.release(context, recordId)
            }
        }
        if (jobs.putIfAbsent(recordId, job) != null) {
            job.cancel()
            return null
        }
        return job
    }

    private suspend fun run(recordId: Uuid) {
        try {
            generate(recordId)
        } catch (e: Throwable) {
            currentCoroutineContext().ensureActive()
            Log.e(TAG, "Generation $recordId failed", e)
            repository.updateRecord(recordId) {
                it.copy(status = MediaCreationStatus.FAILED, error = e.toDisplayMessage())
            }
        }
        repository.getRecord(recordId)?.let(::notifyFinished)
    }

    private suspend fun generate(recordId: Uuid) {
        val record = repository.getRecord(recordId) ?: return
        val setting = settingsStore.awaitLoaded()
            .mediaGenerationProviders.find { it.id == record.providerId }
            ?: error(context.getString(R.string.media_creation_error_provider_deleted, record.providerName))
        val model = MediaGenerationModel(modelId = record.modelId, kind = record.kind)

        val task = if (record.taskId != null) {
            awaitTask(setting, model, record.id, record.taskId, since = record.updateAt)
        } else {
            val created = create(setting, model, record)
            if (created.isTerminal) {
                created
            } else {
                withContext(NonCancellable) {
                    repository.updateRecord(record.id) {
                        it.copy(taskId = created.id, status = created.status.toCreationStatus(it.status))
                    }
                }
                delay(POLL_INTERVAL)
                awaitTask(setting, model, record.id, created.id, since = Instant.now())
            }
        }

        if (task.status != MediaGenerationStatus.SUCCEEDED) {
            repository.updateRecord(record.id) {
                it.copy(status = MediaCreationStatus.FAILED, taskId = null, error = task.failureMessage(context))
            }
            return
        }

        repository.updateRecord(record.id) { it.copy(status = MediaCreationStatus.DOWNLOADING) }
        val outputs = downloadOutputs(setting, record, task.outputs)
        repository.updateRecord(record.id) {
            if (outputs.isEmpty()) {
                it.copy(
                    status = MediaCreationStatus.FAILED,
                    taskId = null,
                    error = context.getString(R.string.media_creation_error_no_output),
                )
            } else {
                it.copy(status = MediaCreationStatus.SUCCEEDED, outputs = outputs, error = null)
            }
        }
    }

    private suspend fun create(
        setting: MediaGenerationProviderSetting,
        model: MediaGenerationModel,
        record: MediaCreationRecord,
    ): MediaGenerationTask {
        val capabilities = setting.capabilities(record.kind)
            ?: error(context.getString(R.string.media_creation_error_kind_unsupported, setting.name))
        val inputUrls = if (capabilities.requiresRemoteInputs && record.inputs.isNotEmpty()) {
            check(remoteFileStore.isConfigured) { context.getString(R.string.media_creation_error_upload_required) }
            repository.updateRecord(record.id) { it.copy(status = MediaCreationStatus.PREPARING) }
            record.inputs.map { remoteFileStore.upload(repository.resolve(it.path)).getOrThrow().url }
        } else {
            record.inputs.map { repository.resolve(it.path).absolutePath }
        }
        repository.updateRecord(record.id) { it.copy(status = MediaCreationStatus.RUNNING) }
        val request = buildMediaGenerationRequest(record.prompt, record.params, record.inputs, inputUrls, capabilities)
        return manager.create(setting, model, request).getOrElse {
            currentCoroutineContext().ensureActive()
            throw it
        }
    }

    /**
     *
     */
    private suspend fun awaitTask(
        setting: MediaGenerationProviderSetting,
        model: MediaGenerationModel,
        recordId: Uuid,
        taskId: String,
        since: Instant,
    ): MediaGenerationTask {
        val deadline = since + MAX_WAIT.toJavaDuration()
        var failures = 0
        while (true) {
            val result = manager.query(setting, model, taskId)
            currentCoroutineContext().ensureActive()
            result.onSuccess { task ->
                failures = 0
                if (task.isTerminal) return task
                repository.updateRecord(recordId) { it.copy(status = task.status.toCreationStatus(it.status)) }
            }.onFailure { error ->
                failures++
                Log.w(TAG, "Query $taskId failed ($failures)", error)
                if (!error.isRetryable() || failures >= MAX_QUERY_FAILURES) throw error
            }
            check(Instant.now() < deadline) { context.getString(R.string.media_creation_error_timeout) }
            delay(pollDelay(failures))
        }
    }

    private suspend fun copyInputs(
        sessionId: Uuid,
        recordId: Uuid,
        assets: List<MediaCreationAsset>,
    ): List<MediaCreationAsset> = withContext(Dispatchers.IO) {
        if (assets.isEmpty()) return@withContext emptyList()
        val dir = repository.recordDir(sessionId, recordId)
        try {
            dir.mkdirs()
            assets.mapIndexed { index, asset ->
                val source = repository.resolve(asset.path)
                check(source.isFile) { context.getString(R.string.media_creation_error_asset_missing) }
                val target = File(dir, "in_$index.${source.extension.ifEmpty { "bin" }}")
                source.copyTo(target, overwrite = true)
                asset.copy(path = repository.relativePath(target))
            }
        } catch (e: Throwable) {
            dir.deleteRecursively()
            throw e
        }
    }

    private suspend fun downloadOutputs(
        setting: MediaGenerationProviderSetting,
        record: MediaCreationRecord,
        outputs: List<MediaGenerationOutput>,
    ): List<MediaCreationOutput> = withContext(Dispatchers.IO) {
        val dir = repository.recordDir(record.sessionId, record.id).apply { mkdirs() }
        dir.listFiles { file -> file.name.startsWith(OUTPUT_PREFIX) }?.forEach { it.delete() }

        outputs.mapIndexedNotNull { index, output ->
            val isVideo = output.mimeType.startsWith("video/")
            val file = File(dir, "$OUTPUT_PREFIX$index.${output.fileExtension()}")
            val data = output.data
            val url = output.url
            when {
                data != null -> file.writeBytes(data)
                url != null -> download(setting, url, file)
                else -> return@mapIndexedNotNull null
            }

            if (isVideo) {
                val poster = File(dir, "$OUTPUT_PREFIX${index}_poster.jpg")
                    .takeIf { MediaCreationFiles.extractFrame(file, it, last = false) }
                val lastFrame = output.lastFrameUrl?.let { lastFrameUrl ->
                    File(dir, "$OUTPUT_PREFIX${index}_last.${lastFrameUrl.urlExtension() ?: "jpg"}")
                        .takeIf { runCatching { download(setting, lastFrameUrl, it, attempts = 1) }.isSuccess }
                }
                val info = MediaCreationFiles.videoInfo(file)
                MediaCreationOutput(
                    path = repository.relativePath(file),
                    mimeType = output.mimeType,
                    width = info?.width,
                    height = info?.height,
                    durationSeconds = output.durationSeconds ?: info?.durationSeconds,
                    posterPath = poster?.let(repository::relativePath),
                    lastFramePath = lastFrame?.let(repository::relativePath),
                )
            } else {
                val info = MediaCreationFiles.imageInfo(file)
                MediaCreationOutput(
                    path = repository.relativePath(file),
                    mimeType = output.mimeType,
                    width = info?.width,
                    height = info?.height,
                )
            }
        }
    }

    private suspend fun download(
        setting: MediaGenerationProviderSetting,
        url: String,
        target: File,
        attempts: Int = DOWNLOAD_ATTEMPTS,
    ) {
        val request = Request.Builder().url(url).headers(setting.downloadHeaders(url).toHeaders()).build()
        var attempt = 0
        while (true) {
            attempt++
            try {
                downloadOnce(request, target)
                return
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (attempt >= attempts) throw e
                Log.w(TAG, "Download failed ($attempt/$attempts)", e)
                delay(POLL_INTERVAL)
            }
        }
    }

    private suspend fun downloadOnce(request: Request, target: File) {
        val partial = File(target.parentFile, target.name + MediaCreationFiles.PARTIAL_SUFFIX)
        val call = okHttpClient.newCall(request)
        try {
            coroutineScope {
                val canceller = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        call.cancel()
                    }
                }
                call.await().use { response ->
                    check(response.isSuccessful) {
                        context.getString(R.string.media_creation_error_download_failed, response.code)
                    }
                    response.body.byteStream().use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                }
                canceller.cancel()
            }
            check(partial.renameTo(target)) { context.getString(R.string.media_creation_error_save_failed) }
        } finally {
            partial.delete()
        }
    }

    private fun notifyFinished(record: MediaCreationRecord) {
        if (record.status != MediaCreationStatus.SUCCEEDED && record.status != MediaCreationStatus.FAILED) return
        if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        val isVideo = record.kind == MediaKind.VIDEO
        val succeeded = record.status == MediaCreationStatus.SUCCEEDED
        val titleRes = when {
            isVideo && succeeded -> R.string.media_creation_notification_video_succeeded
            isVideo -> R.string.media_creation_notification_video_failed
            succeeded -> R.string.media_creation_notification_image_succeeded
            else -> R.string.media_creation_notification_image_failed
        }
        context.sendNotification(
            channelId = MEDIA_CREATION_NOTIFICATION_CHANNEL_ID,
            notificationId = record.id.hashCode(),
        ) {
            title = context.getString(titleRes)
            content = record.error?.takeIf { record.status == MediaCreationStatus.FAILED }
                ?: record.prompt.ifBlank { record.modelId }
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_STATUS
            contentIntent = MediaCreationForegroundService.sessionPendingIntent(context, record.sessionId.toString())
        }
    }

    private suspend fun <T> inAppScope(block: suspend () -> T): T = appScope.async { block() }.await()

    companion object {
        private const val OUTPUT_PREFIX = "out_"
        private const val DOWNLOAD_ATTEMPTS = 3
        private const val MAX_QUERY_FAILURES = 20
        private val POLL_INTERVAL = 5.seconds
        private val MAX_POLL_INTERVAL = 60.seconds
        private val MAX_WAIT = 24.hours

        private fun pollDelay(failures: Int): Duration =
            (POLL_INTERVAL * (1 shl failures.coerceIn(0, 4))).coerceAtMost(MAX_POLL_INTERVAL)
    }
}

private fun MediaGenerationStatus.toCreationStatus(current: MediaCreationStatus): MediaCreationStatus = when (this) {
    MediaGenerationStatus.QUEUED -> MediaCreationStatus.QUEUED
    MediaGenerationStatus.RUNNING -> MediaCreationStatus.RUNNING
    else -> current.takeIf { it == MediaCreationStatus.QUEUED } ?: MediaCreationStatus.RUNNING
}

private fun MediaGenerationTask.failureMessage(context: Context): String {
    val reason = error?.let { error -> listOfNotNull(error.code, error.message).joinToString(":") }
    return reason?.takeIf(String::isNotBlank) ?: when (status) {
        MediaGenerationStatus.CANCELLED -> context.getString(R.string.media_creation_error_cancelled_remotely)
        MediaGenerationStatus.EXPIRED -> context.getString(R.string.media_creation_error_expired)
        else -> context.getString(R.string.media_creation_status_failed)
    }
}

private fun Throwable.isRetryable(): Boolean =
    this !is MediaGenerationApiException || statusCode !in 400..499 || statusCode == 408 || statusCode == 429

private fun Throwable.toDisplayMessage(): String = when (this) {
    is MediaGenerationApiException -> listOfNotNull("HTTP $statusCode", code, message).joinToString(" · ")
    else -> message?.takeIf(String::isNotBlank) ?: javaClass.simpleName
}

private fun MediaGenerationOutput.fileExtension(): String = when (mimeType.lowercase()) {
    "image/png" -> "png"
    "image/jpeg" -> "jpg"
    "image/webp" -> "webp"
    "image/gif" -> "gif"
    "image/svg+xml" -> "svg"
    "video/mp4" -> "mp4"
    "video/webm" -> "webm"
    "video/quicktime" -> "mov"
    else -> url?.urlExtension() ?: if (mimeType.startsWith("video/")) "mp4" else "png"
}

private fun String.urlExtension(): String? =
    substringBefore('?').substringAfterLast('/').substringAfterLast('.', "")
        .lowercase()
        .takeIf { it.length in 2..4 && it.all(Char::isLetterOrDigit) }
