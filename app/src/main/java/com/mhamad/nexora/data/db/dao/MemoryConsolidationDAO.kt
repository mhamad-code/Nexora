package com.mhamad.nexora.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.mhamad.nexora.data.db.entity.MemoryConsolidationEntity

@Dao
interface MemoryConsolidationDAO {
    @Query("SELECT consolidated_at FROM memory_consolidation WHERE conversation_id = :conversationId")
    suspend fun getConsolidatedAt(conversationId: String): Long?

    @Upsert
    suspend fun upsert(entity: MemoryConsolidationEntity)

    @Query(
        "SELECT c.id FROM conversationentity AS c " +
            "LEFT JOIN memory_consolidation AS m ON m.conversation_id = c.id " +
            "WHERE c.assistant_id IN (:assistantIds) " +
            "AND c.update_at >= :since " +
            "AND c.update_at > COALESCE(m.consolidated_at, 0) " +
            "ORDER BY c.update_at DESC LIMIT :limit"
    )
    suspend fun getUpdatedConversationIds(assistantIds: List<String>, since: Long, limit: Int): List<String>

    @Query(
        "SELECT DISTINCT c.assistant_id FROM conversationentity AS c " +
            "LEFT JOIN memory_consolidation AS m ON m.conversation_id = c.id " +
            "WHERE c.assistant_id IN (:assistantIds) " +
            "AND c.update_at >= :since " +
            "AND c.update_at > COALESCE(m.consolidated_at, 0)"
    )
    suspend fun getAssistantIdsWithUpdatedConversations(assistantIds: List<String>, since: Long): List<String>
}
