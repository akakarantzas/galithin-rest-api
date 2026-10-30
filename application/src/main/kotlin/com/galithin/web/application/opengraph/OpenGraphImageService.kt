package com.galithin.web.application.opengraph

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.galithin.web.application.OpenGraphConfig
import com.galithin.web.domain.PlayerProfile
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.ScreenshotType
import com.microsoft.playwright.options.WaitForSelectorState
import com.microsoft.playwright.options.WaitUntilState
import java.net.URLEncoder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

sealed interface OpenGraphImageResult {
    data class Image(
        val path: Path,
        val version: String,
        val cacheHit: Boolean,
    ) : OpenGraphImageResult

    data class RedirectToCurrent(val version: String) : OpenGraphImageResult

    data class Fallback(
        val reason: FailureReason,
        val retryAfterSeconds: Int? = null,
        val error: Throwable? = null,
    ) : OpenGraphImageResult
}

enum class FailureReason {
    DISABLED,
    RATE_LIMITED,
    CAPACITY_EXHAUSTED,
    RENDER_FAILED,
}

fun interface PlayerImageRenderer {
    fun render(renderUrl: String, timeout: Duration): ByteArray
}

class PlaywrightPlayerImageRenderer(
    private val browserExecutablePath: Path?,
) : PlayerImageRenderer {
    override fun render(renderUrl: String, timeout: Duration): ByteArray {
        val timeoutMillis = timeout.toMillis().toDouble()
        Playwright.create().use { playwright ->
            val launchOptions = BrowserType.LaunchOptions()
                .setHeadless(true)
                .setArgs(listOf("--hide-scrollbars", "--no-sandbox", "--disable-dev-shm-usage"))
            browserExecutablePath?.let(launchOptions::setExecutablePath)

            playwright.chromium().launch(launchOptions).use { browser ->
                browser.newContext(
                    Browser.NewContextOptions()
                        .setViewportSize(1600, 900)
                        .setDeviceScaleFactor(1.0),
                ).use { context ->
                    val page = context.newPage()
                    page.navigate(
                        renderUrl,
                        Page.NavigateOptions()
                            .setWaitUntil(WaitUntilState.NETWORKIDLE)
                            .setTimeout(timeoutMillis),
                    )
                    val image = page.locator("[data-og-player]")
                    image.waitFor(
                        Locator.WaitForOptions()
                            .setState(WaitForSelectorState.VISIBLE)
                            .setTimeout(timeoutMillis),
                    )
                    page.waitForFunction(
                        "() => document.querySelector('[data-og-player]')?.getAttribute('data-render-state') === 'ready'",
                        null,
                        Page.WaitForFunctionOptions().setTimeout(timeoutMillis),
                    )
                    page.evaluate("() => document.fonts.ready")
                    return image.screenshot(
                        Locator.ScreenshotOptions()
                            .setType(ScreenshotType.PNG)
                            .setTimeout(timeoutMillis),
                    )
                }
            }
        }
    }
}

class OpenGraphImageService(
    private val config: OpenGraphConfig,
    private val renderer: PlayerImageRenderer = PlaywrightPlayerImageRenderer(config.browserExecutablePath),
    nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val renderPermits = Semaphore(config.maxConcurrentRenders)
    private val rateLimiter = FixedWindowRateLimiter(config.rendersPerIpPerHour, Duration.ofHours(1), nowMillis)
    private val cacheKeyLocks = Array(64) { Any() }

    fun resolve(
        profile: PlayerProfile,
        requestedVersion: String?,
        clientKey: String,
    ): OpenGraphImageResult {
        val currentVersion = playerOpenGraphVersion(profile)
        if (requestedVersion != currentVersion) {
            val oldImage = requestedVersion
                ?.takeIf(VERSION_PATTERN::matches)
                ?.let { cachedImage(profile, it) }
            return oldImage ?: OpenGraphImageResult.RedirectToCurrent(currentVersion)
        }

        cachedImage(profile, currentVersion)?.let { return it }
        if (!config.enabled) return OpenGraphImageResult.Fallback(FailureReason.DISABLED)

        val cacheKey = "${profile.uuid}:$currentVersion"
        val lock = cacheKeyLocks[(cacheKey.hashCode() and Int.MAX_VALUE) % cacheKeyLocks.size]
        return synchronized(lock) {
            cachedImage(profile, currentVersion)?.let { return@synchronized it }
            if (!rateLimiter.allow(clientKey)) {
                return@synchronized OpenGraphImageResult.Fallback(
                    FailureReason.RATE_LIMITED,
                    config.retryAfter.seconds.toInt(),
                )
            }
            if (!renderPermits.tryAcquire()) {
                return@synchronized OpenGraphImageResult.Fallback(
                    FailureReason.CAPACITY_EXHAUSTED,
                    config.retryAfter.seconds.toInt(),
                )
            }

            try {
                val png = renderer.render(renderUrl(profile.name), config.renderTimeout)
                requireValidPng(png)
                val path = store(profile, currentVersion, png)
                OpenGraphImageResult.Image(path, currentVersion, cacheHit = false)
            } catch (error: Exception) {
                OpenGraphImageResult.Fallback(FailureReason.RENDER_FAILED, error = error)
            } finally {
                renderPermits.release()
            }
        }
    }

    private fun cachedImage(profile: PlayerProfile, version: String): OpenGraphImageResult.Image? {
        val path = cachePath(profile, version)
        return path.takeIf(::isValidPng)?.let {
            OpenGraphImageResult.Image(it, version, cacheHit = true)
        }
    }

    private fun store(profile: PlayerProfile, version: String, png: ByteArray): Path {
        val target = cachePath(profile, version)
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, ".$version-", ".tmp")
        try {
            Files.write(temporary, png)
            try {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
        return target
    }

    private fun cachePath(profile: PlayerProfile, version: String): Path {
        val playerKey = sha256(profile.uuid).joinToString("") { "%02x".format(it) }
        return config.cacheDirectory.resolve(playerKey).resolve("$version.png")
    }

    private fun renderUrl(playerName: String): String {
        val encodedName = URLEncoder.encode(playerName, Charsets.UTF_8).replace("+", "%20")
        return "${config.renderPageBaseUrl}/stats/$encodedName/opengraph"
    }

    private fun isValidPng(path: Path): Boolean = try {
        Files.isRegularFile(path) && Files.size(path) >= PNG_SIGNATURE.size &&
            Files.newInputStream(path).use { input -> input.readNBytes(PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE) }
    } catch (_: Exception) {
        false
    }

    private fun requireValidPng(bytes: ByteArray) {
        require(bytes.size in PNG_SIGNATURE.size..MAX_IMAGE_BYTES && bytes.copyOf(PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)) {
            "The renderer did not return a valid PNG"
        }
    }

    private class FixedWindowRateLimiter(
        private val maximum: Int,
        private val window: Duration,
        private val nowMillis: () -> Long,
    ) {
        private val windows = ConcurrentHashMap<String, Window>()

        fun allow(key: String): Boolean {
            val now = nowMillis()
            val windowMillis = window.toMillis()
            if (!windows.containsKey(key) && windows.size >= MAX_RATE_LIMIT_KEYS) {
                windows.entries.removeIf { it.value.resetAt <= now }
                if (windows.size >= MAX_RATE_LIMIT_KEYS) return false
            }
            val current = windows.compute(key) { _, existing ->
                if (existing == null || existing.resetAt <= now) Window(1, now + windowMillis)
                else existing.apply { count += 1 }
            } ?: return false
            return current.count <= maximum
        }

        private data class Window(var count: Int, val resetAt: Long)
    }

    companion object {
        private val VERSION_JSON = jacksonObjectMapper()
        private val VERSION_PATTERN = Regex("^[a-f0-9]{16}$")
        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        private const val MAX_IMAGE_BYTES = 20 * 1024 * 1024
        private const val MAX_RATE_LIMIT_KEYS = 5_000

        fun playerOpenGraphVersion(profile: PlayerProfile): String {
            val equipment = profile.equipment
                .sortedBy { it.slot.name }
                .map { item ->
                    linkedMapOf<String, Any?>(
                        "slot" to item.slot.name,
                        "itemId" to item.itemId,
                        "nexoId" to item.nexoId,
                        "material" to item.material,
                        "assets" to item.assets?.let { assets ->
                            linkedMapOf(
                                "catalogVersion" to assets.catalogVersion,
                                "modelUrl" to assets.modelUrl,
                                "equipmentUrl" to assets.equipmentUrl,
                                "textures" to assets.textures.toSortedMap(),
                            )
                        },
                    )
                }
            val json = VERSION_JSON.writeValueAsBytes(linkedMapOf("uuid" to profile.uuid, "equipment" to equipment))
            return sha256(json).joinToString("") { "%02x".format(it) }.take(16)
        }

        private fun sha256(value: String): ByteArray = sha256(value.toByteArray(Charsets.UTF_8))

        private fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)
    }
}
