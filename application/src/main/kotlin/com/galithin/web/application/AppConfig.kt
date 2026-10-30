package com.galithin.web.application

import com.galithin.web.sources.database.EmbeddedReadModelConfig
import com.galithin.web.sources.database.JdbcPoolConfig
import java.nio.file.Path
import java.time.Duration
import org.yaml.snakeyaml.Yaml

data class ServerConfig(
    val host: String,
    val port: Int,
    val corsOrigins: List<String>,
    val trustedProxies: List<String>,
)

data class SecurityConfig(
    val apiKeySha256: Set<String>,
    val publicRequestsPerMinute: Int,
    val publicBurst: Int,
    val authenticatedRequestsPerMinute: Int,
    val authenticatedBurst: Int,
)

data class ProjectionConfig(
    val pollInterval: Duration,
    val reconciliationInterval: Duration,
    val batchSize: Int,
)

data class ExclusionConfig(
    val groups: Set<String>,
    val uuids: Set<String>,
    val names: Set<String>,
)

data class CacheConfig(
    val profile: Duration,
    val leaderboard: Duration,
    val catalog: Duration,
)

data class OpenGraphConfig(
    val enabled: Boolean,
    val renderPageBaseUrl: String,
    val fallbackImageUrl: String,
    val cacheDirectory: Path,
    val browserExecutablePath: Path?,
    val maxConcurrentRenders: Int,
    val rendersPerIpPerHour: Int,
    val renderTimeout: Duration,
    val retryAfter: Duration,
)

data class AppConfig(
    val server: ServerConfig,
    val security: SecurityConfig,
    val projection: ProjectionConfig,
    val exclusions: ExclusionConfig,
    val cache: CacheConfig,
    val openGraph: OpenGraphConfig,
    val readModel: EmbeddedReadModelConfig,
    val huskSync: JdbcPoolConfig,
    val core: JdbcPoolConfig,
    val luckPerms: JdbcPoolConfig,
    val luckPermsTablePrefix: String,
    val luckPermsServerContext: String,
    val contentBundle: Path,
) {
    companion object {
        fun load(path: Path): AppConfig {
            val raw = substituteEnvironment(path.toFile().readText())
            val root = Yaml().load<Map<String, Any?>>(raw)
            val server = root.map("server")
            val databases = root.map("databases")
            val security = root.map("security")
            val projection = root.map("projection")
            val exclusions = root.map("exclusions")
            val cache = root.map("cache")
            val openGraph = root.map("openGraph")
            return AppConfig(
                server = ServerConfig(
                    host = server.string("host", "0.0.0.0"),
                    port = server.int("port", 8080),
                    corsOrigins = server.strings("corsOrigins"),
                    trustedProxies = server.strings("trustedProxies"),
                ),
                security = SecurityConfig(
                    apiKeySha256 = security.strings("apiKeySha256").filter(String::isNotBlank).toSet(),
                    publicRequestsPerMinute = security.int("publicRequestsPerMinute", 120),
                    publicBurst = security.int("publicBurst", 30),
                    authenticatedRequestsPerMinute = security.int("authenticatedRequestsPerMinute", 1200),
                    authenticatedBurst = security.int("authenticatedBurst", 100),
                ),
                projection = ProjectionConfig(
                    pollInterval = Duration.ofMillis(projection.long("pollIntervalMillis", 2_000)),
                    reconciliationInterval = Duration.ofSeconds(projection.long("reconciliationIntervalSeconds", 300)),
                    batchSize = projection.int("batchSize", 250).coerceIn(10, 2_000),
                ),
                exclusions = ExclusionConfig(
                    groups = exclusions.strings("groups").map(String::lowercase).toSet(),
                    uuids = exclusions.strings("uuids").map(String::lowercase).toSet(),
                    names = exclusions.strings("names").map(String::lowercase).toSet(),
                ),
                cache = CacheConfig(
                    profile = Duration.ofSeconds(cache.long("profileSeconds", 5)),
                    leaderboard = Duration.ofSeconds(cache.long("leaderboardSeconds", 15)),
                    catalog = Duration.ofSeconds(cache.long("catalogSeconds", 300)),
                ),
                openGraph = OpenGraphConfig(
                    enabled = openGraph.boolean("enabled", true),
                    renderPageBaseUrl = openGraph.string("renderPageBaseUrl", "https://galithin.com").trimEnd('/'),
                    fallbackImageUrl = openGraph.string(
                        "fallbackImageUrl",
                        "https://galithin.com/static/images/statsBg.png",
                    ),
                    cacheDirectory = Path.of(openGraph.string("cacheDirectory", "./data/opengraph")),
                    browserExecutablePath = openGraph.optionalString("browserExecutablePath")?.let(Path::of),
                    maxConcurrentRenders = openGraph.int("maxConcurrentRenders", 2).coerceIn(1, 8),
                    rendersPerIpPerHour = openGraph.int("rendersPerIpPerHour", 10).coerceIn(1, 1_000),
                    renderTimeout = Duration.ofSeconds(openGraph.long("renderTimeoutSeconds", 25).coerceIn(1, 120)),
                    retryAfter = Duration.ofSeconds(openGraph.long("retryAfterSeconds", 300).coerceIn(1, 3_600)),
                ),
                readModel = root.map("storage").embeddedReadModel(),
                huskSync = databases.pool("huskSync", readOnly = true, defaultSize = 4),
                core = databases.pool("core", readOnly = true, defaultSize = 4),
                luckPerms = databases.pool("luckPerms", readOnly = true, defaultSize = 2),
                luckPermsTablePrefix = root.map("luckPerms").string("tablePrefix", "luckperms_"),
                luckPermsServerContext = root.map("luckPerms").string("serverContext", "global"),
                contentBundle = Path.of(root.map("content").string("bundlePath")),
            )
        }

        private val ENVIRONMENT = Regex("\\$\\{([A-Z0-9_]+)(?::-(.*?))?}")

        private fun substituteEnvironment(value: String): String =
            ENVIRONMENT.replace(value) { match ->
                System.getenv(match.groupValues[1])
                    ?: match.groups[2]?.value
                    ?: error("Missing environment variable ${match.groupValues[1]}")
            }

        private fun Map<String, Any?>.pool(
            key: String,
            readOnly: Boolean,
            defaultSize: Int,
        ): JdbcPoolConfig {
            val value = map(key)
            return JdbcPoolConfig(
                jdbcUrl = value.string("jdbcUrl"),
                username = value.string("username"),
                password = value.string("password"),
                maximumPoolSize = value.int("maximumPoolSize", defaultSize),
                readOnly = readOnly,
            )
        }

        private fun Map<String, Any?>.embeddedReadModel(): EmbeddedReadModelConfig =
            EmbeddedReadModelConfig(
                path = Path.of(string("readModelPath", "./data/galithin-read-model")),
                maximumPoolSize = int("maximumPoolSize", 8).coerceIn(1, 32),
            )

        @Suppress("UNCHECKED_CAST")
        private fun Map<String, Any?>.map(key: String): Map<String, Any?> =
            this[key] as? Map<String, Any?> ?: emptyMap()

        private fun Map<String, Any?>.string(key: String, default: String? = null): String =
            this[key]?.toString() ?: default ?: error("Missing configuration value '$key'")

        private fun Map<String, Any?>.optionalString(key: String): String? =
            this[key]?.toString()?.trim()?.takeIf(String::isNotEmpty)

        private fun Map<String, Any?>.boolean(key: String, default: Boolean): Boolean =
            (this[key] as? Boolean) ?: this[key]?.toString()?.toBooleanStrictOrNull() ?: default

        private fun Map<String, Any?>.int(key: String, default: Int): Int =
            (this[key] as? Number)?.toInt() ?: this[key]?.toString()?.toIntOrNull() ?: default

        private fun Map<String, Any?>.long(key: String, default: Long): Long =
            (this[key] as? Number)?.toLong() ?: this[key]?.toString()?.toLongOrNull() ?: default

        private fun Map<String, Any?>.strings(key: String): List<String> =
            (this[key] as? Collection<*>)?.mapNotNull { it?.toString() } ?: emptyList()
    }
}
