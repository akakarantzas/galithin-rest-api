package com.galithin.web.sources.database

import com.galithin.web.domain.DungeonStatistics
import com.galithin.web.domain.GuildLeaderboardEntry
import com.galithin.web.domain.GuildCatalog
import com.galithin.web.domain.GuildLevelReward
import com.galithin.web.domain.PlayerProfile
import com.galithin.web.domain.PlayerStatistics
import com.galithin.web.domain.RankPresentation
import com.galithin.web.domain.RichText
import com.zaxxer.hikari.HikariDataSource
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmbeddedReadModelTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private lateinit var dataSource: HikariDataSource

    @BeforeEach
    fun setUp() {
        dataSource = createEmbeddedReadModelDataSource(
            EmbeddedReadModelConfig(temporaryDirectory.resolve("read-model"), maximumPoolSize = 2),
        )
        migrateReadModel(dataSource)
    }

    @AfterEach
    fun tearDown() {
        dataSource.close()
    }

    @Test
    fun `persists and updates profiles in the embedded database`() = runBlocking {
        val writer = ReadModelWriter(dataSource)
        val repository = repository()
        val first = profile(uuid = PLAYER_ONE, name = "FirstPlayer", level = 10, experience = 100)

        writer.upsertPlayer(projected(first, sourceVersion = VERSION_ONE))

        assertEquals(first, repository.findPlayer(PLAYER_ONE))
        assertEquals(first, repository.findPlayer("firstplayer"))

        val updated = first.copy(level = 11, experience = 5_000)
        writer.upsertPlayer(projected(updated, sourceVersion = VERSION_TWO))

        assertEquals(updated, repository.findPlayer(PLAYER_ONE))
        assertEquals(11, repository.playerLevelLeaderboard(50, null).entries.single().level)
        assertTrue(repository.status().ready)
    }

    @Test
    fun `paginates player and guild leaderboards deterministically`() = runBlocking {
        val writer = ReadModelWriter(dataSource)
        val repository = repository()
        writer.upsertPlayer(projected(profile(PLAYER_ONE, "FirstPlayer", 20, 100), VERSION_ONE))
        writer.upsertPlayer(projected(profile(PLAYER_TWO, "SecondPlayer", 20, 90), VERSION_TWO))

        val firstPage = repository.playerLevelLeaderboard(1, null)
        assertEquals(PLAYER_ONE, firstPage.entries.single().uuid)
        assertNotNull(firstPage.nextCursor)
        val secondPage = repository.playerLevelLeaderboard(1, firstPage.nextCursor)
        assertEquals(PLAYER_TWO, secondPage.entries.single().uuid)
        assertNull(secondPage.nextCursor)

        writer.synchronizeGuilds(
            listOf(
                projectedGuild(GUILD_ONE, "First Guild", 8, 1_000, "ONE"),
                projectedGuild(GUILD_TWO, "Second Guild", 8, 900, "TWO"),
            ),
        )
        val guildPage = repository.guildLevelLeaderboard(1, null)
        assertEquals(GUILD_ONE, guildPage.entries.single().id)
        assertNotNull(guildPage.nextCursor)

        val guild = assertNotNull(repository.findGuild("first guild"))
        assertEquals(GUILD_ONE, guild.id)
        assertEquals(GUILD_ONE, repository.findGuild("one")?.id)
        assertEquals(GUILD_ONE, repository.findGuild(GUILD_ONE)?.id)
        assertEquals(2_660, guild.progression.requiredExperience)
        assertEquals(10, guild.memberCapacity.limit)
        assertEquals(5, guild.claimCapacity.limit)
        assertEquals("owner", guild.members.single().role)
        assertEquals("FirstPlayer", guild.members.single().name)
        assertTrue(guild.members.single().profileAvailable)

        writer.synchronizeGuilds(listOf(projectedGuild(GUILD_TWO, "Second Guild", 9, 2_000, "TWO")))
        val activeGuilds = repository.guildLevelLeaderboard(50, null)
        assertEquals(listOf(GUILD_TWO), activeGuilds.entries.map { it.id })
    }

    @Test
    fun `searches player and guild leaderboards by global position or name`() = runBlocking {
        val writer = ReadModelWriter(dataSource)
        val repository = repository()
        writer.upsertPlayer(projected(profile(PLAYER_ONE, "FirstPlayer", 20, 100), VERSION_ONE))
        writer.upsertPlayer(projected(profile(PLAYER_TWO, "SecondPlayer", 20, 90), VERSION_TWO))

        val playerByPosition = repository.playerLevelLeaderboard(50, null, "#2")
        assertEquals(listOf(PLAYER_TWO), playerByPosition.entries.map { it.uuid })
        assertEquals(2, playerByPosition.entries.single().position)
        assertNull(playerByPosition.nextCursor)

        val playerByName = repository.playerLevelLeaderboard(50, null, "sEcOnD")
        assertEquals(listOf(PLAYER_TWO), playerByName.entries.map { it.uuid })
        assertEquals(2, playerByName.entries.single().position)

        val firstSearchPage = repository.playerLevelLeaderboard(1, null, "player")
        assertEquals(PLAYER_ONE, firstSearchPage.entries.single().uuid)
        assertNotNull(firstSearchPage.nextCursor)
        val secondSearchPage = repository.playerLevelLeaderboard(1, firstSearchPage.nextCursor, "player")
        assertEquals(PLAYER_TWO, secondSearchPage.entries.single().uuid)
        assertEquals(2, secondSearchPage.entries.single().position)
        assertNull(secondSearchPage.nextCursor)

        writer.synchronizeGuilds(
            listOf(
                projectedGuild(GUILD_ONE, "First Guild", 8, 1_000, "ONE"),
                projectedGuild(GUILD_TWO, "Second Guild", 8, 900, "TWO", PLAYER_TWO),
            ),
        )

        val guildByPosition = repository.guildLevelLeaderboard(50, null, "2")
        assertEquals(listOf(GUILD_TWO), guildByPosition.entries.map { it.id })
        assertEquals(2, guildByPosition.entries.single().position)

        val guildByName = repository.guildLevelLeaderboard(50, null, "FIRST")
        assertEquals(listOf(GUILD_ONE), guildByName.entries.map { it.id })
        assertEquals(1, guildByName.entries.single().position)

        val guildByTag = repository.guildLevelLeaderboard(50, null, "wO")
        assertEquals(listOf(GUILD_TWO), guildByTag.entries.map { it.id })
        assertEquals(2, guildByTag.entries.single().position)

        val guildByExactPlayerName = repository.guildLevelLeaderboard(50, null, "sEcOnDpLaYeR")
        assertEquals(listOf(GUILD_TWO), guildByExactPlayerName.entries.map { it.id })
        assertEquals(2, guildByExactPlayerName.entries.single().position)

        val guildByPartialPlayerName = repository.guildLevelLeaderboard(50, null, "SecondPlaye")
        assertTrue(guildByPartialPlayerName.entries.isEmpty())
    }

    @Test
    fun `returns leaderboard windows around a global position and continues downward`() = runBlocking {
        val writer = ReadModelWriter(dataSource)
        val repository = repository()
        (1..30).forEach { position ->
            writer.upsertPlayer(
                projected(
                    profile(
                        uuid = "00000000-0000-0000-0000-${position.toString().padStart(12, '0')}",
                        name = "Player$position",
                        level = 31 - position,
                        experience = 0,
                    ),
                    sourceVersion = VERSION_ONE,
                ),
            )
        }

        val playerWindow = repository.playerLevelLeaderboard(5, null, around = 15)
        assertEquals(listOf(13L, 14L, 15L, 16L, 17L), playerWindow.entries.map { it.position })
        assertNotNull(playerWindow.nextCursor)

        val continued = repository.playerLevelLeaderboard(5, playerWindow.nextCursor)
        assertEquals(listOf(18L, 19L, 20L, 21L, 22L), continued.entries.map { it.position })

        writer.synchronizeGuilds(
            (1..7).map { position ->
                projectedGuild(
                    id = "10000000-0000-0000-0000-${position.toString().padStart(12, '0')}",
                    name = "Guild $position",
                    level = 8 - position,
                    experience = 0,
                )
            },
        )
        val guildWindow = repository.guildLevelLeaderboard(3, null, around = 4)
        assertEquals(listOf(3L, 4L, 5L), guildWindow.entries.map { it.position })
        assertNotNull(guildWindow.nextCursor)
    }

    @Test
    fun `keeps the last successful checkpoint timestamp after a projection failure`() {
        val writer = ReadModelWriter(dataSource)
        val checkpoint = ProjectionCheckpoint(PROJECTED_AT, VERSION_ONE)
        writer.markSuccess("husksync-v4", checkpoint, "catalog-one")

        val successfulAt = stateLastSuccessAt()
        assertNotNull(successfulAt)
        writer.markFailure("husksync-v4", "catalog-two", IllegalStateException("source unavailable"))

        assertEquals(successfulAt, stateLastSuccessAt())
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT stale, last_error, catalog_version FROM web_projection_state WHERE source_name = ?",
            ).use { statement ->
                statement.setString(1, "husksync-v4")
                statement.executeQuery().use { result ->
                    assertTrue(result.next())
                    assertTrue(result.getBoolean("stale"))
                    assertEquals("source unavailable", result.getString("last_error"))
                    assertEquals("catalog-two", result.getString("catalog_version"))
                }
            }
        }
    }

    @Test
    fun `creates a persistent local database file`() {
        val writer = ReadModelWriter(dataSource)
        writer.upsertPlayer(projected(profile(PLAYER_ONE, "FirstPlayer", 10, 100), VERSION_ONE))
        dataSource.connection.use { connection ->
            assertFalse(connection.isClosed)
            connection.prepareStatement("SELECT COUNT(*) FROM web_players").executeQuery().use { result ->
                assertTrue(result.next())
                assertEquals(1, result.getInt(1))
            }
        }
        assertTrue(temporaryDirectory.resolve("read-model.mv.db").toFile().isFile)

        dataSource.close()
        dataSource = createEmbeddedReadModelDataSource(
            EmbeddedReadModelConfig(temporaryDirectory.resolve("read-model"), maximumPoolSize = 2),
        )
        migrateReadModel(dataSource)

        val restored = runBlocking {
            repository().findPlayer(PLAYER_ONE)
        }
        assertEquals("FirstPlayer", restored?.name)
    }

    private fun stateLastSuccessAt(): Instant? = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT last_success_at FROM web_projection_state WHERE source_name = ?",
        ).use { statement ->
            statement.setString(1, "husksync-v4")
            statement.executeQuery().use { result ->
                if (result.next()) result.getTimestamp(1)?.toInstant() else null
            }
        }
    }

    private fun profile(
        uuid: String,
        name: String,
        level: Int,
        experience: Long,
    ): PlayerProfile = PlayerProfile(
        uuid = uuid,
        name = name,
        level = level,
        experience = experience,
        rank = RankPresentation("default", richText("Player")),
        badges = emptyList(),
        guild = null,
        equipment = emptyList(),
        statistics = PlayerStatistics(0, 0, 0, null, null, 0),
        dungeonStatistics = DungeonStatistics(mapOf("roomsExplored" to JsonPrimitive(12))),
        abilities = emptyList(),
        talents = emptyList(),
    )

    private fun projected(profile: PlayerProfile, sourceVersion: String): ProjectedPlayer =
        ProjectedPlayer(
            profile = profile,
            eligible = true,
            sourceVersionUuid = sourceVersion,
            sourceSavedAt = PROJECTED_AT,
            projectedAt = PROJECTED_AT,
            stale = false,
        )

    private fun projectedGuild(
        id: String,
        name: String,
        level: Int,
        experience: Long,
        tag: String? = null,
        memberUuid: String = PLAYER_ONE,
    ): ProjectedGuild = ProjectedGuild(
        GuildLeaderboardEntry(
            position = 0,
            id = id,
            name = name,
            tag = tag,
            level = level,
            totalExperience = experience,
            ownerUuid = memberUuid,
            memberCount = 1,
            projectedAt = PROJECTED_AT,
        ),
        currentExperience = 250,
        memberLimitBonus = 0,
        claimCount = 1,
        claimLimitBonus = 0,
        members = listOf(
            ProjectedGuildMember(
                playerUuid = memberUuid,
                fallbackName = if (memberUuid == PLAYER_ONE) "FirstPlayer" else "SecondPlayer",
                role = "owner",
                totalContribution = 400,
            ),
        ),
        active = true,
    )

    private fun repository() = JdbcProfileReadRepository(
        dataSource,
        catalogVersion = { "test-catalog" },
        guildCatalog = {
            GuildCatalog(
                baseExperienceCost = 1_000,
                experienceIncreasePerLevel = 1.15,
                maximumLevel = 100,
                rewards = mapOf(5 to GuildLevelReward(10, 5)),
            )
        },
    )

    private fun richText(value: String): RichText =
        RichText("{\"text\":\"$value\"}", value, value)

    companion object {
        private const val PLAYER_ONE = "00000000-0000-0000-0000-000000000001"
        private const val PLAYER_TWO = "00000000-0000-0000-0000-000000000002"
        private const val GUILD_ONE = "10000000-0000-0000-0000-000000000001"
        private const val GUILD_TWO = "10000000-0000-0000-0000-000000000002"
        private const val VERSION_ONE = "20000000-0000-0000-0000-000000000001"
        private const val VERSION_TWO = "20000000-0000-0000-0000-000000000002"
        private const val PROJECTED_AT = "2026-08-08T12:00:00Z"
    }
}
