package com.galithin.web.sources.database

import com.galithin.web.sources.objectMapper
import java.sql.Timestamp
import java.sql.PreparedStatement
import java.time.Instant
import javax.sql.DataSource

class ReadModelWriter(private val dataSource: DataSource) {
    fun upsertPlayer(player: ProjectedPlayer) {
        val playerSql =
            """
            MERGE INTO web_players (
                uuid, name, normalized_name, level, experience, eligible
            ) KEY (uuid) VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent()
        val profileSql =
            """
            MERGE INTO web_profiles (
                player_uuid, profile_json, source_version_uuid, source_saved_at, projected_at, stale
            ) KEY (player_uuid) VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent()
        dataSource.connection { connection ->
            connection.autoCommit = false
            try {
            connection.prepareStatement(playerSql).use { statement ->
                val profile = player.profile
                statement.setString(1, profile.uuid)
                statement.setString(2, profile.name)
                statement.setString(3, profile.name.lowercase())
                statement.setInt(4, profile.level)
                statement.setLong(5, profile.experience)
                statement.setBoolean(6, player.eligible)
                statement.executeUpdate()
            }
            connection.prepareStatement(profileSql).use { statement ->
                statement.setString(1, player.profile.uuid)
                statement.setString(2, objectMapper.writeValueAsString(player.profile))
                statement.setString(3, player.sourceVersionUuid)
                statement.setTimestamp(4, player.sourceSavedAt?.let { Timestamp.from(Instant.parse(it)) })
                statement.setTimestamp(5, Timestamp.from(Instant.parse(player.projectedAt)))
                statement.setBoolean(6, player.stale)
                statement.executeUpdate()
            }
            connection.commit()
            } catch (error: Exception) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = true
            }
        }
    }

    fun upsertGuild(guild: ProjectedGuild) {
        dataSource.connection { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement(GUILD_SQL).use { statement ->
                    statement.bindGuild(guild)
                    statement.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM web_guild_members WHERE guild_id = ?").use { statement ->
                    statement.setString(1, guild.guild.id)
                    statement.executeUpdate()
                }
                connection.prepareStatement(GUILD_MEMBER_SQL).use { statement ->
                    guild.members.forEach { member ->
                        statement.bindGuildMember(guild, member)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
                connection.commit()
            } catch (error: Exception) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = true
            }
        }
    }

    /** Atomically retires disbanded guilds and refreshes every currently active guild. */
    fun synchronizeGuilds(guilds: Collection<ProjectedGuild>) {
        dataSource.connection { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement("UPDATE web_guilds SET active = FALSE").use { it.executeUpdate() }
                connection.prepareStatement("DELETE FROM web_guild_members").use { it.executeUpdate() }
                connection.prepareStatement(GUILD_SQL).use { statement ->
                    guilds.forEach { guild ->
                        statement.bindGuild(guild)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
                connection.prepareStatement(GUILD_MEMBER_SQL).use { statement ->
                    guilds.forEach { guild ->
                        guild.members.forEach { member ->
                            statement.bindGuildMember(guild, member)
                            statement.addBatch()
                        }
                    }
                    statement.executeBatch()
                }
                connection.commit()
            } catch (error: Exception) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = true
            }
        }
    }

    private fun PreparedStatement.bindGuild(guild: ProjectedGuild) {
        val value = guild.guild
        setString(1, value.id)
        setString(2, value.name)
        setString(3, value.name.lowercase())
        setString(4, value.tag)
        setString(5, value.tag?.lowercase())
        setInt(6, value.level)
        setLong(7, guild.currentExperience)
        setLong(8, value.totalExperience)
        setInt(9, guild.memberLimitBonus)
        setString(10, value.ownerUuid)
        setInt(11, value.memberCount)
        setInt(12, guild.claimCount)
        setInt(13, guild.claimLimitBonus)
        setBoolean(14, guild.active)
        setTimestamp(15, Timestamp.from(Instant.parse(value.projectedAt)))
    }

    private fun PreparedStatement.bindGuildMember(guild: ProjectedGuild, member: ProjectedGuildMember) {
        setString(1, guild.guild.id)
        setString(2, member.playerUuid)
        setString(3, member.fallbackName)
        setString(4, member.role)
        setLong(5, member.totalContribution)
        setTimestamp(6, Timestamp.from(Instant.parse(guild.guild.projectedAt)))
    }

    fun checkpoint(source: String): ProjectionCheckpoint? {
        val sql =
            "SELECT checkpoint_timestamp, checkpoint_uuid FROM web_projection_state WHERE source_name = ?"
        return dataSource.connection { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, source)
                statement.executeQuery().use { result ->
                    if (!result.next()) return@connection null
                    ProjectionCheckpoint(
                        result.getTimestamp("checkpoint_timestamp")?.toInstant()?.toString(),
                        result.getString("checkpoint_uuid"),
                    )
                }
            }
        }
    }

    fun markSuccess(
        source: String,
        checkpoint: ProjectionCheckpoint?,
        catalogVersion: String,
    ) {
        updateState(source, checkpoint, catalogVersion, null, false)
    }

    fun markFailure(source: String, catalogVersion: String, error: Throwable) {
        updateState(source, checkpoint(source), catalogVersion, error.message ?: error.javaClass.name, true)
    }

    private fun updateState(
        source: String,
        checkpoint: ProjectionCheckpoint?,
        catalogVersion: String,
        error: String?,
        stale: Boolean,
    ) {
        val updateSql =
            """
            UPDATE web_projection_state SET
                checkpoint_timestamp = ?, checkpoint_uuid = ?,
                last_success_at = CASE WHEN ? THEN last_success_at ELSE ? END,
                last_error = ?, stale = ?, catalog_version = ?
            WHERE source_name = ?
            """.trimIndent()
        val insertSql =
            """
            INSERT INTO web_projection_state (
                source_name, checkpoint_timestamp, checkpoint_uuid, last_success_at,
                last_error, stale, catalog_version
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        dataSource.connection { connection ->
            connection.autoCommit = false
            try {
                val now = Timestamp.from(Instant.now())
                val checkpointTimestamp = checkpoint?.timestamp?.let { Timestamp.from(Instant.parse(it)) }
                val updated = connection.prepareStatement(updateSql).use { statement ->
                    statement.setTimestamp(1, checkpointTimestamp)
                    statement.setString(2, checkpoint?.versionUuid)
                    statement.setBoolean(3, stale)
                    statement.setTimestamp(4, now)
                    statement.setString(5, error)
                    statement.setBoolean(6, stale)
                    statement.setString(7, catalogVersion)
                    statement.setString(8, source)
                    statement.executeUpdate()
                }
                if (updated == 0) {
                    connection.prepareStatement(insertSql).use { statement ->
                        statement.setString(1, source)
                        statement.setTimestamp(2, checkpointTimestamp)
                        statement.setString(3, checkpoint?.versionUuid)
                        statement.setTimestamp(4, if (stale) null else now)
                        statement.setString(5, error)
                        statement.setBoolean(6, stale)
                        statement.setString(7, catalogVersion)
                        statement.executeUpdate()
                    }
                }
                connection.commit()
            } catch (failure: Exception) {
                connection.rollback()
                throw failure
            } finally {
                connection.autoCommit = true
            }
        }
    }

    companion object {
        private val GUILD_SQL =
            """
            MERGE INTO web_guilds (
                id, name, normalized_name, tag, normalized_tag, level,
                current_experience, total_experience, member_limit_bonus, owner_uuid,
                member_count, claim_count, claim_limit_bonus, active, projected_at
            ) KEY (id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()

        private val GUILD_MEMBER_SQL =
            """
            INSERT INTO web_guild_members (
                guild_id, player_uuid, fallback_name, role, total_contribution, projected_at
            ) VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent()
    }
}
