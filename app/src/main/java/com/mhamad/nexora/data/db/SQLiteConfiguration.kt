package com.mhamad.nexora.data.db

import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory

/** Use the bundled SQLite implementation so FTS5 is available consistently. */
internal object SQLiteConfiguration {
    const val DATABASE_NAME = "rikka_hub"

    fun openHelperFactory() = RequerySQLiteOpenHelperFactory()
}
