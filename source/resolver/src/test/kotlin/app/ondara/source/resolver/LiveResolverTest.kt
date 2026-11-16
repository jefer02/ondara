package app.ondara.source.resolver

import app.ondara.core.model.AudioQuality
import app.ondara.core.network.HttpClientFactory
import app.ondara.source.innertube.InnerTubeClient
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/** Pruebas contra YouTube real. Solo corren con `ONDARA_LIVE=1` (no en CI: dependen de la red). */
@EnabledIfEnvironmentVariable(named = "ONDARA_LIVE", matches = "1")
class LiveResolverTest {
    private val okHttp = HttpClientFactory.okHttp()
    private val innerTube = InnerTubeClient(HttpClientFactory.create(okHttp))

    private fun assertPlayable(strategy: StreamStrategy) = runBlocking {
        val stream = strategy.resolve("5NV6Rdv1a3I", AudioQuality.HIGH).getOrThrow()
        println("${strategy.name}: ${stream.mimeType} ${stream.bitrate} bps loud=${stream.loudnessDb} len=${stream.contentLength}")
        val request = Request.Builder().url(stream.url).header("Range", "bytes=0-2047").build()
        okHttp.newCall(request).execute().use { assertTrue(it.code in 200..299, "HTTP ${it.code}") }
        assertTrue(stream.expiresAtMs > System.currentTimeMillis())
    }

    @Test
    fun `newpipe resolves a playable url`() = assertPlayable(NewPipeStrategy(okHttp))

    @Test
    fun `innertube resolves a playable url`() = assertPlayable(InnerTubeClientStrategy(innerTube))

    @Test
    fun `catalog endpoints answer`() = runBlocking {
        assertTrue(innerTube.home(0).isNotEmpty())
        assertTrue(innerTube.next("5NV6Rdv1a3I").size > 5)
        println("lyrics: " + innerTube.lyrics("5NV6Rdv1a3I")?.take(60))
    }
}
