package com.mhamad.nexora.data.repository

import com.mhamad.nexora.data.db.entity.FavoriteEntity
import com.mhamad.nexora.data.model.FavoriteType

interface FavoriteAdapter<T> {
    val type: FavoriteType

    fun buildRefKey(target: T): String

    fun buildFavoriteEntity(
        target: T,
        existing: FavoriteEntity? = null,
        now: Long = System.currentTimeMillis()
    ): FavoriteEntity
}
