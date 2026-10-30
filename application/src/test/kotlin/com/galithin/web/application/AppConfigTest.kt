package com.galithin.web.application

import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppConfigTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `loads an embedded read model without API MariaDB credentials`() {
        val configPath = temporaryDirectory.resolve("config.yml")
        configPath.writeText(
            """
            server:
              host: 127.0.0.1
              port: 8080
            security: {}
            projection: {}
            exclusions: {}
            cache: {}
            content:
              bundlePath: ./content.zip
            storage:
              readModelPath: ./persistent/api-read-model
              maximumPoolSize: 6
            luckPerms: {}
            databases:
              huskSync:
                jdbcUrl: jdbc:mariadb://database:3306/husksync
                username: existing_husksync_user
                password: existing_husksync_password
              core:
                jdbcUrl: jdbc:mariadb://database:3306/galithin_core
                username: existing_core_user
                password: existing_core_password
              luckPerms:
                jdbcUrl: jdbc:mariadb://database:3306/luckperms
                username: existing_luckperms_user
                password: existing_luckperms_password
            """.trimIndent(),
        )

        val config = AppConfig.load(configPath)

        assertEquals(Path.of("./persistent/api-read-model"), config.readModel.path)
        assertEquals(6, config.readModel.maximumPoolSize)
        assertEquals("existing_husksync_user", config.huskSync.username)
        assertTrue(config.huskSync.readOnly)
        assertTrue(config.core.readOnly)
        assertTrue(config.luckPerms.readOnly)
        assertFalse(config.readModel.path.toString().contains("mariadb"))
        assertTrue(config.openGraph.enabled)
        assertEquals(Path.of("./data/opengraph"), config.openGraph.cacheDirectory)
        assertEquals(2, config.openGraph.maxConcurrentRenders)
    }

    @Test
    fun `uses the default local read model path`() {
        val configPath = temporaryDirectory.resolve("config.yml")
        configPath.writeText(
            """
            content:
              bundlePath: ./content.zip
            databases:
              huskSync: &source
                jdbcUrl: jdbc:mariadb://database:3306/source
                username: existing
                password: existing
              core: *source
              luckPerms: *source
            """.trimIndent(),
        )

        val config = AppConfig.load(configPath)

        assertEquals(Path.of("./data/galithin-read-model"), config.readModel.path)
        assertEquals(8, config.readModel.maximumPoolSize)
    }

    @Test
    fun `loads Open Graph worker settings`() {
        val configPath = temporaryDirectory.resolve("config.yml")
        configPath.writeText(
            """
            openGraph:
              enabled: false
              renderPageBaseUrl: https://preview.galithin.com/
              fallbackImageUrl: https://preview.galithin.com/fallback.png
              cacheDirectory: ./persistent/opengraph
              browserExecutablePath: /usr/bin/chromium
              maxConcurrentRenders: 3
              rendersPerIpPerHour: 12
              renderTimeoutSeconds: 40
              retryAfterSeconds: 90
            content:
              bundlePath: ./content.zip
            databases:
              huskSync: &source
                jdbcUrl: jdbc:mariadb://database:3306/source
                username: existing
                password: existing
              core: *source
              luckPerms: *source
            """.trimIndent(),
        )

        val config = AppConfig.load(configPath)

        assertFalse(config.openGraph.enabled)
        assertEquals("https://preview.galithin.com", config.openGraph.renderPageBaseUrl)
        assertEquals(Path.of("/usr/bin/chromium"), config.openGraph.browserExecutablePath)
        assertEquals(3, config.openGraph.maxConcurrentRenders)
        assertEquals(12, config.openGraph.rendersPerIpPerHour)
        assertEquals(40, config.openGraph.renderTimeout.seconds)
        assertEquals(90, config.openGraph.retryAfter.seconds)
    }
}
