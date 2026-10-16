package com.mhamad.nexora.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 *
 */
@Entity(
    tableName = "memory_consolidation",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
)
data class MemoryConsolidationEntity(
    @PrimaryKey
    @ColumnInfo("conversation_id")
    val conversationId: String,
    @ColumnInfo("consolidated_at")
    val consolidatedAt: Long,
)
