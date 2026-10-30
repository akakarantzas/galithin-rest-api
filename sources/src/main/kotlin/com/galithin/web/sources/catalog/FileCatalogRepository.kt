package com.galithin.web.sources.catalog

import com.fasterxml.jackson.core.type.TypeReference
import com.galithin.web.domain.CatalogEntry
import com.galithin.web.domain.CatalogKind
import com.galithin.web.domain.CatalogManifest
import com.galithin.web.domain.CatalogRepository
import com.galithin.web.domain.GuildCatalog
import com.galithin.web.sources.objectMapper
import com.galithin.web.sources.text.GlyphDefinition
import com.galithin.web.sources.text.GlyphRegistry
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import org.slf4j.LoggerFactory

class FileCatalogRepository(
    private val bundlePath: Path,
    reloadInterval: Duration = Duration.ofSeconds(2),
) : CatalogRepository, AutoCloseable {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val snapshot = AtomicReference(CatalogSnapshot.empty())
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread.ofPlatform().daemon(true).name("content-bundle-watcher").unstarted(runnable)
    }
    private var observedModified = Long.MIN_VALUE

    init {
        reload()
        executor.scheduleWithFixedDelay(
            ::reloadIfChanged,
            reloadInterval.toMillis(),
            reloadInterval.toMillis(),
            TimeUnit.MILLISECONDS,
        )
    }

    override fun version(): String = snapshot.get().manifest?.catalogVersion ?: "unavailable"

    override fun entries(kind: CatalogKind): List<CatalogEntry> =
        snapshot.get().entries[kind].orEmpty()

    override fun entry(kind: CatalogKind, id: String): CatalogEntry? =
        snapshot.get().entries[kind].orEmpty().firstOrNull { it.id == id }

    override fun guilds(): GuildCatalog =
        requireNotNull(snapshot.get().guilds) { "Guild content is unavailable" }

    override fun resolveAsset(version: String, relativePath: String): Path? {
        val active = snapshot.get()
        if (active.manifest?.catalogVersion != version) return null
        val normalized = relativePath.replace('\\', '/').trimStart('/')
        if (normalized.contains("..") || normalized.isBlank()) return null
        val resolved = active.root?.resolve(normalized)?.normalize() ?: return null
        if (!resolved.startsWith(active.root) || !resolved.isRegularFile()) return null
        return resolved
    }

    override fun ready(): Boolean = snapshot.get().manifest != null

    fun glyphRegistry(): GlyphRegistry = snapshot.get().glyphs

    fun reload() {
        val modified = runCatching { Files.getLastModifiedTime(bundlePath).toMillis() }.getOrNull()
        try {
            val loaded = loadSnapshot(bundlePath)
            val previousRoot = snapshot.getAndSet(loaded).temporaryRoot
            previousRoot?.let(::deleteRecursively)
            logger.info("Loaded web content bundle {}", loaded.manifest?.catalogVersion)
        } catch (error: Exception) {
            logger.error("Could not load content bundle {}; keeping the last valid version", bundlePath, error)
        } finally {
            // An unchanged invalid bundle should be reported once, not every watcher tick.
            // If it is atomically replaced while loading, the next tick sees the new timestamp.
            modified?.let { observedModified = it }
        }
    }

    private fun reloadIfChanged() {
        try {
            if (!Files.exists(bundlePath)) return
            val modified = Files.getLastModifiedTime(bundlePath).toMillis()
            if (modified != observedModified) reload()
        } catch (error: Exception) {
            logger.warn("Could not inspect content bundle {}", bundlePath, error)
        }
    }

    private fun loadSnapshot(path: Path): CatalogSnapshot {
        require(Files.exists(path)) { "Content bundle does not exist: $path" }
        val temporary = if (path.isDirectory()) null else Files.createTempDirectory("galithin-content-")
        val root = temporary ?: path.toAbsolutePath().normalize()
        if (temporary != null) extractZip(path, temporary)

        try {
            val manifestPath = root.resolve("manifest.json")
            val manifest = objectMapper.readValue(manifestPath.toFile(), CatalogManifest::class.java)
            require(manifest.schemaVersion == SUPPORTED_SCHEMA_VERSION) {
                "Unsupported catalog schema ${manifest.schemaVersion}"
            }
            verifyFiles(root, manifest)

            val entries = CatalogKind.entries.associateWith { kind ->
                val name = kind.name.lowercase()
                val file = root.resolve("catalog/$name.json")
                if (!file.isRegularFile()) emptyList() else objectMapper.readValue(
                    file.toFile(),
                    object : TypeReference<List<CatalogEntry>>() {},
                )
            }
            val glyphFile = root.resolve("catalog/glyphs.json")
            val glyphs = if (glyphFile.isRegularFile()) {
                GlyphRegistry(
                    objectMapper.readValue(
                        glyphFile.toFile(),
                        object : TypeReference<List<GlyphDefinition>>() {},
                    ),
                    loadShiftAdvances(root),
                )
            } else {
                GlyphRegistry(shiftAdvances = loadShiftAdvances(root))
            }
            val guildFile = root.resolve("catalog/guilds.json")
            require("catalog/guilds.json" in manifest.files) {
                "Content manifest does not declare catalog/guilds.json"
            }
            require(guildFile.isRegularFile()) { "Missing bundle file: catalog/guilds.json" }
            val guilds = objectMapper.readValue(guildFile.toFile(), GuildCatalog::class.java)
            validateGuildCatalog(guilds)
            return CatalogSnapshot(manifest, entries, guilds, glyphs, root, temporary)
        } catch (error: Exception) {
            temporary?.let(::deleteRecursively)
            throw error
        }
    }

    private fun extractZip(bundle: Path, destination: Path) {
        ZipFile(bundle.toFile()).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val output = destination.resolve(entry.name).normalize()
                require(output.startsWith(destination)) { "Unsafe ZIP entry: ${entry.name}" }
                if (entry.isDirectory) {
                    Files.createDirectories(output)
                } else {
                    Files.createDirectories(output.parent)
                    zip.getInputStream(entry).use { input ->
                        Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
        }
    }

    private fun verifyFiles(root: Path, manifest: CatalogManifest) {
        manifest.files.forEach { (relative, expected) ->
            require(!relative.contains("..")) { "Unsafe manifest path: $relative" }
            val file = root.resolve(relative).normalize()
            require(file.startsWith(root) && file.isRegularFile()) { "Missing bundle file: $relative" }
            val actual = Files.newInputStream(file).use(::sha256)
            require(actual.equals(expected, ignoreCase = true)) { "Checksum mismatch: $relative" }
        }
    }

    private fun loadShiftAdvances(root: Path): Map<Int, Int> {
        val shiftFile = root.resolve("assets/nexo/font/shift.json")
        if (!shiftFile.isRegularFile()) return emptyMap()
        val providers = objectMapper.readTree(shiftFile.toFile()).path("providers")
        if (!providers.isArray) return emptyMap()
        return buildMap {
            providers.filter { it.path("type").textValue() == "space" }.forEach { provider ->
                val advances = provider.path("advances")
                advances.fieldNames().forEachRemaining { character ->
                    if (character.codePointCount(0, character.length) != 1) return@forEachRemaining
                    val width = advances.path(character)
                    if (width.isIntegralNumber && width.canConvertToInt()) {
                        put(character.codePointAt(0), width.intValue())
                    }
                }
            }
        }
    }

    private fun validateGuildCatalog(guilds: GuildCatalog) {
        require(guilds.baseExperienceCost > 0) { "Guild base experience cost must be positive" }
        require(guilds.experienceIncreasePerLevel.isFinite() && guilds.experienceIncreasePerLevel > 0) {
            "Guild experience increase per level must be positive"
        }
        require(guilds.maximumLevel > 0) { "Guild maximum level must be positive" }
        require(guilds.rewards.all { (level, reward) ->
            level > 0 &&
                (reward.maximumMembers ?: 0) >= 0 &&
                (reward.maximumClaims ?: 0) >= 0
        }) { "Guild reward levels must be positive and limits must be non-negative" }
    }

    override fun close() {
        executor.shutdownNow()
        snapshot.get().temporaryRoot?.let(::deleteRecursively)
    }

    private data class CatalogSnapshot(
        val manifest: CatalogManifest?,
        val entries: Map<CatalogKind, List<CatalogEntry>>,
        val guilds: GuildCatalog?,
        val glyphs: GlyphRegistry,
        val root: Path?,
        val temporaryRoot: Path?,
    ) {
        companion object {
            fun empty() = CatalogSnapshot(null, emptyMap(), null, GlyphRegistry(), null, null)
        }
    }

    companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1

        private fun sha256(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun deleteRecursively(path: Path) {
            if (!Files.exists(path)) return
            Files.walk(path).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }
}
