package com.mhamad.nexora.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 */
@Entity(tableName = "media_creation_session")
data class MediaCreationSessionEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("title")
    val title: String,
    @ColumnInfo("draft")
    val draft: String,
    @ColumnInfo("create_at")
    val createAt: Long,
    @ColumnInfo("update_at")
    val updateAt: Long,
)

/**
 */
@Entity(
    tableName = "media_creation_node",
    foreignKeys = [
        ForeignKey(
            entity = MediaCreationSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("session_id")]
)
data class MediaCreationNodeEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("session_id")
    val sessionId: String,
    @ColumnInfo("selected_record_id")
    val selectedRecordId: String,
    @ColumnInfo("create_at")
    val createAt: Long,
)

/**
 */
@Entity(
    tableName = "media_creation_record",
    foreignKeys = [
        ForeignKey(
            entity = MediaCreationSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = MediaCreationNodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["node_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("session_id"), Index("node_id")]
)
data class MediaCreationRecordEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("session_id")
    val sessionId: String,
    @ColumnInfo("node_id")
    val nodeId: String,
    @ColumnInfo("provider_id")
    val providerId: String,
    @ColumnInfo("provider_name")
    val providerName: String,
    @ColumnInfo("model_id")
    val modelId: String,
    @ColumnInfo("kind")
    val kind: String,
    @ColumnInfo("prompt")
    val prompt: String,
    @ColumnInfo("params")
    val params: String,
    @ColumnInfo("inputs")
    val inputs: String,
    @ColumnInfo("status")
    val status: String,
    @ColumnInfo("task_id")
    val taskId: String?,
    @ColumnInfo("error")
    val error: String?,
    @ColumnInfo("outputs")
    val outputs: String,
    @ColumnInfo("create_at")
    val createAt: Long,
    @ColumnInfo("update_at")
    val updateAt: Long,
)

data class MediaCreationSessionWithStats(
    @Embedded
    val session: MediaCreationSessionEntity,
    @ColumnInfo("node_count")
    val nodeCount: Int,
    @ColumnInfo("active_count")
    val activeCount: Int,
    @ColumnInfo("cover_outputs")
    val coverOutputs: String?,
)

/**
 */
data class MediaCreationRecordWithVersion(
    @Embedded
    val record: MediaCreationRecordEntity,
    @ColumnInfo("version_index")
    val versionIndex: Int,
    @ColumnInfo("version_count")
    val versionCount: Int,
)
