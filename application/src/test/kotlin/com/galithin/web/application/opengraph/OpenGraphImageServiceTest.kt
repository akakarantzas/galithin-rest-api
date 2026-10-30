package com.galithin.web.application.opengraph

import com.galithin.web.application.OpenGraphConfig
import com.galithin.web.domain.AssetReference
import com.galithin.web.domain.DungeonStatistics
import com.galithin.web.domain.EquipmentItem
import com.galithin.web.domain.EquipmentSlot
import com.galithin.web.domain.PlayerProfile
import com.galithin.web.domain.PlayerStatistics
import com.galithin.web.domain.RankPresentation
import com.galithin.web.domain.RichText
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class OpenGraphImageServiceTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `version matches the website contract and ignores unrelated profile updates`() {
        val before = profile()
        val unrelatedUpdate = before.copy(experience = 50_000, statistics = before.statistics.copy(kills = 99))
        val equipmentUpdate = before.copy(
            equipment = before.equipment.map { it.copy(itemId = "gold-helmet") },
        )

        assertEquals("268b89211bebd889", OpenGraphImageService.playerOpenGraphVersion(before))
        assertEquals(
            OpenGraphImageService.playerOpenGraphVersion(before),
            OpenGraphImageService.playerOpenGraphVersion(unrelatedUpdate),
        )
        assertNotEquals(
            OpenGraphImageService.playerOpenGraphVersion(before),
            OpenGraphImageService.playerOpenGraphVersion(equipmentUpdate),
        )
    }

    @Test
    fun `redirects to the current version then renders and reuses the persistent cache`() {
        val renders = AtomicInteger()
        val service = service { _, _ ->
            renders.incrementAndGet()
            PNG
        }
        val profile = profile()
        val version = OpenGraphImageService.playerOpenGraphVersion(profile)

        assertEquals(OpenGraphImageResult.RedirectToCurrent(version), service.resolve(profile, null, "client"))

        val generated = assertIs<OpenGraphImageResult.Image>(service.resolve(profile, version, "client"))
        assertEquals(false, generated.cacheHit)
        assertTrue(generated.path.toFile().isFile)

        val cached = assertIs<OpenGraphImageResult.Image>(service.resolve(profile, version, "client"))
        assertTrue(cached.cacheHit)
        assertEquals(generated.path, cached.path)
        assertEquals(1, renders.get())
    }

    @Test
    fun `serves an already generated historical version after equipment changes`() {
        val service = service { _, _ -> PNG }
        val before = profile()
        val oldVersion = OpenGraphImageService.playerOpenGraphVersion(before)
        assertIs<OpenGraphImageResult.Image>(service.resolve(before, oldVersion, "client"))

        val after = before.copy(equipment = before.equipment.map { it.copy(itemId = "gold-helmet") })
        val historical = assertIs<OpenGraphImageResult.Image>(service.resolve(after, oldVersion, "client"))

        assertTrue(historical.cacheHit)
        assertEquals(oldVersion, historical.version)
    }

    @Test
    fun `rate limits cache misses without affecting cache hits`() {
        val service = OpenGraphImageService(
            config().copy(rendersPerIpPerHour = 1),
            PlayerImageRenderer { _, _ -> PNG },
        )
        val first = profile()
        val firstVersion = OpenGraphImageService.playerOpenGraphVersion(first)
        assertIs<OpenGraphImageResult.Image>(service.resolve(first, firstVersion, "client"))
        assertIs<OpenGraphImageResult.Image>(service.resolve(first, firstVersion, "client"))

        val second = profile(uuid = "another-player")
        val secondVersion = OpenGraphImageService.playerOpenGraphVersion(second)
        val rejected = assertIs<OpenGraphImageResult.Fallback>(service.resolve(second, secondVersion, "client"))

        assertEquals(FailureReason.RATE_LIMITED, rejected.reason)
        assertEquals(300, rejected.retryAfterSeconds)
    }

    @Test
    fun `uses the fallback when the renderer does not return a PNG`() {
        val service = service { _, _ -> "not an image".toByteArray() }
        val profile = profile()
        val version = OpenGraphImageService.playerOpenGraphVersion(profile)

        val fallback = assertIs<OpenGraphImageResult.Fallback>(service.resolve(profile, version, "client"))

        assertEquals(FailureReason.RENDER_FAILED, fallback.reason)
    }

    @Test
    fun `rejects a cache miss when render capacity is exhausted`() {
        val renderStarted = CountDownLatch(1)
        val releaseRender = CountDownLatch(1)
        val service = OpenGraphImageService(
            config().copy(maxConcurrentRenders = 1),
            PlayerImageRenderer { _, _ ->
                renderStarted.countDown()
                check(releaseRender.await(2, TimeUnit.SECONDS))
                PNG
            },
        )
        val first = profile()
        val second = profile(uuid = "another-player")
        val executor = Executors.newSingleThreadExecutor()

        try {
            val firstRender = executor.submit<OpenGraphImageResult> {
                service.resolve(first, OpenGraphImageService.playerOpenGraphVersion(first), "first-client")
            }
            assertTrue(renderStarted.await(2, TimeUnit.SECONDS))

            val rejected = assertIs<OpenGraphImageResult.Fallback>(
                service.resolve(second, OpenGraphImageService.playerOpenGraphVersion(second), "second-client"),
            )
            assertEquals(FailureReason.CAPACITY_EXHAUSTED, rejected.reason)

            releaseRender.countDown()
            assertIs<OpenGraphImageResult.Image>(firstRender.get(2, TimeUnit.SECONDS))
        } finally {
            releaseRender.countDown()
            executor.shutdownNow()
        }
    }

    private fun service(renderer: PlayerImageRenderer) = OpenGraphImageService(config(), renderer)

    private fun config() = OpenGraphConfig(
        enabled = true,
        renderPageBaseUrl = "https://galithin.com",
        fallbackImageUrl = "https://galithin.com/static/images/statsBg.png",
        cacheDirectory = temporaryDirectory.resolve("opengraph"),
        browserExecutablePath = null,
        maxConcurrentRenders = 2,
        rendersPerIpPerHour = 10,
        renderTimeout = Duration.ofSeconds(25),
        retryAfter = Duration.ofSeconds(300),
    )

    private fun profile(uuid: String = "player-uuid") = PlayerProfile(
        uuid = uuid,
        name = "Player",
        level = 1,
        experience = 0,
        rank = RankPresentation("default", text("Player")),
        badges = emptyList(),
        guild = null,
        equipment = listOf(
            EquipmentItem(
                slot = EquipmentSlot.HEAD,
                itemId = "helmet",
                nexoId = null,
                material = "IRON_HELMET",
                amount = 1,
                itemUuid = null,
                rarity = null,
                tier = null,
                requiredLevel = null,
                durability = null,
                maximumDurability = null,
                repairs = null,
                name = text("Helmet"),
                lore = emptyList(),
                assets = AssetReference(
                    catalogVersion = "1",
                    modelUrl = "/helmet.json",
                    equipmentUrl = "/iron.json",
                    textures = mapOf("layer" to "/iron.png"),
                ),
            ),
        ),
        statistics = PlayerStatistics(0, 0, 0, null, null, 0),
        dungeonStatistics = DungeonStatistics(),
        abilities = emptyList(),
        talents = emptyList(),
    )

    private fun text(value: String) = RichText("{\"text\":\"$value\"}", value, value)

    companion object {
        private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    }
}
