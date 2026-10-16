package com.mhamad.nexora.data.repository

import android.content.Context
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.mediagen.model.MediaKind
import com.mhamad.nexora.data.db.AppDatabase
import com.mhamad.nexora.data.db.dao.MediaCreationDAO
import com.mhamad.nexora.data.db.entity.MediaCreationNodeEntity
import com.mhamad.nexora.data.db.entity.MediaCreationRecordEntity
import com.mhamad.nexora.data.db.entity.MediaCreationSessionEntity
import com.mhamad.nexora.data.files.FileFolders
import com.mhamad.nexora.data.model.MediaCreationDraft
import com.mhamad.nexora.data.model.MediaCreationNode
import com.mhamad.nexora.data.model.MediaCreationOutput
import com.mhamad.nexora.data.model.MediaCreationParams
import com.mhamad.nexora.data.model.MediaCreationRecord
import com.mhamad.nexora.data.model.MediaCreationSession
import com.mhamad.nexora.data.model.MediaCreationStatus
import java.io.File
import java.time.Instant
import kotlin.uuid.Uuid

/**
 *
 *
 */
class MediaCreationRepository(
    private val context: Context,
    private val database: AppDatabase,
    private val dao: MediaCreationDAO,
    private val json: Json,
) {
    private val activeStatuses = MediaCreationStatus.ACTIVE.map { it.name }


    fun resolve(path: String): File = File(context.filesDir, path)

    fun relativePath(file: File): String = file.relativeTo(context.filesDir).path

    fun sessionDir(sessionId: Uuid): File = File(context.filesDir, "${FileFolders.MEDIA_CREATION}/$sessionId")

    fun recordDir(sessionId: Uuid, recordId: Uuid): File = File(sessionDir(sessionId), recordId.toString())

    fun draftDir(sessionId: Uuid): File = File(sessionDir(sessionId), DRAFT_DIR)


    fun observeSessions(): Flow<List<MediaCreationSession>> =
        dao.observeSessions(activeStatuses, MediaCreationStatus.SUCCEEDED.name).map { sessions ->
            sessions.map {
                it.session.toSession().copy(
                    nodeCount = it.nodeCount,
                    activeCount = it.activeCount,
                    cover = it.coverOutputs
                        ?.let { outputs -> decodeOrDefault<List<MediaCreationOutput>>(outputs, emptyList()) }
                        ?.firstOrNull(),
                )
            }
        }

    fun observeSession(id: Uuid): Flow<MediaCreationSession?> =
        dao.observeSession(id.toString()).map { it?.toSession() }

    suspend fun getSession(id: Uuid): MediaCreationSession? = dao.getSession(id.toString())?.toSession()

    suspend fun getLatestSession(): MediaCreationSession? = dao.getLatestSession()?.toSession()

    /**
     */
    suspend fun getOrCreateEmptySession(draft: MediaCreationDraft): MediaCreationSession = database.withTransaction {
        dao.getEmptySession()?.toSession() ?: MediaCreationSession(draft = draft).also {
            dao.insertSession(it.toEntity())
        }
    }

    suspend fun newSession(): MediaCreationSession =
        getOrCreateEmptySession(MediaCreationDraft(modelId = getLatestSession()?.draft?.modelId))

    suspend fun renameSession(id: Uuid, title: String) = dao.renameSession(id.toString(), title)

    suspend fun saveDraft(id: Uuid, draft: MediaCreationDraft) =
        dao.updateDraft(id.toString(), json.encodeToString(draft))

    suspend fun updateSession(id: Uuid, transform: (MediaCreationSession) -> MediaCreationSession) {
        database.withTransaction {
            val session = dao.getSession(id.toString())?.toSession() ?: return@withTransaction
            dao.updateSession(transform(session).toEntity())
        }
    }

    suspend fun deleteSession(id: Uuid) {
        dao.deleteSession(id.toString())
        withContext(Dispatchers.IO) { sessionDir(id).deleteRecursively() }
    }


    /**
     */
    fun observeNodes(sessionId: Uuid): Flow<PagingData<MediaCreationNode>> =
        Pager(PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = true)) {
            dao.nodePagingSource(sessionId.toString())
        }.flow.map { page ->
            page.map { MediaCreationNode(it.record.toRecord(), it.versionIndex, it.versionCount) }
        }

    suspend fun getNodePosition(id: Uuid): Int = dao.getNodePosition(id.toString())

    suspend fun hasNode(id: Uuid): Boolean = dao.getNode(id.toString()) != null

    /**
     */
    suspend fun switchVersion(nodeId: Uuid, offset: Int) = database.withTransaction {
        val node = dao.getNode(nodeId.toString()) ?: return@withTransaction
        val versions = dao.getVersionIds(node.id)
        val target = versions.getOrNull(versions.indexOf(node.selectedRecordId) + offset) ?: return@withTransaction
        dao.selectRecord(node.id, target)
    }


    suspend fun getRecord(id: Uuid): MediaCreationRecord? = dao.getRecord(id.toString())?.toRecord()

    suspend fun getRecordsOfSession(sessionId: Uuid): List<MediaCreationRecord> =
        dao.getRecordsOfSession(sessionId.toString()).map { it.toRecord() }

    suspend fun getActiveRecords(): List<MediaCreationRecord> =
        dao.getRecordsByStatus(activeStatuses).map { it.toRecord() }

    fun observeRecentSucceededRecords(limit: Int): Flow<List<MediaCreationRecord>> =
        dao.observeRecentRecords(MediaCreationStatus.SUCCEEDED.name, limit).map { records ->
            records.map { it.toRecord() }
        }

    suspend fun getLatestRecordOfKind(kind: MediaKind): MediaCreationRecord? =
        dao.getLatestRecordOfKind(kind.name)?.toRecord()

    /**
     */
    suspend fun insertRecord(record: MediaCreationRecord) = database.withTransaction {
        val entity = record.toEntity()
        if (dao.getNode(entity.nodeId) == null) {
            dao.insertNode(
                MediaCreationNodeEntity(
                    id = entity.nodeId,
                    sessionId = entity.sessionId,
                    selectedRecordId = entity.id,
                    createAt = entity.createAt,
                )
            )
            dao.insertRecord(entity)
        } else {
            dao.insertRecord(entity)
            dao.selectRecord(entity.nodeId, entity.id)
        }
    }

    /**
     */
    suspend fun updateRecord(
        id: Uuid,
        transform: (MediaCreationRecord) -> MediaCreationRecord,
    ): MediaCreationRecord? = database.withTransaction {
        val current = dao.getRecord(id.toString())?.toRecord() ?: return@withTransaction null
        val updated = transform(current)
        if (updated != current) dao.updateRecord(updated.copy(updateAt = Instant.now()).toEntity())
        updated
    }

    /**
     */
    suspend fun deleteRecord(record: MediaCreationRecord) {
        database.withTransaction {
            val id = record.id.toString()
            val nodeId = record.nodeId.toString()
            val versions = dao.getVersionIds(nodeId)
            dao.deleteRecord(id)
            val node = dao.getNode(nodeId) ?: return@withTransaction
            val remaining = versions - id
            when {
                remaining.isEmpty() -> dao.deleteNode(nodeId)
                node.selectedRecordId == id ->
                    dao.selectRecord(nodeId, remaining[(versions.indexOf(id) - 1).coerceIn(remaining.indices)])
            }
        }
        withContext(Dispatchers.IO) { recordDir(record.sessionId, record.id).deleteRecursively() }
    }


    private fun MediaCreationSessionEntity.toSession() = MediaCreationSession(
        id = Uuid.parse(id),
        title = title,
        draft = decodeOrDefault(draft, MediaCreationDraft()),
        createAt = Instant.ofEpochMilli(createAt),
        updateAt = Instant.ofEpochMilli(updateAt),
    )

    private fun MediaCreationSession.toEntity() = MediaCreationSessionEntity(
        id = id.toString(),
        title = title,
        draft = json.encodeToString(draft),
        createAt = createAt.toEpochMilli(),
        updateAt = updateAt.toEpochMilli(),
    )

    private fun MediaCreationRecordEntity.toRecord() = MediaCreationRecord(
        id = Uuid.parse(id),
        sessionId = Uuid.parse(sessionId),
        nodeId = Uuid.parse(nodeId),
        providerId = Uuid.parse(providerId),
        providerName = providerName,
        modelId = modelId,
        kind = MediaKind.valueOf(kind),
        prompt = prompt,
        params = decodeOrDefault(params, MediaCreationParams()),
        inputs = decodeOrDefault(inputs, emptyList()),
        status = MediaCreationStatus.valueOf(status),
        taskId = taskId,
        error = error,
        outputs = decodeOrDefault(outputs, emptyList()),
        createAt = Instant.ofEpochMilli(createAt),
        updateAt = Instant.ofEpochMilli(updateAt),
    )

    private fun MediaCreationRecord.toEntity() = MediaCreationRecordEntity(
        id = id.toString(),
        sessionId = sessionId.toString(),
        nodeId = nodeId.toString(),
        providerId = providerId.toString(),
        providerName = providerName,
        modelId = modelId,
        kind = kind.name,
        prompt = prompt,
        params = json.encodeToString(params),
        inputs = json.encodeToString(inputs),
        status = status.name,
        taskId = taskId,
        error = error,
        outputs = json.encodeToString(outputs),
        createAt = createAt.toEpochMilli(),
        updateAt = updateAt.toEpochMilli(),
    )

    private inline fun <reified T> decodeOrDefault(text: String, default: T): T =
        runCatching { json.decodeFromString<T>(text) }.getOrDefault(default)

    companion object {
        private const val DRAFT_DIR = "draft"
        private const val PAGE_SIZE = 20
    }
}
