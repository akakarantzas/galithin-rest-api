package com.galithin.web.application

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.galithin.web.application.cache.ResponseCache
import com.galithin.web.application.opengraph.FailureReason
import com.galithin.web.application.opengraph.OpenGraphImageResult
import com.galithin.web.application.opengraph.OpenGraphImageService
import com.galithin.web.application.security.RequestAuthenticator
import com.galithin.web.application.security.TokenBucketRateLimiter
import com.galithin.web.domain.ApiResponse
import com.galithin.web.domain.CatalogKind
import com.galithin.web.domain.ProblemDetail
import com.galithin.web.domain.ResponseMeta
import com.galithin.web.sources.catalog.FileCatalogRepository
import com.galithin.web.sources.core.CoreDataReader
import com.galithin.web.sources.database.JdbcProfileReadRepository
import com.galithin.web.sources.database.ReadModelWriter
import com.galithin.web.sources.database.createDataSource
import com.galithin.web.sources.database.createEmbeddedReadModelDataSource
import com.galithin.web.sources.database.migrateReadModel
import com.galithin.web.sources.husksync.EligibilityRules
import com.galithin.web.sources.husksync.HuskSyncSnapshotReader
import com.galithin.web.sources.husksync.PackedSnapshotDecoder
import com.galithin.web.sources.husksync.ProfileSnapshotDecoder
import com.galithin.web.sources.luckperms.LuckPermsIdentityReader
import com.galithin.web.sources.projection.GalithinProjectionRunner
import com.galithin.web.sources.registerKotlinxJsonElementSupport
import com.galithin.web.sources.text.RichTextRenderer
import com.zaxxer.hikari.HikariDataSource
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.deflate
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.micrometer.core.instrument.Counter
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.net.URI
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun main(args: Array<String>) {
    val configPath = argument(args, "--config")
        ?: System.getenv("GALITHIN_API_CONFIG")
        ?: "config.yml"
    val config = AppConfig.load(Path.of(configPath))
    RuntimeComponents.create(config).use { runtime ->
        embeddedServer(Netty, host = config.server.host, port = config.server.port) {
            galithinModule(runtime)
        }.start(wait = true)
    }
}

private fun argument(args: Array<String>, name: String): String? {
    val index = args.indexOf(name)
    return if (index >= 0) args.getOrNull(index + 1) else null
}

class RuntimeComponents private constructor(
    val config: AppConfig,
    val catalog: FileCatalogRepository,
    val repository: JdbcProfileReadRepository,
    val projection: GalithinProjectionRunner,
    val metrics: PrometheusMeterRegistry,
    val authenticator: RequestAuthenticator,
    val rateLimiter: TokenBucketRateLimiter,
    val rateLimitRejections: Counter,
    val cache: ResponseCache,
    val openGraphImages: OpenGraphImageService,
    val openGraphCacheHits: Counter,
    val openGraphCacheMisses: Counter,
    val openGraphRenderFailures: Counter,
    val openGraphRateLimitRejections: Counter,
    private val pools: List<HikariDataSource>,
) : AutoCloseable {
    override fun close() {
        projection.close()
        catalog.close()
        pools.forEach(HikariDataSource::close)
    }

    companion object {
        fun create(config: AppConfig): RuntimeComponents {
            val metrics = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
            val readModel = createEmbeddedReadModelDataSource(config.readModel, metrics)
            val huskSync = createDataSource("galithin-husksync-source", config.huskSync, metrics)
            val core = createDataSource("galithin-core-source", config.core, metrics)
            val luckPerms = createDataSource("galithin-luckperms-source", config.luckPerms, metrics)
            migrateReadModel(readModel)

            val catalog = FileCatalogRepository(config.contentBundle)
            lateinit var richText: RichTextRenderer
            richText = RichTextRenderer(catalog::version) { catalog.glyphRegistry() }
            val coreReader = CoreDataReader(
                core,
                richText,
                LuckPermsIdentityReader(
                    luckPerms,
                    richText,
                    config.luckPermsTablePrefix,
                    config.luckPermsServerContext,
                ),
            )
            val profileDecoder = ProfileSnapshotDecoder(
                catalog,
                richText,
                EligibilityRules(
                    config.exclusions.groups,
                    config.exclusions.uuids,
                    config.exclusions.names,
                ),
            )
            val writer = ReadModelWriter(readModel)
            val decodeFailures = Counter.builder("galithin_projection_decode_failures_total").register(metrics)
            val projectionLag = AtomicLong()
            val backfillProgress = AtomicLong()
            io.micrometer.core.instrument.Gauge.builder("galithin_projection_lag_milliseconds", projectionLag) { it.get().toDouble() }
                .register(metrics)
            io.micrometer.core.instrument.Gauge.builder("galithin_projection_backfill_players", backfillProgress) { it.get().toDouble() }
                .register(metrics)
            val projection = GalithinProjectionRunner(
                snapshots = HuskSyncSnapshotReader(huskSync),
                packedDecoder = PackedSnapshotDecoder(),
                profileDecoder = profileDecoder,
                coreData = coreReader,
                writer = writer,
                catalog = catalog,
                pollInterval = config.projection.pollInterval,
                reconciliationInterval = config.projection.reconciliationInterval,
                batchSize = config.projection.batchSize,
                onDecodeFailure = { decodeFailures.increment() },
                onProjectionLagMillis = projectionLag::set,
                onBackfillProgress = backfillProgress::set,
            )
            val repository = JdbcProfileReadRepository(readModel, catalog::version, catalog::guilds)
            val runtime = RuntimeComponents(
                config = config,
                catalog = catalog,
                repository = repository,
                projection = projection,
                metrics = metrics,
                authenticator = RequestAuthenticator(
                    config.security.apiKeySha256,
                    config.server.trustedProxies,
                ),
                rateLimiter = TokenBucketRateLimiter(config.security),
                rateLimitRejections = Counter.builder("galithin_rate_limit_rejections_total").register(metrics),
                cache = ResponseCache(),
                openGraphImages = OpenGraphImageService(config.openGraph),
                openGraphCacheHits = Counter.builder("galithin_opengraph_cache_hits_total").register(metrics),
                openGraphCacheMisses = Counter.builder("galithin_opengraph_cache_misses_total").register(metrics),
                openGraphRenderFailures = Counter.builder("galithin_opengraph_render_failures_total").register(metrics),
                openGraphRateLimitRejections = Counter.builder("galithin_opengraph_rate_limit_rejections_total")
                    .register(metrics),
                pools = listOf(readModel, huskSync, core, luckPerms),
            )
            projection.start()
            return runtime
        }
    }
}

fun Application.galithinModule(runtime: RuntimeComponents) {
    val appLogger = environment.log
    install(CallLogging)
    install(Compression) {
        gzip()
        deflate()
    }
    install(ContentNegotiation) {
        jackson {
            registerKotlinModule()
            registerKotlinxJsonElementSupport()
            disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        }
    }
    install(MicrometerMetrics) { registry = runtime.metrics }
    install(CORS) {
        allowMethod(io.ktor.http.HttpMethod.Get)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.IfNoneMatch)
        runtime.config.server.corsOrigins.forEach { origin ->
            val uri = URI(origin)
            allowHost(uri.authority, schemes = listOf(uri.scheme))
        }
    }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, error ->
            call.respondProblem(HttpStatusCode.BadRequest, "Bad request", error.message ?: "Invalid request")
        }
        exception<ResourceNotFound> { call, error ->
            call.respondProblem(HttpStatusCode.NotFound, "Not found", error.message ?: "Resource not found")
        }
        exception<Throwable> { call, error ->
            appLogger.error("Unhandled request failure", error)
            call.respondProblem(HttpStatusCode.InternalServerError, "Internal server error", "The request could not be completed")
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respondProblem(status, "Not found", "No resource exists at this path")
        }
    }

    routing {
        get("/health/live") {
            call.respond(mapOf("status" to "up"))
        }
        get("/health/ready") {
            val status = runtime.repository.status()
            val ready = status.ready && runtime.catalog.ready()
            call.respond(
                if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                mapOf(
                    "status" to if (ready) "ready" else "not_ready",
                    "stale" to status.stale,
                    "catalogVersion" to status.catalogVersion,
                    "projectedAt" to status.projectedAt,
                    "detail" to status.detail,
                ),
            )
        }
        get("/metrics") {
            if (!runtime.authorize(call, requireAuthentication = true)) return@get
            call.respondText(runtime.metrics.scrape(), ContentType.parse("text/plain; version=0.0.4"))
        }
        get("/openapi.json") {
            val content = javaClass.classLoader.getResource("static/openapi.json")?.readText()
                ?: error("OpenAPI resource is missing")
            call.response.header(HttpHeaders.CacheControl, "public, max-age=300")
            call.respondText(content, ContentType.Application.Json)
        }

        route("/api/v1") {
            get("/players/{identifier}/opengraph") {
                val identity = runtime.authenticator.identify(call)
                if (identity.invalidCredentials) {
                    call.respondProblem(HttpStatusCode.Unauthorized, "Unauthorized", "A valid Bearer API key is required")
                    return@get
                }
                if (!runtime.rateLimiter.allow(identity)) {
                    runtime.rateLimitRejections.increment()
                    call.respondOpenGraphFallback(runtime.config.openGraph, retryAfterSeconds = 60)
                    return@get
                }

                val identifier = call.parameters["identifier"]?.trim().orEmpty()
                val profile = try {
                    identifier.takeIf(String::isNotBlank)?.let {
                        runtime.cache.get("player:${it.lowercase()}", runtime.config.cache.profile) {
                            runtime.repository.findPlayer(it)
                                ?: throw ResourceNotFound("Player '$it' was not found")
                        }
                    }
                } catch (_: ResourceNotFound) {
                    null
                } catch (error: Exception) {
                    appLogger.warn("Open Graph player validation failed for '{}'", identifier, error)
                    null
                }
                if (profile == null) {
                    call.respondOpenGraphFallback(runtime.config.openGraph)
                    return@get
                }

                when (
                    val result = withContext(Dispatchers.IO) {
                        runtime.openGraphImages.resolve(
                            profile = profile,
                            requestedVersion = call.request.queryParameters["v"],
                            clientKey = runtime.authenticator.clientAddress(call),
                        )
                    }
                ) {
                    is OpenGraphImageResult.Image -> {
                        if (result.cacheHit) runtime.openGraphCacheHits.increment()
                        else runtime.openGraphCacheMisses.increment()
                        call.respondOpenGraphImage(result)
                    }
                    is OpenGraphImageResult.RedirectToCurrent -> call.respondOpenGraphCanonical(result.version)
                    is OpenGraphImageResult.Fallback -> {
                        if (result.reason == FailureReason.RATE_LIMITED) {
                            runtime.openGraphRateLimitRejections.increment()
                        }
                        if (result.reason == FailureReason.RENDER_FAILED) {
                            runtime.openGraphRenderFailures.increment()
                            appLogger.warn("Open Graph render failed for player '{}'", profile.name, result.error)
                        }
                        call.respondOpenGraphFallback(runtime.config.openGraph, result.retryAfterSeconds)
                    }
                }
            }
            get("/players/{identifier}") {
                if (!runtime.authorize(call)) return@get
                val identifier = call.parameters["identifier"]?.trim().orEmpty()
                require(identifier.isNotBlank()) { "A player name or UUID is required" }
                val profile = runtime.cache.get("player:${identifier.lowercase()}", runtime.config.cache.profile) {
                    runtime.repository.findPlayer(identifier)
                        ?: throw ResourceNotFound("Player '$identifier' was not found")
                }
                call.respondEnvelope(profile, runtime, runtime.config.cache.profile.seconds.toInt())
            }
            get("/guilds/{identifier}") {
                if (!runtime.authorize(call)) return@get
                val identifier = call.parameters["identifier"]?.trim().orEmpty()
                require(identifier.isNotBlank()) { "A guild UUID, name, or tag is required" }
                val guild = runtime.cache.get("guild:${identifier.lowercase()}", runtime.config.cache.profile) {
                    runtime.repository.findGuild(identifier)
                        ?: throw ResourceNotFound("Guild '$identifier' was not found")
                }
                call.respondEnvelope(guild, runtime, runtime.config.cache.profile.seconds.toInt())
            }
            get("/leaderboards/players/level") {
                if (!runtime.authorize(call)) return@get
                val limit = call.pageLimit()
                val cursor = call.request.queryParameters["cursor"]
                val query = call.leaderboardQuery()
                val page = runtime.cache.get(
                    "players:$limit:${cursor.orEmpty()}:${query.search?.lowercase().orEmpty()}:${query.around?.toString().orEmpty()}",
                    runtime.config.cache.leaderboard,
                ) {
                    runtime.repository.playerLevelLeaderboard(limit, cursor, query.search, query.around)
                }
                call.respondEnvelope(page, runtime, runtime.config.cache.leaderboard.seconds.toInt())
            }
            get("/leaderboards/guilds/level") {
                if (!runtime.authorize(call)) return@get
                val limit = call.pageLimit()
                val cursor = call.request.queryParameters["cursor"]
                val query = call.leaderboardQuery()
                val page = runtime.cache.get(
                    "guilds:$limit:${cursor.orEmpty()}:${query.search?.lowercase().orEmpty()}:${query.around?.toString().orEmpty()}",
                    runtime.config.cache.leaderboard,
                ) {
                    runtime.repository.guildLevelLeaderboard(limit, cursor, query.search, query.around)
                }
                call.respondEnvelope(page, runtime, runtime.config.cache.leaderboard.seconds.toInt())
            }
            get("/catalog/{kind}") {
                if (!runtime.authorize(call)) return@get
                val requested = call.parameters["kind"]
                if (requested.equals("guilds", ignoreCase = true)) {
                    call.respondCatalog(runtime.catalog.guilds(), runtime)
                } else {
                    val kind = parseCatalogKind(requested)
                    val entries = runtime.catalog.entries(kind)
                    call.respondCatalog(entries, runtime)
                }
            }
        }

        get("/assets/{catalogVersion}/{path...}") {
            if (!runtime.authorize(call)) return@get
            val version = call.parameters["catalogVersion"].orEmpty()
            val path = call.parameters.getAll("path")?.joinToString("/").orEmpty()
            val asset = runtime.catalog.resolveAsset(version, path)
                ?: throw ResourceNotFound("Asset was not found")
            call.response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
            call.respondFile(asset.toFile())
        }
    }
}

private suspend fun ApplicationCall.respondOpenGraphImage(result: OpenGraphImageResult.Image) {
    val etag = "\"og-${result.version}\""
    response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
    response.header(HttpHeaders.ETag, etag)
    response.header("X-Content-Type-Options", "nosniff")
    if (request.headers[HttpHeaders.IfNoneMatch]?.split(',')?.map(String::trim)?.contains(etag) == true) {
        respond(HttpStatusCode.NotModified)
        return
    }
    respondFile(result.path.toFile())
}

private suspend fun ApplicationCall.respondOpenGraphCanonical(version: String) {
    response.header(HttpHeaders.Location, "${request.uri.substringBefore('?')}?v=$version")
    response.header(HttpHeaders.CacheControl, "public, max-age=300")
    respond(HttpStatusCode.TemporaryRedirect)
}

private suspend fun ApplicationCall.respondOpenGraphFallback(
    config: OpenGraphConfig,
    retryAfterSeconds: Int? = null,
) {
    response.header(HttpHeaders.Location, config.fallbackImageUrl)
    response.header(HttpHeaders.CacheControl, "public, max-age=300, stale-while-revalidate=3600")
    retryAfterSeconds?.let { response.header(HttpHeaders.RetryAfter, it.toString()) }
    respond(HttpStatusCode.TemporaryRedirect)
}

private class ResourceNotFound(message: String) : RuntimeException(message)

private suspend fun RuntimeComponents.authorize(
    call: ApplicationCall,
    requireAuthentication: Boolean = false,
): Boolean {
    val identity = authenticator.identify(call)
    if (identity.invalidCredentials || (requireAuthentication && !identity.authenticated)) {
        call.respondProblem(HttpStatusCode.Unauthorized, "Unauthorized", "A valid Bearer API key is required")
        return false
    }
    if (!rateLimiter.allow(identity)) {
        rateLimitRejections.increment()
        call.response.header(HttpHeaders.RetryAfter, "60")
        call.respondProblem(HttpStatusCode.TooManyRequests, "Too many requests", "The request rate limit was exceeded")
        return false
    }
    return true
}

private fun ApplicationCall.pageLimit(): Int {
    val raw = request.queryParameters["limit"] ?: return 50
    return raw.toIntOrNull()?.coerceIn(1, 100) ?: throw IllegalArgumentException("limit must be an integer")
}

private data class LeaderboardQuery(val search: String?, val around: Long?)

private fun ApplicationCall.leaderboardQuery(): LeaderboardQuery {
    val search = request.queryParameters["search"]?.trim()?.takeIf(String::isNotEmpty)
    require(search == null || search.length <= 64) { "search must not exceed 64 characters" }
    val around = request.queryParameters["around"]?.let { raw ->
        raw.toLongOrNull()?.takeIf { it > 0 }
            ?: throw IllegalArgumentException("around must be a positive integer")
    }
    require(search == null || around == null) { "search and around cannot be used together" }
    return LeaderboardQuery(search, around)
}

private suspend fun ApplicationCall.respondEnvelope(
    data: Any,
    runtime: RuntimeComponents,
    cacheSeconds: Int,
) {
    val status = runtime.repository.status()
    val response = ApiResponse(
        data,
        ResponseMeta(
            catalogVersion = runtime.catalog.version(),
            sourceSavedAt = status.sourceSavedAt,
            projectedAt = status.projectedAt,
            stale = status.stale,
        ),
    )
    this.response.header(HttpHeaders.CacheControl, "public, max-age=$cacheSeconds, stale-while-revalidate=${cacheSeconds * 6}")
    respondWithEtag(response)
}

private suspend fun ApplicationCall.respondCatalog(data: Any, runtime: RuntimeComponents) {
    val response = ApiResponse(
        data,
        ResponseMeta(
            catalogVersion = runtime.catalog.version(),
            sourceSavedAt = null,
            projectedAt = Instant.now().toString(),
            stale = false,
        ),
    )
    this.response.header(HttpHeaders.CacheControl, "public, max-age=${runtime.config.cache.catalog.seconds}")
    respondWithEtag(response)
}

private suspend fun ApplicationCall.respondWithEtag(value: Any) {
    val json = API_JSON.writeValueAsString(value)
    val digest = MessageDigest.getInstance("SHA-256").digest(json.toByteArray())
        .joinToString("") { "%02x".format(it) }
    val etag = "\"$digest\""
    response.header(HttpHeaders.ETag, etag)
    if (request.headers[HttpHeaders.IfNoneMatch]?.split(',')?.map(String::trim)?.contains(etag) == true) {
        respond(HttpStatusCode.NotModified)
        return
    }
    respondText(json, ContentType.Application.Json)
}

private suspend fun ApplicationCall.respondProblem(
    status: HttpStatusCode,
    title: String,
    detail: String,
) {
    val problem = ProblemDetail(
        type = "https://galithin.com/problems/${status.value}",
        title = title,
        status = status.value,
        detail = detail,
        instance = request.path(),
    )
    respondText(
        API_JSON.writeValueAsString(problem),
        ContentType.parse("application/problem+json"),
        status,
    )
}

private fun parseCatalogKind(value: String?): CatalogKind =
    try {
        CatalogKind.valueOf(value.orEmpty().uppercase())
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("Unknown catalog kind '${value.orEmpty()}'")
    }

private val API_JSON: ObjectMapper = jacksonObjectMapper()
    .registerKotlinModule()
    .registerKotlinxJsonElementSupport()
    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
