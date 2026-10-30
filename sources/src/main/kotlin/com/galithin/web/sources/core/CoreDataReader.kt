package com.galithin.web.sources.core

import com.galithin.web.domain.GuildLeaderboardEntry
import com.galithin.web.domain.GuildSummary
import com.galithin.web.domain.RankPresentation
import com.galithin.web.sources.database.connection
import com.galithin.web.sources.luckperms.LuckPermsIdentityReader
import com.galithin.web.sources.text.RichTextRenderer
import java.nio.ByteBuffer
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

data class WebIdentity(
    val uuid: String,
    val username: String,
    val firstJoinedAt: String?,
    val lastJoinedAt: String?,
    val rank: RankPresentation,
    val banned: Boolean,
)

data class CoreDataSnapshot(
    val identities: Map<String, WebIdentity>,
    val playerGuilds: Map<String, GuildSummary>,
    val guilds: List<CoreGuild>,
) {
    companion object {
        fun empty() = CoreDataSnapshot(emptyMap(), emptyMap(), emptyList())
    }
}

data class CoreGuild(
    val leaderboard: GuildLeaderboardEntry,
    val currentExperience: Long,
    val memberLimitBonus: Int,
    val claimCount: Int,
    val claimLimitBonus: Int,
    val members: List<CoreGuildMember>,
)

data class CoreGuildMember(
    val playerUuid: String,
    val fallbackName: String?,
    val role: String,
    val totalContribution: Long,
)

class CoreDataReader(
    private val dataSource: DataSource,
    private val richText: RichTextRenderer,
    private val identities: LuckPermsIdentityReader,
) {
    private val snapshot = AtomicReference(CoreDataSnapshot.empty())

    fun current(): CoreDataSnapshot = snapshot.get()

    fun refresh(): CoreDataSnapshot {
        val identities = identities.refresh()
        val guildData = readGuilds(identities)
        val playerGuilds = readPlayerGuilds(guildData)
        return CoreDataSnapshot(identities, playerGuilds, guildData).also(snapshot::set)
    }

    private fun readGuilds(identities: Map<String, WebIdentity>): List<CoreGuild> {
        val sql =
            """
            SELECT g.guild_id, g.owner_id, n.display_name, t.display_tag,
                   COALESCE(p.level, 1) AS level,
                   COALESCE(p.experience, 0) AS current_experience,
                   COALESCE(p.total_experience, 0) AS total_experience,
                   COALESCE(p.member_limit_bonus, 0) AS member_limit_bonus,
                   COALESCE(p.claim_limit_bonus, 0) AS claim_limit_bonus,
                   COALESCE(c.claim_count, 0) AS claim_count
            FROM guild_data g
            JOIN guild_name_index n ON n.guild_id = g.guild_id
            LEFT JOIN guild_tag_index t ON t.guild_id = g.guild_id
            LEFT JOIN guild_progression p ON p.guild_id = g.guild_id
            LEFT JOIN (
                SELECT guild_id, COUNT(*) AS claim_count
                FROM guild_claims
                GROUP BY guild_id
            ) c ON c.guild_id = g.guild_id
            """.trimIndent()
        val rows = dataSource.connection { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.executeQuery().use { result -> buildList {
                    while (result.next()) {
                        add(
                            CoreGuildRow(
                                id = result.uuid("guild_id"),
                                ownerUuid = result.uuid("owner_id"),
                                name = result.getString("display_name"),
                                tag = result.getString("display_tag"),
                                level = result.getInt("level"),
                                currentExperience = result.getLong("current_experience"),
                                totalExperience = result.getLong("total_experience"),
                                memberLimitBonus = result.getInt("member_limit_bonus"),
                                claimLimitBonus = result.getInt("claim_limit_bonus"),
                                claimCount = result.getInt("claim_count"),
                            )
                        )
                    }
                } }
            }
        }
        val members = readGuildMembers(identities)
        val projectedAt = Instant.now().toString()
        return rows.map { row ->
            val guildMembers = members[row.id].orEmpty().sortedWith(
                compareBy<CoreGuildMember> { roleOrder(it.role) }
                    .thenByDescending(CoreGuildMember::totalContribution)
                    .thenBy { it.fallbackName?.lowercase() }
                    .thenBy(CoreGuildMember::playerUuid),
            )
            CoreGuild(
                leaderboard = GuildLeaderboardEntry(
                    position = 0,
                    id = row.id,
                    name = row.name,
                    tag = row.tag,
                    level = row.level,
                    totalExperience = row.totalExperience,
                    ownerUuid = row.ownerUuid,
                    memberCount = guildMembers.size,
                    projectedAt = projectedAt,
                ),
                currentExperience = row.currentExperience,
                memberLimitBonus = row.memberLimitBonus,
                claimCount = row.claimCount,
                claimLimitBonus = row.claimLimitBonus,
                members = guildMembers,
            )
        }
    }

    private fun readGuildMembers(identities: Map<String, WebIdentity>): Map<String, List<CoreGuildMember>> {
        val sql =
            """
            SELECT membership.guild_id, membership.player_id, membership.role,
                   COALESCE(contribution.total_experience, 0) AS total_contribution
            FROM (
                SELECT guild_id, owner_id AS player_id, 'owner' AS role FROM guild_data
                UNION ALL
                SELECT guild_id, player_id, role FROM guild_members
            ) membership
            LEFT JOIN guild_member_contributions contribution
              ON contribution.guild_id = membership.guild_id
             AND contribution.player_id = membership.player_id
            """.trimIndent()
        return dataSource.connection { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.executeQuery().use { rows -> buildMap<String, MutableList<CoreGuildMember>> {
                    while (rows.next()) {
                        val guildId = rows.uuid("guild_id")
                        val playerUuid = rows.uuid("player_id")
                        getOrPut(guildId, ::mutableListOf).add(
                            CoreGuildMember(
                                playerUuid = playerUuid,
                                fallbackName = identities[playerUuid]?.username,
                                role = rows.getString("role")?.lowercase()?.takeIf { it in GUILD_ROLES }
                                    ?: "member",
                                totalContribution = rows.getLong("total_contribution"),
                            )
                        )
                    }
                } }
            }
        }
    }

    private fun readPlayerGuilds(guilds: List<CoreGuild>): Map<String, GuildSummary> = buildMap {
        guilds.forEach { guild ->
            val summary = GuildSummary(
                id = guild.leaderboard.id,
                name = guild.leaderboard.name,
                tag = guild.leaderboard.tag,
                level = guild.leaderboard.level,
            )
            guild.members.forEach { member -> put(member.playerUuid, summary) }
        }
    }

    private data class CoreGuildRow(
        val id: String,
        val ownerUuid: String,
        val name: String,
        val tag: String?,
        val level: Int,
        val currentExperience: Long,
        val totalExperience: Long,
        val memberLimitBonus: Int,
        val claimLimitBonus: Int,
        val claimCount: Int,
    )

    private fun roleOrder(role: String): Int = when (role) {
        "owner" -> 0
        "mod" -> 1
        else -> 2
    }

    private fun ResultSet.uuid(name: String): String =
        when (val value = getObject(name)) {
            is UUID -> value.toString()
            is ByteArray -> {
                require(value.size == 16) { "Invalid binary UUID in $name" }
                val buffer = ByteBuffer.wrap(value)
                UUID(buffer.long, buffer.long).toString()
            }
            else -> value.toString()
        }.lowercase()

    companion object {
        private val GUILD_ROLES = setOf("owner", "mod", "member")
    }
}
