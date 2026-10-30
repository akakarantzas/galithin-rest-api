package com.galithin.web.sources.database

import com.galithin.web.domain.GuildLeaderboardEntry
import com.galithin.web.domain.PlayerProfile
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.time.Duration
import javax.sql.DataSource
import org.flywaydb.core.Flyway

data class JdbcPoolConfig(
    val jdbcUrl: String,
    val username: String,
    val password: String,
    val maximumPoolSize: Int = 8,
    val readOnly: Boolean = false,
    val connectionTimeout: Duration = Duration.ofSeconds(5),
)

data class EmbeddedReadModelConfig(
    val path: Path,
    val maximumPoolSize: Int = 8,
)

fun createDataSource(name: String, config: JdbcPoolConfig, metricRegistry: Any? = null): HikariDataSource {
    val hikari = HikariConfig()
    hikari.poolName = name
    hikari.jdbcUrl = config.jdbcUrl
    hikari.username = config.username
    hikari.password = config.password
    hikari.maximumPoolSize = config.maximumPoolSize
    hikari.minimumIdle = minOf(2, config.maximumPoolSize)
    hikari.isReadOnly = config.readOnly
    hikari.connectionTimeout = config.connectionTimeout.toMillis()
    hikari.validationTimeout = 2_000
    hikari.keepaliveTime = 60_000
    if (metricRegistry != null) hikari.metricRegistry = metricRegistry
    return HikariDataSource(hikari)
}

fun createEmbeddedReadModelDataSource(
    config: EmbeddedReadModelConfig,
    metricRegistry: Any? = null,
): HikariDataSource {
    val databasePath = config.path.toAbsolutePath().normalize()
    databasePath.parent?.let(Files::createDirectories)
    require(!databasePath.fileName.toString().endsWith(".mv.db", ignoreCase = true)) {
        "Embedded read-model path must omit the H2 .mv.db suffix: $databasePath"
    }
    require(!Files.isDirectory(databasePath)) {
        "Embedded read-model path must name a database file, not a directory: $databasePath"
    }

    val hikari = HikariConfig()
    hikari.poolName = "galithin-read-model"
    hikari.driverClassName = "org.h2.Driver"
    hikari.jdbcUrl = buildString {
        append("jdbc:h2:file:")
        append(databasePath)
        append(";MODE=MariaDB")
        append(";DATABASE_TO_LOWER=TRUE")
        append(";DEFAULT_NULL_ORDERING=HIGH")
        append(";DB_CLOSE_ON_EXIT=FALSE")
    }
    hikari.username = "sa"
    hikari.password = ""
    hikari.maximumPoolSize = config.maximumPoolSize
    hikari.minimumIdle = minOf(2, config.maximumPoolSize)
    hikari.connectionTimeout = 5_000
    hikari.validationTimeout = 2_000
    hikari.keepaliveTime = 60_000
    hikari.connectionInitSql = "SET LOCK_TIMEOUT 10000"
    if (metricRegistry != null) hikari.metricRegistry = metricRegistry
    return HikariDataSource(hikari)
}

fun migrateReadModel(dataSource: DataSource) {
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .baselineOnMigrate(true)
        .load()
        .migrate()
}

data class ProjectedPlayer(
    val profile: PlayerProfile,
    val eligible: Boolean,
    val sourceVersionUuid: String?,
    val sourceSavedAt: String?,
    val projectedAt: String,
    val stale: Boolean,
)

data class ProjectedGuild(
    val guild: GuildLeaderboardEntry,
    val currentExperience: Long,
    val memberLimitBonus: Int,
    val claimCount: Int,
    val claimLimitBonus: Int,
    val members: List<ProjectedGuildMember>,
    val active: Boolean,
)

data class ProjectedGuildMember(
    val playerUuid: String,
    val fallbackName: String?,
    val role: String,
    val totalContribution: Long,
)

data class ProjectionCheckpoint(
    val timestamp: String?,
    val versionUuid: String?,
)

internal inline fun <T> DataSource.connection(block: (Connection) -> T): T =
    connection.use(block)
