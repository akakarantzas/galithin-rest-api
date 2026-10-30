package com.galithin.web.domain

import java.nio.file.Path

interface ProfileReadRepository {
    suspend fun findPlayer(identifier: String): PlayerProfile?

    suspend fun findGuild(identifier: String): GuildProfile?

    suspend fun playerLevelLeaderboard(
        limit: Int,
        cursor: String?,
        search: String? = null,
        around: Long? = null,
    ): CursorPage<PlayerLeaderboardEntry>

    suspend fun guildLevelLeaderboard(
        limit: Int,
        cursor: String?,
        search: String? = null,
        around: Long? = null,
    ): CursorPage<GuildLeaderboardEntry>

    suspend fun status(): ProjectionStatus
}

interface CatalogRepository {
    fun version(): String

    fun entries(kind: CatalogKind): List<CatalogEntry>

    fun entry(kind: CatalogKind, id: String): CatalogEntry?

    fun guilds(): GuildCatalog

    fun resolveAsset(version: String, relativePath: String): Path?

    fun ready(): Boolean
}

interface ProjectionRunner : AutoCloseable {
    fun start()

    override fun close()
}
