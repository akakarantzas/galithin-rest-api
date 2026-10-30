package com.galithin.web.sources.husksync

import com.fasterxml.jackson.databind.JsonNode
import com.galithin.web.sources.database.ProjectionCheckpoint
import com.galithin.web.sources.database.connection
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

data class PackedSnapshotRow(
    val versionUuid: String,
    val playerUuid: String,
    val username: String,
    val firstSeenAt: String?,
    val savedAt: String,
    val packedData: ByteArray,
)

data class DecodedSnapshot(
    val formatVersion: Int,
    val minecraftVersion: String,
    val data: Map<String, String>,
) {
    fun serialized(identifier: String): String? {
        data[identifier]?.let { return it }
        return data.entries.firstOrNull { (key, _) ->
            key.equals(identifier, ignoreCase = true) ||
                key.substringAfter(':').equals(identifier, ignoreCase = true)
        }?.value
    }
}

class UnsupportedHuskSyncFormat(message: String) : RuntimeException(message)

class HuskSyncSnapshotReader(private val dataSource: DataSource) {
    fun latest(offset: Long, limit: Int): List<PackedSnapshotRow> {
        val sql =
            """
            SELECT version_uuid, player_uuid, username, first_seen_at, timestamp, data
            FROM (
                SELECT d.version_uuid, d.player_uuid, u.username, d.timestamp, d.data,
                       MIN(d.timestamp) OVER (PARTITION BY d.player_uuid) AS first_seen_at,
                       ROW_NUMBER() OVER (
                           PARTITION BY d.player_uuid
                           ORDER BY d.timestamp DESC, d.version_uuid DESC
                       ) AS row_number
                FROM husksync_user_data d
                JOIN husksync_users u ON u.uuid = d.player_uuid
            ) latest
            WHERE row_number = 1
            ORDER BY player_uuid
            LIMIT ? OFFSET ?
            """.trimIndent()
        return dataSource.connection { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setInt(1, limit)
                statement.setLong(2, offset)
                statement.executeQuery().use { result -> buildList {
                    while (result.next()) add(
                        PackedSnapshotRow(
                            versionUuid = result.getString("version_uuid"),
                            playerUuid = result.getString("player_uuid"),
                            username = result.getString("username"),
                            firstSeenAt = result.getTimestamp("first_seen_at")?.toInstant()?.toString(),
                            savedAt = result.getTimestamp("timestamp").toInstant().toString(),
                            packedData = result.getBytes("data"),
                        )
                    )
                } }
            }
        }
    }

    fun after(checkpoint: ProjectionCheckpoint?, limit: Int): List<PackedSnapshotRow> {
        if (checkpoint?.timestamp == null || checkpoint.versionUuid == null) return emptyList()
        val sql =
            """
            SELECT d.version_uuid, d.player_uuid, u.username, d.timestamp, d.data,
                   (SELECT MIN(history.timestamp) FROM husksync_user_data history
                    WHERE history.player_uuid = d.player_uuid) AS first_seen_at
            FROM husksync_user_data d
            JOIN husksync_users u ON u.uuid = d.player_uuid
            WHERE d.timestamp > ? OR (d.timestamp = ? AND d.version_uuid > ?)
            ORDER BY d.timestamp, d.version_uuid
            LIMIT ?
            """.trimIndent()
        return dataSource.connection { connection ->
            connection.prepareStatement(sql).use { statement ->
                val timestamp = Timestamp.from(Instant.parse(checkpoint.timestamp))
                statement.setTimestamp(1, timestamp)
                statement.setTimestamp(2, timestamp)
                statement.setString(3, checkpoint.versionUuid)
                statement.setInt(4, limit)
                statement.executeQuery().use { result -> buildList {
                    while (result.next()) add(
                        PackedSnapshotRow(
                            versionUuid = result.getString("version_uuid"),
                            playerUuid = result.getString("player_uuid"),
                            username = result.getString("username"),
                            firstSeenAt = result.getTimestamp("first_seen_at")?.toInstant()?.toString(),
                            savedAt = result.getTimestamp("timestamp").toInstant().toString(),
                            packedData = result.getBytes("data"),
                        )
                    )
                } }
            }
        }
    }

    /** Returns older snapshots in the same order used by HuskSync's fallback merge. */
    fun before(row: PackedSnapshotRow, limit: Int = 15): List<PackedSnapshotRow> {
        val sql =
            """
            SELECT d.version_uuid, d.player_uuid, u.username, d.timestamp, d.data,
                   (SELECT MIN(history.timestamp) FROM husksync_user_data history
                    WHERE history.player_uuid = d.player_uuid) AS first_seen_at
            FROM husksync_user_data d
            JOIN husksync_users u ON u.uuid = d.player_uuid
            WHERE d.player_uuid = ?
              AND (d.timestamp < ? OR (d.timestamp = ? AND d.version_uuid < ?))
            ORDER BY d.timestamp DESC, d.version_uuid DESC
            LIMIT ?
            """.trimIndent()
        return dataSource.connection { connection ->
            connection.prepareStatement(sql).use { statement ->
                val savedAt = Timestamp.from(Instant.parse(row.savedAt))
                statement.setString(1, row.playerUuid)
                statement.setTimestamp(2, savedAt)
                statement.setTimestamp(3, savedAt)
                statement.setString(4, row.versionUuid)
                statement.setInt(5, limit)
                statement.executeQuery().use { result -> buildList {
                    while (result.next()) add(
                        PackedSnapshotRow(
                            versionUuid = result.getString("version_uuid"),
                            playerUuid = result.getString("player_uuid"),
                            username = result.getString("username"),
                            firstSeenAt = result.getTimestamp("first_seen_at")?.toInstant()?.toString(),
                            savedAt = result.getTimestamp("timestamp").toInstant().toString(),
                            packedData = result.getBytes("data"),
                        )
                    )
                } }
            }
        }
    }

    fun latestCheckpoint(): ProjectionCheckpoint? {
        val sql =
            """
            SELECT timestamp, version_uuid FROM husksync_user_data
            ORDER BY timestamp DESC, version_uuid DESC LIMIT 1
            """.trimIndent()
        return dataSource.connection { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.executeQuery().use { result ->
                    if (!result.next()) return@connection null
                    ProjectionCheckpoint(
                        result.getTimestamp("timestamp").toInstant().toString(),
                        result.getString("version_uuid"),
                    )
                }
            }
        }
    }
}
