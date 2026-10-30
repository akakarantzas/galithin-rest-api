package com.galithin.web.sources.database

import com.galithin.web.domain.CursorPage
import com.galithin.web.domain.GuildLeaderboardEntry
import com.galithin.web.domain.GuildCatalog
import com.galithin.web.domain.GuildCapacity
import com.galithin.web.domain.GuildMemberSummary
import com.galithin.web.domain.GuildProfile
import com.galithin.web.domain.GuildProgression
import com.galithin.web.domain.PlayerLeaderboardEntry
import com.galithin.web.domain.PlayerProfile
import com.galithin.web.domain.ProfileReadRepository
import com.galithin.web.domain.ProjectionStatus
import com.galithin.web.sources.objectMapper
import java.nio.charset.StandardCharsets
import java.sql.ResultSet
import java.time.Instant
import java.util.Base64
import javax.sql.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class JdbcProfileReadRepository(
    private val dataSource: DataSource,
    private val catalogVersion: () -> String,
    private val guildCatalog: () -> GuildCatalog,
) : ProfileReadRepository {
    override suspend fun findPlayer(identifier: String): PlayerProfile? =
        withContext(Dispatchers.IO) {
            val uuidLookup = identifier.matches(UUID_PATTERN)
            val sql =
                if (uuidLookup) {
                    "SELECT p.profile_json FROM web_players w JOIN web_profiles p ON p.player_uuid = w.uuid WHERE w.uuid = ?"
                } else {
                    "SELECT p.profile_json FROM web_players w JOIN web_profiles p ON p.player_uuid = w.uuid WHERE w.normalized_name = ?"
                }
            dataSource.connection { connection ->
                connection.prepareStatement(sql).use { statement ->
                    statement.setString(1, if (uuidLookup) identifier.lowercase() else identifier.lowercase())
                    statement.executeQuery().use { result ->
                        if (!result.next()) return@connection null
                        objectMapper.readValue(result.getString(1), PlayerProfile::class.java)
                    }
                }
            }
        }

    override suspend fun findGuild(identifier: String): GuildProfile? =
        withContext(Dispatchers.IO) {
            val uuidLookup = identifier.matches(UUID_PATTERN)
            val guildSql =
                if (uuidLookup) {
                    """
                    SELECT * FROM web_guilds
                    WHERE active = TRUE AND id = ?
                    """.trimIndent()
                } else {
                    """
                    SELECT * FROM web_guilds
                    WHERE active = TRUE AND (normalized_name = ? OR normalized_tag = ?)
                    ORDER BY CASE WHEN normalized_name = ? THEN 0 ELSE 1 END
                    LIMIT 1
                    """.trimIndent()
                }
            dataSource.connection { connection ->
                val normalized = identifier.lowercase()
                val guild = connection.prepareStatement(guildSql).use { statement ->
                    statement.setString(1, normalized)
                    if (!uuidLookup) {
                        statement.setString(2, normalized)
                        statement.setString(3, normalized)
                    }
                    statement.executeQuery().use { result ->
                        if (!result.next()) return@connection null
                        GuildRow(
                            id = result.getString("id"),
                            name = result.getString("name"),
                            tag = result.getString("tag"),
                            level = result.getInt("level"),
                            currentExperience = result.getLong("current_experience"),
                            totalExperience = result.getLong("total_experience"),
                            ownerUuid = result.getString("owner_uuid"),
                            memberCount = result.getInt("member_count"),
                            memberLimitBonus = result.getInt("member_limit_bonus"),
                            claimCount = result.getInt("claim_count"),
                            claimLimitBonus = result.getInt("claim_limit_bonus"),
                            projectedAt = result.getTimestamp("projected_at").toInstant().toString(),
                        )
                    }
                }
                val members = connection.prepareStatement(
                    """
                    SELECT m.player_uuid, m.fallback_name, m.role, m.total_contribution,
                           p.profile_json
                    FROM web_guild_members m
                    LEFT JOIN web_profiles p ON p.player_uuid = m.player_uuid
                    WHERE m.guild_id = ?
                    ORDER BY CASE m.role WHEN 'owner' THEN 0 WHEN 'mod' THEN 1 ELSE 2 END,
                             m.total_contribution DESC, LOWER(COALESCE(m.fallback_name, m.player_uuid)),
                             m.player_uuid
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, guild.id)
                    statement.executeQuery().use { result -> buildList {
                        while (result.next()) {
                            val profile = result.getString("profile_json")?.let {
                                objectMapper.readValue(it, PlayerProfile::class.java)
                            }
                            add(
                                GuildMemberSummary(
                                    uuid = result.getString("player_uuid"),
                                    name = profile?.name ?: result.getString("fallback_name"),
                                    role = result.getString("role"),
                                    level = profile?.level,
                                    rank = profile?.rank,
                                    badges = profile?.badges.orEmpty(),
                                    lastSeenAt = profile?.statistics?.lastJoinedAt,
                                    totalContribution = result.getLong("total_contribution"),
                                    profileAvailable = profile != null,
                                )
                            )
                        }
                    } }
                }
                val content = guildCatalog()
                val requiredExperience = content.requiredExperience(guild.level)
                GuildProfile(
                    id = guild.id,
                    name = guild.name,
                    tag = guild.tag,
                    ownerUuid = guild.ownerUuid,
                    progression = GuildProgression(
                        level = guild.level,
                        currentExperience = guild.currentExperience,
                        requiredExperience = requiredExperience,
                        experienceToNextLevel = (requiredExperience - guild.currentExperience).coerceAtLeast(0),
                        totalExperience = guild.totalExperience,
                        maximumLevel = content.maximumLevel,
                    ),
                    memberCapacity = GuildCapacity(
                        guild.memberCount,
                        content.memberLimit(guild.level, guild.memberLimitBonus),
                    ),
                    claimCapacity = GuildCapacity(
                        guild.claimCount,
                        content.claimLimit(guild.level, guild.claimLimitBonus),
                    ),
                    members = members,
                    projectedAt = guild.projectedAt,
                )
            }
        }

    override suspend fun playerLevelLeaderboard(
        limit: Int,
        cursor: String?,
        search: String?,
        around: Long?,
    ): CursorPage<PlayerLeaderboardEntry> = withContext(Dispatchers.IO) {
        val decoded = cursor?.let(::decodePlayerCursor)
        val filter = search.toLeaderboardSearch()
        val aroundStart = around?.let { (it - limit / 2).coerceAtLeast(1) }
        val conditions = buildList {
            filter?.position?.let { add("position = ?") }
            filter?.normalizedName?.let { add("LOCATE(?, normalized_name) > 0") }
            aroundStart?.let { add("position >= ?") }
            if (decoded != null) {
                add(
                    """
                    (level < ? OR (level = ? AND experience < ?)
                        OR (level = ? AND experience = ? AND uuid > ?))
                    """.trimIndent(),
                )
            }
        }
        val where = conditions.takeIf { it.isNotEmpty() }?.joinToString(" AND ", prefix = "WHERE ").orEmpty()
        val sql =
            """
            SELECT position, uuid, level, experience, profile_json, projected_at
            FROM (
                SELECT ROW_NUMBER() OVER (ORDER BY level DESC, experience DESC, uuid ASC) AS position,
                       w.uuid, w.normalized_name, w.level, w.experience,
                       p.profile_json, p.projected_at
                FROM web_players w JOIN web_profiles p ON p.player_uuid = w.uuid
                WHERE w.eligible = TRUE
            ) ranked
            $where
            ORDER BY level DESC, experience DESC, uuid ASC
            LIMIT ?
            """.trimIndent()
        val entries = dataSource.connection { connection ->
            connection.prepareStatement(sql).use { statement ->
                var index = 1
                filter?.position?.let { statement.setLong(index++, it) }
                filter?.normalizedName?.let { statement.setString(index++, it) }
                aroundStart?.let { statement.setLong(index++, it) }
                if (decoded != null) {
                    statement.setInt(index++, decoded.level)
                    statement.setInt(index++, decoded.level)
                    statement.setLong(index++, decoded.experience)
                    statement.setInt(index++, decoded.level)
                    statement.setLong(index++, decoded.experience)
                    statement.setString(index++, decoded.uuid)
                }
                statement.setInt(index, limit + 1)
                statement.executeQuery().use { result -> buildList {
                    while (result.next()) add(result.toPlayerLeaderboardEntry())
                } }
            }
        }
        val page = entries.take(limit)
        CursorPage(
            page,
            if (entries.size > limit) page.lastOrNull()?.let(::encodePlayerCursor) else null,
        )
    }

    override suspend fun guildLevelLeaderboard(
        limit: Int,
        cursor: String?,
        search: String?,
        around: Long?,
    ): CursorPage<GuildLeaderboardEntry> = withContext(Dispatchers.IO) {
        val decoded = cursor?.let(::decodeGuildCursor)
        val filter = search.toLeaderboardSearch()
        val aroundStart = around?.let { (it - limit / 2).coerceAtLeast(1) }
        val conditions = buildList {
            filter?.position?.let { add("position = ?") }
            filter?.normalizedName?.let {
                add(
                    """
                    (LOCATE(?, normalized_name) > 0 OR LOCATE(?, normalized_tag) > 0
                        OR EXISTS (
                            SELECT 1
                            FROM web_guild_members member
                            JOIN web_players player ON player.uuid = member.player_uuid
                            WHERE member.guild_id = ranked.id AND player.normalized_name = ?
                        ))
                    """.trimIndent(),
                )
            }
            aroundStart?.let { add("position >= ?") }
            if (decoded != null) {
                add(
                    """
                    (level < ? OR (level = ? AND total_experience < ?)
                        OR (level = ? AND total_experience = ? AND id > ?))
                    """.trimIndent(),
                )
            }
        }
        val where = conditions.takeIf { it.isNotEmpty() }?.joinToString(" AND ", prefix = "WHERE ").orEmpty()
        val sql =
            """
            SELECT position, id, name, tag, level, total_experience, owner_uuid,
                   member_count, projected_at
            FROM (
                SELECT ROW_NUMBER() OVER (ORDER BY level DESC, total_experience DESC, id ASC) AS position,
                       id, name, normalized_name, tag, normalized_tag, level, total_experience,
                       owner_uuid, member_count, projected_at
                FROM web_guilds WHERE active = TRUE
            ) ranked
            $where
            ORDER BY level DESC, total_experience DESC, id ASC
            LIMIT ?
            """.trimIndent()
        val entries = dataSource.connection { connection ->
            connection.prepareStatement(sql).use { statement ->
                var index = 1
                filter?.position?.let { statement.setLong(index++, it) }
                filter?.normalizedName?.let {
                    statement.setString(index++, it)
                    statement.setString(index++, it)
                    statement.setString(index++, it)
                }
                aroundStart?.let { statement.setLong(index++, it) }
                if (decoded != null) {
                    statement.setInt(index++, decoded.level)
                    statement.setInt(index++, decoded.level)
                    statement.setLong(index++, decoded.totalExperience)
                    statement.setInt(index++, decoded.level)
                    statement.setLong(index++, decoded.totalExperience)
                    statement.setString(index++, decoded.id)
                }
                statement.setInt(index, limit + 1)
                statement.executeQuery().use { result -> buildList {
                    while (result.next()) {
                        add(
                            GuildLeaderboardEntry(
                                position = result.getLong("position"),
                                id = result.getString("id"),
                                name = result.getString("name"),
                                tag = result.getString("tag"),
                                level = result.getInt("level"),
                                totalExperience = result.getLong("total_experience"),
                                ownerUuid = result.getString("owner_uuid"),
                                memberCount = result.getInt("member_count"),
                                projectedAt = result.getTimestamp("projected_at").toInstant().toString(),
                            )
                        )
                    }
                } }
            }
        }
        val page = entries.take(limit)
        CursorPage(
            page,
            if (entries.size > limit) page.lastOrNull()?.let(::encodeGuildCursor) else null,
        )
    }

    override suspend fun status(): ProjectionStatus = withContext(Dispatchers.IO) {
        val sql =
            """
            SELECT COUNT(*) AS player_count, MAX(projected_at) AS projected_at,
                   MAX(source_saved_at) AS source_saved_at, MAX(stale) AS stale,
                   (SELECT COALESCE(MAX(stale), FALSE) FROM web_projection_state) AS source_stale,
                   (SELECT last_error FROM web_projection_state ORDER BY source_name LIMIT 1) AS last_error
            FROM web_profiles
            """.trimIndent()
        dataSource.connection { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.executeQuery().use { result ->
                    result.next()
                    val count = result.getLong("player_count")
                    ProjectionStatus(
                        ready = count > 0,
                        stale = result.getBoolean("stale") || result.getBoolean("source_stale"),
                        catalogVersion = catalogVersion(),
                        sourceSavedAt = result.getTimestamp("source_saved_at")?.toInstant()?.toString(),
                        projectedAt = result.getTimestamp("projected_at")?.toInstant()?.toString()
                            ?: Instant.EPOCH.toString(),
                        detail = if (count > 0) result.getString("last_error")
                            else "The initial projection has not completed",
                    )
                }
            }
        }
    }

    private fun ResultSet.toPlayerLeaderboardEntry(): PlayerLeaderboardEntry {
        val profile = objectMapper.readValue(getString("profile_json"), PlayerProfile::class.java)
        return PlayerLeaderboardEntry(
            position = getLong("position"),
            uuid = profile.uuid,
            name = profile.name,
            level = profile.level,
            experience = profile.experience,
            rank = profile.rank,
            badges = profile.badges,
            guild = profile.guild,
            projectedAt = getTimestamp("projected_at").toInstant().toString(),
        )
    }

    private data class PlayerCursor(val level: Int, val experience: Long, val uuid: String)

    private data class GuildCursor(val level: Int, val totalExperience: Long, val id: String)

    private data class LeaderboardSearch(val position: Long? = null, val normalizedName: String? = null)

    private data class GuildRow(
        val id: String,
        val name: String,
        val tag: String?,
        val level: Int,
        val currentExperience: Long,
        val totalExperience: Long,
        val ownerUuid: String,
        val memberCount: Int,
        val memberLimitBonus: Int,
        val claimCount: Int,
        val claimLimitBonus: Int,
        val projectedAt: String,
    )

    private fun encodePlayerCursor(entry: PlayerLeaderboardEntry): String =
        encode("${entry.level}:${entry.experience}:${entry.uuid}")

    private fun decodePlayerCursor(cursor: String): PlayerCursor {
        val parts = decode(cursor).split(':', limit = 3)
        require(parts.size == 3) { "Invalid cursor" }
        return PlayerCursor(parts[0].toInt(), parts[1].toLong(), parts[2])
    }

    private fun encodeGuildCursor(entry: GuildLeaderboardEntry): String =
        encode("${entry.level}:${entry.totalExperience}:${entry.id}")

    private fun decodeGuildCursor(cursor: String): GuildCursor {
        val parts = decode(cursor).split(':', limit = 3)
        require(parts.size == 3) { "Invalid cursor" }
        return GuildCursor(parts[0].toInt(), parts[1].toLong(), parts[2])
    }

    private fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decode(value: String): String =
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)

    private fun String?.toLeaderboardSearch(): LeaderboardSearch? {
        val value = this?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return if (value.matches(POSITION_PATTERN)) {
            LeaderboardSearch(position = value.removePrefix("#").toLongOrNull() ?: -1)
        } else {
            LeaderboardSearch(normalizedName = value.lowercase())
        }
    }

    companion object {
        private val POSITION_PATTERN = Regex("^#?[0-9]+$")
        private val UUID_PATTERN =
            Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}
