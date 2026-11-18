package app.ondara.source.innertube

import app.ondara.core.network.HttpClientFactory
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/** Pruebas contra YouTube Music real. Solo corren con `ONDARA_LIVE=1` (no en CI: dependen de la red). */
@EnabledIfEnvironmentVariable(named = "ONDARA_LIVE", matches = "1")
class LiveInnerTubeTest {
    private val innerTube = InnerTubeClient(HttpClientFactory.create(HttpClientFactory.okHttp())) { InnerTubeConfig("es", "CO") }

    @Test
    fun `new releases answer with shelves`() = runBlocking {
        val shelves = innerTube.newReleases()
        shelves.forEach { println("novedades: ${it.title} (${it.items.size})") }
        assertTrue(shelves.isNotEmpty() && shelves.all { it.title.isNotBlank() && it.items.isNotEmpty() })
    }

    @Test
    fun `explore answers with shelves`() = runBlocking {
        val shelves = innerTube.explore()
        shelves.forEach { println("explorar: ${it.title} (${it.items.size})") }
        assertTrue(shelves.size >= 2 && shelves.all { it.title.isNotBlank() && it.items.isNotEmpty() })
    }

    @Test
    fun `account endpoints degrade without a valid session`() = runBlocking {
        // Cookie inventada: YouTube la ignora o la rechaza, pero nunca debe propagarse una excepción.
        assertNull(innerTube.accountInfo("SAPISID=invalid; __Secure-3PAPISID=invalid"))
        assertTrue(innerTube.libraryPlaylists().isEmpty())
        assertTrue(innerTube.accountHistory().isEmpty())
        assertTrue(!innerTube.rate("5NV6Rdv1a3I", LikeStatus.LIKE))
    }
}
