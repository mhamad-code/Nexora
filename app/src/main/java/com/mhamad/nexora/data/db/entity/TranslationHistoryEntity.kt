package com.mhamad.nexora.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 */
@Entity(
    tableName = "translation_history",
    indices = [Index(value = ["create_at"])]
)
data class TranslationHistoryEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("source_text")
    val sourceText: String,
    @ColumnInfo("translated_text")
    val translatedText: String,
    @ColumnInfo("target_language")
    val targetLanguage: String,
    @ColumnInfo("model_id")
    val modelId: String,
    @ColumnInfo("create_at")
    val createAt: Long,
)
