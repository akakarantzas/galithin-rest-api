package com.galithin.web.sources.husksync

import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import org.h2.jdbcx.JdbcDataSource

class HuskSyncSnapshotReaderTest {
    @Test
    fun `loads older snapshots using timestamp and version tie breaker`() {
        val dataSource = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:husksync-${UUID.randomUUID()};MODE=MySQL;DB_CLOSE_DELAY=-1")
        }
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE husksync_users (uuid VARCHAR(36) PRIMARY KEY, username VARCHAR(32))")
                statement.execute(
                    """CREATE TABLE husksync_user_data (
                        version_uuid VARCHAR(36) PRIMARY KEY,
                        player_uuid VARCHAR(36) NOT NULL,
                        timestamp TIMESTAMP NOT NULL,
                        data VARBINARY NOT NULL
                    )""",
                )
                statement.execute("INSERT INTO husksync_users VALUES ('player-1', 'Tester')")
            }
            connection.prepareStatement("INSERT INTO husksync_user_data VALUES (?, 'player-1', ?, ?)").use { insert ->
                listOf(
                    Triple("version-a", "2026-01-02T00:00:00Z", byteArrayOf(1)),
                    Triple("version-b", "2026-01-02T00:00:00Z", byteArrayOf(2)),
                    Triple("version-z", "2026-01-01T00:00:00Z", byteArrayOf(3)),
                ).forEach { (version, savedAt, data) ->
                    insert.setString(1, version)
                    insert.setTimestamp(2, Timestamp.from(Instant.parse(savedAt)))
                    insert.setBytes(3, data)
                    insert.addBatch()
                }
                insert.executeBatch()
            }
        }
        val current = PackedSnapshotRow(
            versionUuid = "version-b",
            playerUuid = "player-1",
            username = "Tester",
            firstSeenAt = null,
            savedAt = "2026-01-02T00:00:00Z",
            packedData = byteArrayOf(2),
        )

        val history = HuskSyncSnapshotReader(dataSource).before(current)

        assertEquals(listOf("version-a", "version-z"), history.map { it.versionUuid })
    }
}
