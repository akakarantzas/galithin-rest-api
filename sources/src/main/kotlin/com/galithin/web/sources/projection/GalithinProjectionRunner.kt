package com.galithin.web.sources.projection

import com.galithin.web.domain.CatalogRepository
import com.galithin.web.domain.ProjectionRunner
import com.galithin.web.sources.core.CoreDataReader
import com.galithin.web.sources.database.ProjectedGuild
import com.galithin.web.sources.database.ProjectedGuildMember
import com.galithin.web.sources.database.ProjectedPlayer
import com.galithin.web.sources.database.ProjectionCheckpoint
import com.galithin.web.sources.database.ReadModelWriter
import com.galithin.web.sources.husksync.HuskSyncSnapshotReader
import com.galithin.web.sources.husksync.PackedSnapshotDecoder
import com.galithin.web.sources.husksync.PackedSnapshotRow
import com.galithin.web.sources.husksync.ProfileSnapshotDecoder
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

class GalithinProjectionRunner(
    private val snapshots: HuskSyncSnapshotReader,
    private val packedDecoder: PackedSnapshotDecoder,
    private val profileDecoder: ProfileSnapshotDecoder,
    private val coreData: CoreDataReader,
    private val writer: ReadModelWriter,
    private val catalog: CatalogRepository,
    private val pollInterval: Duration = Duration.ofSeconds(2),
    private val reconciliationInterval: Duration = Duration.ofMinutes(5),
    private val batchSize: Int = 250,
    private val onDecodeFailure: (Throwable) -> Unit = {},
    private val onProjectionLagMillis: (Long) -> Unit = {},
    private val onBackfillProgress: (Long) -> Unit = {},
) : ProjectionRunner {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("projection"))
    private val started = AtomicBoolean()
    private var job: Job? = null

    override fun start() {
        if (!started.compareAndSet(false, true)) return
        job = scope.launch { runProjection() }
    }

    private suspend fun runProjection() {
        var lastReconciliation = Instant.EPOCH
        var waitingForCatalog = false
        while (true) {
            if (!catalog.ready()) {
                if (!waitingForCatalog) {
                    logger.warn("Projection paused until a valid content bundle is available")
                    waitingForCatalog = true
                }
                delay(pollInterval.toMillis())
                continue
            }
            waitingForCatalog = false
            try {
                val core = withContext(Dispatchers.IO) { coreData.refresh() }
                withContext(Dispatchers.IO) {
                    writer.synchronizeGuilds(core.guilds.map { guild ->
                        ProjectedGuild(
                            guild = guild.leaderboard,
                            currentExperience = guild.currentExperience,
                            memberLimitBonus = guild.memberLimitBonus,
                            claimCount = guild.claimCount,
                            claimLimitBonus = guild.claimLimitBonus,
                            members = guild.members.map { member ->
                                ProjectedGuildMember(
                                    playerUuid = member.playerUuid,
                                    fallbackName = member.fallbackName,
                                    role = member.role,
                                    totalContribution = member.totalContribution,
                                )
                            },
                            active = true,
                        )
                    })
                }

                var checkpoint = withContext(Dispatchers.IO) { writer.checkpoint(SOURCE_NAME) }
                if (checkpoint?.timestamp == null) {
                    bootstrap(core)
                    checkpoint = withContext(Dispatchers.IO) { snapshots.latestCheckpoint() }
                    writer.markSuccess(SOURCE_NAME, checkpoint, catalog.version())
                } else {
                    poll(checkpoint, core)
                }

                if (Duration.between(lastReconciliation, Instant.now()) >= reconciliationInterval) {
                    reconcile(core)
                    lastReconciliation = Instant.now()
                }
            } catch (error: Exception) {
                logger.error("Projection cycle failed", error)
                withContext(Dispatchers.IO) {
                    writer.markFailure(SOURCE_NAME, catalog.version(), error)
                }
            }
            delay(pollInterval.toMillis())
        }
    }

    private suspend fun bootstrap(core: com.galithin.web.sources.core.CoreDataSnapshot) {
        logger.info("Starting HuskSync latest-snapshot backfill")
        var offset = 0L
        while (true) {
            val rows = withContext(Dispatchers.IO) { snapshots.latest(offset, batchSize) }
            if (rows.isEmpty()) break
            rows.forEach { project(it, core) }
            offset += rows.size
            onBackfillProgress(offset)
            if (rows.size < batchSize) break
        }
        logger.info("Completed HuskSync latest-snapshot backfill for {} players", offset)
    }

    private suspend fun poll(
        initial: ProjectionCheckpoint,
        core: com.galithin.web.sources.core.CoreDataSnapshot,
    ) {
        var checkpoint = initial
        while (true) {
            val rows = withContext(Dispatchers.IO) { snapshots.after(checkpoint, batchSize) }
            if (rows.isEmpty()) break
            rows.forEach { row ->
                try {
                    project(row, core)
                } catch (error: Exception) {
                    logger.error("Could not project HuskSync snapshot {}", row.versionUuid, error)
                }
                checkpoint = ProjectionCheckpoint(row.savedAt, row.versionUuid)
            }
            writer.markSuccess(SOURCE_NAME, checkpoint, catalog.version())
            if (rows.size < batchSize) break
        }
    }

    private suspend fun reconcile(core: com.galithin.web.sources.core.CoreDataSnapshot) {
        var offset = 0L
        while (true) {
            val rows = withContext(Dispatchers.IO) { snapshots.latest(offset, batchSize) }
            if (rows.isEmpty()) break
            rows.forEach { row ->
                try {
                    project(row, core)
                } catch (error: Exception) {
                    logger.warn("Could not reconcile snapshot {}", row.versionUuid, error)
                }
            }
            offset += rows.size
            if (rows.size < batchSize) break
        }
    }

    private suspend fun project(
        row: PackedSnapshotRow,
        core: com.galithin.web.sources.core.CoreDataSnapshot,
    ) = withContext(Dispatchers.IO) {
        val decoded = try {
            val snapshot = packedDecoder.decode(row.packedData)
            val fallbacks = if (profileDecoder.needsCoreFallback(snapshot)) {
                snapshots.before(row).mapNotNull { fallback ->
                    runCatching { packedDecoder.decode(fallback.packedData) }.getOrNull()
                }
            } else {
                emptyList()
            }
            profileDecoder.decode(row, snapshot, core, fallbacks)
        } catch (error: Exception) {
            onDecodeFailure(error)
            throw error
        }
        writer.upsertPlayer(
            ProjectedPlayer(
                profile = decoded.profile,
                eligible = decoded.eligible,
                sourceVersionUuid = row.versionUuid,
                sourceSavedAt = row.savedAt,
                projectedAt = Instant.now().toString(),
                stale = false,
            )
        )
        onProjectionLagMillis(
            Duration.between(Instant.parse(row.savedAt), Instant.now()).toMillis().coerceAtLeast(0)
        )
    }

    override fun close() {
        job?.cancel()
        scope.cancel()
    }

    companion object {
        private const val SOURCE_NAME = "husksync-v4"
    }
}
