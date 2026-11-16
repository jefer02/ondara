package app.ondara.source.resolver

import app.ondara.core.model.AudioQuality
import app.ondara.core.model.ResolvedStream
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class StreamResolverTest {
    private var clock = 1_000_000L

    private fun stream(id: String, source: String, ttlMs: Long = 6 * 3_600_000L) =
        ResolvedStream(id, "https://x/$id?src=$source", "audio/webm", 160_000, clock + ttlMs, null, null, source)

    private inner class Fake(override val name: String, val fail: Boolean = false, val ttlMs: Long = 6 * 3_600_000L) : StreamStrategy {
        var calls = 0
        override suspend fun resolve(videoId: String, quality: AudioQuality): Result<ResolvedStream> {
            calls++
            delay(10)
            return if (fail) Result.failure(IllegalStateException("$name falló")) else Result.success(stream(videoId, name, ttlMs))
        }
    }

    private fun cache() = ResolvedStreamCache(capacity = 2, safetyMarginMs = 60_000) { clock }

    @Test
    fun `first successful strategy wins and result is cached`() = runTest {
        val a = Fake("a", fail = true)
        val b = Fake("b")
        val c = Fake("c")
        val resolver = StreamResolver(listOf(a, b, c), cache())
        assertEquals("b", resolver.resolve("v1", AudioQuality.HIGH).source)
        assertEquals("b", resolver.resolve("v1", AudioQuality.HIGH).source)
        assertEquals(1, a.calls)
        assertEquals(1, b.calls)
        assertEquals(0, c.calls)
    }

    @Test
    fun `all failures throw with every error collected`() = runTest {
        val resolver = StreamResolver(listOf(Fake("a", true), Fake("b", true)), cache())
        val e = assertThrows<StreamUnavailableException> { resolver.resolve("v1", AudioQuality.HIGH) }
        assertEquals(2, e.errors.size)
        assertFalse(resolver.prefetch("v1", AudioQuality.HIGH))
    }

    @Test
    fun `strategy that throws is treated as failure`() = runTest {
        val thrower = object : StreamStrategy {
            override val name = "boom"
            override suspend fun resolve(videoId: String, quality: AudioQuality): Result<ResolvedStream> = error("boom")
        }
        assertEquals("b", StreamResolver(listOf(thrower, Fake("b")), cache()).resolve("v", AudioQuality.HIGH).source)
    }

    @Test
    fun `expired entries are resolved again`() = runTest {
        val a = Fake("a", ttlMs = 120_000)
        val resolver = StreamResolver(listOf(a), cache())
        resolver.resolve("v1", AudioQuality.HIGH)
        clock += 30_000
        resolver.resolve("v1", AudioQuality.HIGH)
        assertEquals(1, a.calls)
        clock += 40_000 // quedan 50 s < margen de 60 s → caducada
        resolver.resolve("v1", AudioQuality.HIGH)
        assertEquals(2, a.calls)
    }

    @Test
    fun `403 recovery skips the failed strategy and replaces the cache entry`() = runTest {
        val a = Fake("a")
        val b = Fake("b")
        val resolver = StreamResolver(listOf(a, b), cache())
        val first = resolver.resolve("v1", AudioQuality.HIGH)
        val second = resolver.resolveAfterFailure("v1", AudioQuality.HIGH, first)
        assertEquals("b", second.source)
        assertEquals("b", resolver.cached("v1", AudioQuality.HIGH)?.source)
        assertEquals(1, a.calls)
    }

    @Test
    fun `concurrent resolves of the same id hit the network once`() = runTest {
        val a = Fake("a")
        val resolver = StreamResolver(listOf(a), cache())
        List(8) { async { resolver.resolve("v1", AudioQuality.HIGH) } }.awaitAll()
        assertEquals(1, a.calls)
    }

    @Test
    fun `cache evicts least recently used`() {
        val cache = cache()
        cache.put("1", stream("1", "a"))
        cache.put("2", stream("2", "a"))
        cache.get("1")
        cache.put("3", stream("3", "a"))
        assertNull(cache.get("2"))
        assertTrue(cache.get("1") != null && cache.get("3") != null)
        assertEquals(2, cache.size)
    }

    @Test
    fun `expire is read from the url`() {
        assertEquals(1_794_800_000_000, StreamUrls.expiresAtMs("https://r1.googlevideo.com/videoplayback?expire=1794800000&ei=x"))
        assertEquals(1_794_800_000_000, StreamUrls.expiresAtMs("https://r1.googlevideo.com/videoplayback/expire/1794800000/ei/x"))
        assertEquals(5 * 3_600_000L + 7, StreamUrls.expiresAtMs("https://example.com/a.mp3", nowMs = 7))
    }

    @Test
    fun `quality selection prefers opus and respects the cap`() {
        val list = listOf(
            AudioCandidate("aac128", "audio/mp4", 130_567, null, null),
            AudioCandidate("opus50", "audio/webm; codecs=opus", 54_440, null, null),
            AudioCandidate("opus160", "audio/webm; codecs=opus", 146_698, null, null),
            AudioCandidate("aac48", "audio/mp4", 50_068, null, null),
        )
        assertEquals("opus160", list.pick(AudioQuality.HIGH)?.url)
        assertEquals("opus50", list.pick(AudioQuality.LOW)?.url)
        assertEquals("aac128", list.filter { "mp4" in it.mimeType && it.bitrate > 100_000 }.pick(AudioQuality.LOW)?.url)
        assertNull(emptyList<AudioCandidate>().pick(AudioQuality.HIGH))
    }

    @Test
    fun `innertube player response is parsed`() {
        val json = Json.parseToJsonElement(
            """
            {"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"abc"},
             "playerConfig":{"audioConfig":{"loudnessDb":3.5}},
             "streamingData":{"adaptiveFormats":[
               {"itag":137,"mimeType":"video/mp4; codecs=\"avc1\"","bitrate":4000000,"url":"https://v"},
               {"itag":140,"mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":130567,"contentLength":"4025466","url":"https://a/140?expire=1794800000"},
               {"itag":251,"mimeType":"audio/webm; codecs=\"opus\"","bitrate":146698,"contentLength":"4149227","url":"https://a/251?expire=1794800000"},
               {"itag":250,"mimeType":"audio/webm; codecs=\"opus\"","bitrate":72677,"signatureCipher":"s=..."}
             ]}}
            """.trimIndent(),
        ).jsonObject
        val strategy = InnerTubeClientStrategy(io.mockk.mockk())
        val s = strategy.parse("abc", json, AudioQuality.HIGH, "ANDROID")
        assertEquals("https://a/251?expire=1794800000", s.url)
        assertEquals(3.5f, s.loudnessDb)
        assertEquals(4_149_227L, s.contentLength)
        assertEquals(1_794_800_000_000, s.expiresAtMs)
        val blocked = Json.parseToJsonElement("""{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"bot"}}""").jsonObject
        assertThrows<IllegalStateException> { strategy.parse("abc", blocked, AudioQuality.HIGH, "ANDROID_VR") }
    }
}
