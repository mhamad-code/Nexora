package com.mhamad.nexora.data.remote

import android.util.Log
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.mhamad.nexora.data.datastore.SettingsStore
import com.mhamad.nexora.data.sync.s3.S3Client
import com.mhamad.nexora.data.sync.s3.S3Config
import com.mhamad.nexora.data.sync.s3.S3ListResult
import java.io.File
import java.net.URLConnection
import java.security.MessageDigest
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.toJavaDuration

private const val TAG = "RemoteFileStore"

/**
 *
 */
class RemoteFileStore internal constructor(
    private val scope: CoroutineScope,
    private val config: () -> S3Config,
    private val storage: (S3Config) -> RemoteObjectStorage,
    private val now: () -> Instant = Instant::now,
) {
    constructor(settingsStore: SettingsStore, httpClient: HttpClient, scope: CoroutineScope) : this(
        scope = scope,
        config = { settingsStore.settingsFlow.value.uploadS3Config.withHttpsByDefault() },
        storage = { S3ObjectStorage(S3Client(it, httpClient)) },
    )

    private val mutex = Mutex()
    private val hashes = ConcurrentHashMap<String, FileHash>()

    private var knownConfig: S3Config? = null
    private val knownObjects = HashMap<String, KnownObject>()
    private var lastSweepAt: Instant? = null

    val isConfigured: Boolean
        get() = config().isUsable()

    /**
     */
    suspend fun upload(
        file: File,
        contentType: String = guessContentType(file),
        expires: Duration = DEFAULT_URL_EXPIRES,
    ): Result<RemoteFile> = runCatching {
        require(expires.isPositive() && expires <= RETENTION) { "expires must be between 0 and $RETENTION" }
        val config = config()
        check(config.isUsable()) { "S3 is not configured" }

        val key = objectKey(file)
        val storage = storage(config)
        val sweepDue = mutex.withLock {
            if (config != knownConfig) {
                knownObjects.clear()
                knownConfig = config
            }
            if (!isReusable(storage, key, expires)) {
                storage.put(key, file, contentType)
                knownObjects[key] = KnownObject(lastModified = now(), checkedAt = now())
            }
            val due = lastSweepAt?.let { now() >= it + SWEEP_INTERVAL.toJavaDuration() } ?: true
            if (due) lastSweepAt = now()
            due
        }
        if (sweepDue) {
            scope.launch {
                runCatching { sweep() }.onFailure { Log.w(TAG, "sweep failed", it) }
            }
        }

        RemoteFile(
            key = key,
            url = storage.presign(key, expires),
            expiresAt = now() + expires.toJavaDuration(),
        )
    }.onFailure { if (it is CancellationException) throw it }

    /**
     */
    suspend fun testConnection(): Result<Unit> = runCatching {
        val config = config()
        check(config.isUsable()) { "S3 is not configured" }
        storage(config).list(KEY_PREFIX, null)
        Unit
    }.onFailure { if (it is CancellationException) throw it }

    /**
     */
    internal suspend fun sweep() {
        val config = config()
        if (!config.isUsable()) return
        val storage = storage(config)

        var token: String? = null
        do {
            token = mutex.withLock {
                val page = storage.list(KEY_PREFIX, token)
                val deadline = now() - RETENTION.toJavaDuration()
                page.objects
                    .filter { it.lastModified?.isBefore(deadline) == true }
                    .forEach {
                        storage.delete(it.key)
                        knownObjects.remove(it.key)
                    }
                page.nextContinuationToken.takeIf { page.isTruncated }
            }
        } while (token != null)
    }

    private suspend fun isReusable(storage: RemoteObjectStorage, key: String, expires: Duration): Boolean {
        val now = now()
        val known = knownObjects[key]?.takeIf { now < it.checkedAt + RECHECK_INTERVAL.toJavaDuration() }
        val lastModified = known?.lastModified ?: storage.lastModified(key)
        if (lastModified == null) {
            knownObjects.remove(key)
            return false
        }
        if (known == null) knownObjects[key] = KnownObject(lastModified = lastModified, checkedAt = now)
        return now + expires.toJavaDuration() <= lastModified + RETENTION.toJavaDuration()
    }

    private suspend fun objectKey(file: File): String = withContext(Dispatchers.IO) {
        require(file.isFile) { "File does not exist: ${file.path}" }
        val length = file.length()
        val lastModified = file.lastModified()
        val cached = hashes[file.path]?.takeIf { it.length == length && it.lastModified == lastModified }
        val sha256 = cached?.sha256
            ?: file.sha256Hex().also { hashes[file.path] = FileHash(length, lastModified, it) }
        val extension = file.extension.lowercase().takeIf { ext -> ext.isNotEmpty() && ext.all { it.isLetterOrDigit() } }
        KEY_PREFIX + sha256 + extension?.let { ".$it" }.orEmpty()
    }

    private data class FileHash(val length: Long, val lastModified: Long, val sha256: String)

    private data class KnownObject(val lastModified: Instant, val checkedAt: Instant)

    companion object {
        const val KEY_PREFIX = "rikkahub_uploads/"

        val RETENTION = 7.days
        val DEFAULT_URL_EXPIRES = 24.hours

        private val RECHECK_INTERVAL = 1.hours
        private val SWEEP_INTERVAL = 24.hours
    }
}

data class RemoteFile(
    val key: String,
    val url: String,
    val expiresAt: Instant,
)

/**
 */
internal interface RemoteObjectStorage {
    suspend fun lastModified(key: String): Instant?

    suspend fun put(key: String, file: File, contentType: String)

    suspend fun list(prefix: String, continuationToken: String?): S3ListResult

    suspend fun delete(key: String)

    fun presign(key: String, expires: Duration): String
}

private class S3ObjectStorage(private val client: S3Client) : RemoteObjectStorage {
    override suspend fun lastModified(key: String): Instant? =
        client.headObject(key).getOrNull()?.lastModified?.let {
            runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()
        }

    override suspend fun put(key: String, file: File, contentType: String) =
        client.putObject(key, file, contentType).getOrThrow()

    override suspend fun list(prefix: String, continuationToken: String?): S3ListResult =
        client.listObjects(prefix = prefix, continuationToken = continuationToken).getOrThrow()

    override suspend fun delete(key: String) = client.deleteObject(key).getOrThrow()

    override fun presign(key: String, expires: Duration): String = client.presignGetUrl(key, expires)
}

private fun S3Config.isUsable(): Boolean =
    endpoint.isNotBlank() && accessKeyId.isNotBlank() && secretAccessKey.isNotBlank() && bucket.isNotBlank()

private fun guessContentType(file: File): String =
    URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"

private fun File.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
