package dev.guisousa.minhatv.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreTests {
    private class MemStorage : HealthStorage {
        private val map = LinkedHashMap<String, Long>()
        override fun getLong(key: String): Long = map[key] ?: 0L
        override fun putLongs(entries: Map<String, Long>) { map.putAll(entries) }
        override fun removeKeys(keys: Collection<String>) { keys.forEach { map.remove(it) } }
        override fun allEntries(): Map<String, Long> = HashMap(map)
    }

    @Test
    fun sourceHealthDoesNotCollideOnMalformedUrl() {
        var now = 1_000_000L
        val health = SourceHealthCore { now }
        health.attach(MemStorage())

        assertNull(health.host("http://bad url with spaces/live/1.ts"))
        health.markFailed("http://bad url with spaces/live/1.ts")
        // Outra URL malformada diferente não deve herdar penalidade de host vazio
        assertEquals(2, health.score("http://another bad url/live/2.ts"))
        assertEquals(4, health.score("http://bad url with spaces/live/1.ts"))
    }

    @Test
    fun sourceHealthOrdersWorkingFirstAndPersistsTsOnly() {
        var now = 1_000_000L
        val health = SourceHealthCore { now }
        health.attach(MemStorage())

        val u1 = "http://srv1.test/live/1.ts"
        val u2 = "http://srv2.test/live/2.ts"
        val u3 = "http://srv3.test/live/3.ts"
        health.markFailed(u1)
        health.markWorking(u3)
        health.markTsOnly(u3)

        assertTrue(health.tsOnly(u3))
        assertFalse(health.tsOnly(u2))
        assertEquals(listOf(u3, u2, u1), health.order(listOf(u1, u2, u3)) { it })
    }

    @Test
    fun liveBackoffRespects401AndProgressiveDelay() {
        assertEquals(2_500L, LiveBackoff.delayMs(1, 401))
        assertEquals(2_500L, LiveBackoff.delayMs(1, 403))
        assertEquals(1_000L, LiveBackoff.delayMs(1))
        assertEquals(2_000L, LiveBackoff.delayMs(2))
        assertEquals(3_000L, LiveBackoff.delayMs(3))
        assertEquals(4_000L, LiveBackoff.delayMs(4))
        assertEquals(5_000L, LiveBackoff.delayMs(8))
    }

    @Test
    fun ordemAoVivoPutsPrincipaisFirst() {
        val cats = listOf(
            Cat("esportes", "Esportes"),
            Cat("principais", "Principais", ids = listOf(10L, 20L)),
            Cat("filmes", "Filmes"),
        )
        assertEquals(listOf("principais", "esportes", "filmes"), ordemAoVivo(cats).map { it.id })
        assertEquals(listOf("B", "A"), canaisPorIds(listOf(20L, 10L, 99L, 20L), mapOf(10L to "A", 20L to "B")))
    }

    @Test
    fun updaterParsesCorrectAssetPerPlatform() {
        val sample = """
            {
              "tag_name": "v1.0.160",
              "html_url": "https://github.com/guisousaoliveira/minha-tv/releases/tag/v1.0.160",
              "body": "Melhorias no ao vivo",
              "assets": [
                {"name": "MinhaTV.apk", "browser_download_url": "https://ex/MinhaTV.apk"},
                {"name": "Mobile-BadWolfTV.apk", "browser_download_url": "https://ex/Mobile.apk"},
                {"name": "BadWolfTV-Windows.exe", "browser_download_url": "https://ex/Win.exe"},
                {"name": "BadWolfTV-LG.ipk", "browser_download_url": "https://ex/LG.ipk"},
                {"name": "BadWolfEsportes-LG.ipk", "browser_download_url": "https://ex/Esportes.ipk"}
              ]
            }
        """.trimIndent()
        assertEquals("https://ex/MinhaTV.apk", UpdaterCore.parseRelease(sample, AppPlatform.ANDROID_TV, "1.0.150", 150)?.downloadUrl)
        assertEquals("https://ex/Mobile.apk", UpdaterCore.parseRelease(sample, AppPlatform.ANDROID_MOBILE, "1.0.150", 150)?.downloadUrl)
        assertEquals("https://ex/Win.exe", UpdaterCore.parseRelease(sample, AppPlatform.WINDOWS, "1.0.150")?.downloadUrl)
        assertEquals("https://ex/LG.ipk", UpdaterCore.parseRelease(sample, AppPlatform.WEBOS_TV, "1.0.150")?.downloadUrl)
        assertEquals("https://ex/Esportes.ipk", UpdaterCore.parseRelease(sample, AppPlatform.WEBOS_ESPORTES, "1.0.150")?.downloadUrl)
        assertNull(UpdaterCore.parseRelease(sample, AppPlatform.WINDOWS, "1.0.160"))
        assertTrue(UpdaterCore.compareVersions("1.0.160", "1.0.99") > 0)
    }
}
