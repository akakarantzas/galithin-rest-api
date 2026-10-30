package com.galithin.web.sources.core

import com.galithin.web.sources.luckperms.LuckPermsIdentityReader
import com.galithin.web.sources.text.RichTextRenderer
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.util.UUID
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CoreDataReaderTest {
    private val pools = mutableListOf<HikariDataSource>()

    @AfterEach
    fun tearDown() = pools.forEach(HikariDataSource::close)

    @Test
    fun `reads guild progression members roles contributions and claim counts`() {
        val core = dataSource("core")
        val luckPerms = dataSource("luckperms")
        createCoreSchema(core)
        createLuckPermsSchema(luckPerms)
        val renderer = RichTextRenderer(catalogVersion = { "test-catalog" })
        val snapshot = CoreDataReader(
            core,
            renderer,
            LuckPermsIdentityReader(luckPerms, renderer),
        ).refresh()

        val guild = snapshot.guilds.single()
        assertEquals(GUILD_ID, guild.leaderboard.id)
        assertEquals(2, guild.leaderboard.memberCount)
        assertEquals(340, guild.currentExperience)
        assertEquals(2, guild.memberLimitBonus)
        assertEquals(1, guild.claimCount)
        assertEquals(3, guild.claimLimitBonus)
        assertEquals(listOf("owner", "mod"), guild.members.map(CoreGuildMember::role))
        assertEquals(listOf(700L, 250L), guild.members.map(CoreGuildMember::totalContribution))
        assertEquals("OwnerName", guild.members.first().fallbackName)
        assertNotNull(snapshot.playerGuilds[MEMBER_ID])
    }

    private fun dataSource(name: String): HikariDataSource {
        val config = HikariConfig().apply {
            jdbcUrl = "jdbc:h2:mem:$name-${UUID.randomUUID()};MODE=MariaDB;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE"
            username = "sa"
            password = ""
            maximumPoolSize = 2
        }
        return HikariDataSource(config).also(pools::add)
    }

    private fun createCoreSchema(dataSource: HikariDataSource) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE guild_data (guild_id UUID PRIMARY KEY, owner_id UUID NOT NULL)")
                statement.execute("CREATE TABLE guild_name_index (guild_id UUID, display_name VARCHAR(64))")
                statement.execute("CREATE TABLE guild_tag_index (guild_id UUID, display_tag VARCHAR(32))")
                statement.execute("CREATE TABLE guild_progression (guild_id UUID, level INT, experience BIGINT, total_experience BIGINT, member_limit_bonus INT, claim_limit_bonus INT)")
                statement.execute("CREATE TABLE guild_members (guild_id UUID, player_id UUID, role VARCHAR(32))")
                statement.execute("CREATE TABLE guild_member_contributions (guild_id UUID, player_id UUID, total_experience BIGINT)")
                statement.execute("CREATE TABLE guild_claims (guild_id UUID, claim_id UUID)")
                statement.execute("INSERT INTO guild_data VALUES ('$GUILD_ID', '$OWNER_ID')")
                statement.execute("INSERT INTO guild_name_index VALUES ('$GUILD_ID', 'Philosophers')")
                statement.execute("INSERT INTO guild_tag_index VALUES ('$GUILD_ID', 'THNK')")
                statement.execute("INSERT INTO guild_progression VALUES ('$GUILD_ID', 8, 340, 9400, 2, 3)")
                statement.execute("INSERT INTO guild_members VALUES ('$GUILD_ID', '$MEMBER_ID', 'mod')")
                statement.execute("INSERT INTO guild_member_contributions VALUES ('$GUILD_ID', '$OWNER_ID', 700)")
                statement.execute("INSERT INTO guild_member_contributions VALUES ('$GUILD_ID', '$MEMBER_ID', 250)")
                statement.execute("INSERT INTO guild_claims VALUES ('$GUILD_ID', '30000000-0000-0000-0000-000000000001')")
            }
        }
    }

    private fun createLuckPermsSchema(dataSource: HikariDataSource) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE luckperms_players (uuid VARCHAR(36), username VARCHAR(16), primary_group VARCHAR(32))")
                statement.execute("CREATE TABLE luckperms_group_permissions (name VARCHAR(32), permission VARCHAR(255), value BOOLEAN, expiry BIGINT, server VARCHAR(32), world VARCHAR(32))")
                statement.execute("CREATE TABLE luckperms_user_permissions (uuid VARCHAR(36), permission VARCHAR(255), value BOOLEAN, expiry BIGINT, server VARCHAR(32), world VARCHAR(32))")
                statement.execute("INSERT INTO luckperms_players VALUES ('$OWNER_ID', 'OwnerName', 'default')")
                statement.execute("INSERT INTO luckperms_players VALUES ('$MEMBER_ID', 'MemberName', 'default')")
            }
        }
    }

    companion object {
        private const val GUILD_ID = "10000000-0000-0000-0000-000000000001"
        private const val OWNER_ID = "00000000-0000-0000-0000-000000000001"
        private const val MEMBER_ID = "00000000-0000-0000-0000-000000000002"
    }
}
