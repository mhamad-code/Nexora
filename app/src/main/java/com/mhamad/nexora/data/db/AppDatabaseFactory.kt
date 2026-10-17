package com.mhamad.nexora.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mhamad.nexora.data.db.migrations.Migration_6_7
import com.mhamad.nexora.data.db.migrations.Migration_11_12
import com.mhamad.nexora.data.db.migrations.Migration_13_14
import com.mhamad.nexora.data.db.migrations.Migration_14_15
import com.mhamad.nexora.data.db.migrations.Migration_15_16
import com.mhamad.nexora.data.db.migrations.Migration_27_28

/** Shared schema, migrations and extensions for the app and staged backup validation. */
internal object AppDatabaseFactory {
    fun create(context: Context, name: String = SQLiteConfiguration.DATABASE_NAME): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(
                Migration_6_7,
                Migration_11_12,
                Migration_13_14,
                Migration_14_15,
                Migration_15_16,
                Migration_27_28,
            )
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    ensureMessageFts(db)
                }
            })
            .openHelperFactory(SQLiteConfiguration.openHelperFactory())
            .build()

    private fun ensureMessageFts(db: SupportSQLiteDatabase) {
        val existingSql = db.query(
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'message_fts'"
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        if (existingSql?.contains("tokenize = 'simple'") == true ||
            existingSql?.contains("tokenize='simple'") == true
        ) {
            db.beginTransaction()
            try {
                db.execSQL(
                    """
                    CREATE VIRTUAL TABLE message_fts_rebuild USING fts5(
                        text,
                        node_id UNINDEXED,
                        message_id UNINDEXED,
                        conversation_id UNINDEXED,
                        title UNINDEXED,
                        update_at UNINDEXED,
                        tokenize = 'unicode61'
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO message_fts_rebuild(text, node_id, message_id, conversation_id, title, update_at)
                    SELECT text, node_id, message_id, conversation_id, title, update_at FROM message_fts
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE message_fts")
                db.execSQL("ALTER TABLE message_fts_rebuild RENAME TO message_fts")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } else if (existingSql == null) {
            db.execSQL(
                """
                CREATE VIRTUAL TABLE message_fts USING fts5(
                    text,
                    node_id UNINDEXED,
                    message_id UNINDEXED,
                    conversation_id UNINDEXED,
                    title UNINDEXED,
                    update_at UNINDEXED,
                    tokenize = 'unicode61'
                )
                """.trimIndent()
            )
        }
    }
}
