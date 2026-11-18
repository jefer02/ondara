package app.ondara.source.resolver

import app.ondara.core.model.AudioQuality
import app.ondara.core.model.ResolvedStream
import app.ondara.source.innertube.InnerTubeClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class VideoResolverTest {
    private var clock = 1_000_000L

    private fun candidate(height: Int, mime: String = "video/mp4; codecs=\"avc1.4d401e\"", bitrate: Int = height * 1000) =
        VideoCandidate("https://x/v$height?m=${mime.hashCode()}", mime, height * 16 / 9, height, bitrate, hasAudio = false)

    private fun pick(vararg candidates: VideoCandidate) = VideoSelection.pickBackground(candidates.toList())

    // ---- Selección de pista ---------------------------------------------------------------------

    @Test
    fun `prefers the lowest quality inside 360p-480p`() {
        assertEquals(360, pick(candidate(144), candidate(240), candidate(360), candidate(480), candidate(720), candidate(1080))?.height)
        assertEquals(480, pick(candidate(144), candidate(480), candidate(720), candidate(1080))?.height)
    }

    @Test
    fun `falls back to the closest quality up to 720p`() {
        // 240p queda a 120 líneas del rango y 720p a 240: gana 240p.
        assertEquals(240, pick(candidate(144), candidate(240), candidate(720), candidate(1080))?.height)
        assertEquals(720, pick(candidate(720), candidate(1080))?.height)
        assertEquals(144, pick(candidate(144), candidate(1080))?.height)
    }

    @Test
    fun `nothing above 720p is ever chosen`() {
        assertNull(pick(candidate(1080), candidate(2160)))
        assertNull(pick())
        assertNull(pick(candidate(0)))
    }

    @Test
    fun `same height prefers avc then vp9 then av1 and then the lighter one`() {
        val avc = candidate(360)
        val vp9 = candidate(360, "video/webm; codecs=\"vp9\"", bitrate = 100_000)
        val av1 = candidate(360, "video/mp4; codecs=\"av01.0.01M.08\"", bitrate = 50_000)
        assertEquals(avc, pick(av1, vp9, avc))
        assertEquals(vp9, pick(av1, vp9))
        val light = candidate(360, bitrate = 200_000)
        assertEquals(light, pick(avc, light))
    }

    @Test
    fun `muxed stream is the best one up to 720p`() {
        val muxed = { h: Int -> candidate(h).copy(hasAudio = true) }
        assertEquals(720, VideoSelection.pickMuxed(listOf(muxed(360), muxed(720), muxed(1080)))?.height)
        assertEquals(360, VideoSelection.pickMuxed(listOf(muxed(360)))?.height)
        assertEquals(1080, VideoSelection.pickMuxed(listOf(muxed(1080), muxed(1440)))?.height)
        assertNull(VideoSelection.pickMuxed(emptyList()))
    }

    @Test
    fun `art track detection uses musicVideoType and falls back to the topic channel heuristic`() {
        assertTrue(VideoSelection.isArtTrack("MUSIC_VIDEO_TYPE_ATV", "Artista", null))
        assertFalse(VideoSelection.isArtTrack("MUSIC_VIDEO_TYPE_OMV", "Artista - Topic", null))
        assertFalse(VideoSelection.isArtTrack("MUSIC_VIDEO_TYPE_UGC", null, null))
        assertTrue(VideoSelection.isArtTrack(null, "Artista - Topic", null))
        assertTrue(VideoSelection.isArtTrack(null, "Artista", "Provided to YouTube by Sello\n\nCanción"))
        assertFalse(VideoSelection.isArtTrack(null, "ArtistaVEVO", "Vídeo oficial"))
    }

    @Test
    fun `resolution labels are parsed`() {
        assertEquals(360, VideoSelection.heightOf("360p"))
        assertEquals(720, VideoSelection.heightOf("720p60"))
        assertEquals(0, VideoSelection.heightOf(null))
    }

    // ---- Parser de InnerTube --------------------------------------------------------------------

    private fun strategy() = InnerTubeClientStrategy(InnerTubeClient(HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) })))

    private fun playerJson(type: String?, withVideo: Boolean = true) = Json.parseToJsonElement(
        """
        {
          "playabilityStatus": {"status": "OK"},
          "videoDetails": {"videoId": "vid", "author": "Artista", "lengthSeconds": "215"${type?.let { ", \"musicVideoType\": \"$it\"" } ?: ""}},
          "streamingData": {
            "formats": [
              {"itag": 18, "url": "https://g/muxed?expire=1700003600", "mimeType": "video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"", "width": 640, "height": 360, "bitrate": 500000}
            ],
            "adaptiveFormats": [
              ${if (withVideo) """
              {"url": "https://g/1080?expire=1700007200", "mimeType": "video/mp4; codecs=\"avc1.640028\"", "width": 1920, "height": 1080, "bitrate": 4000000},
              {"url": "https://g/480?expire=1700007200", "mimeType": "video/webm; codecs=\"vp9\"", "width": 854, "height": 480, "bitrate": 700000},
              {"url": "https://g/360?expire=1700007200", "mimeType": "video/mp4; codecs=\"avc1.4d401e\"", "qualityLabel": "360p", "bitrate": 400000},
              {"mimeType": "video/mp4; codecs=\"avc1.4d400c\"", "width": 256, "height": 144, "signatureCipher": "s=x"},
              """ else ""}
              {"url": "https://g/audio?expire=1700007200", "mimeType": "audio/webm; codecs=\"opus\"", "bitrate": 160000}
            ]
          }
        }
        """.trimIndent(),
    ).jsonObject

    @Test
    fun `innertube parser picks the 360p video-only track, the muxed one and the audio pair`() {
        val video = strategy().parseVideo("vid", playerJson("MUSIC_VIDEO_TYPE_OMV"), "ANDROID")
        assertEquals("https://g/360?expire=1700007200", video.videoOnly?.url)
        assertEquals(360, video.videoOnly?.height)
        assertFalse(video.videoOnly!!.hasAudio)
        assertEquals("https://g/muxed?expire=1700003600", video.muxed?.url)
        assertTrue(video.muxed!!.hasAudio)
        assertEquals("https://g/audio?expire=1700007200", video.audioUrl)
        assertFalse(video.isArtTrack)
        assertEquals(215, video.durationSec)
        // Caduca cuando lo hace la primera de sus URLs.
        assertEquals(1_700_003_600_000L, video.expiresAtMs)
        assertEquals("innertube", video.source)
    }

    @Test
    fun `innertube parser flags art tracks and survives without adaptive video`() {
        val art = strategy().parseVideo("vid", playerJson("MUSIC_VIDEO_TYPE_ATV"), "ANDROID")
        assertTrue(art.isArtTrack)
        val muxedOnly = strategy().parseVideo("vid", playerJson(null, withVideo = false), "ANDROID")
        assertNull(muxedOnly.videoOnly)
        assertNotNull(muxedOnly.muxed)
        assertTrue(muxedOnly.hasVideo)
    }

    @Test
    fun `innertube parser rejects unplayable or mismatched responses`() {
        val blocked = Json.parseToJsonElement("""{"playabilityStatus": {"status": "LOGIN_REQUIRED", "reason": "Inicia sesión"}}""").jsonObject
        assertThrows<IllegalStateException> { strategy().parseVideo("vid", blocked, "ANDROID") }
        assertThrows<IllegalStateException> { strategy().parseVideo("otro", playerJson("MUSIC_VIDEO_TYPE_OMV"), "ANDROID") }
        val noStreams = Json.parseToJsonElement("""{"playabilityStatus": {"status": "OK"}, "streamingData": {}}""").jsonObject
        assertThrows<IllegalStateException> { strategy().parseVideo("vid", noStreams, "ANDROID") }
    }

    // ---- StreamResolver.resolveVideo ------------------------------------------------------------

    private fun resolved(id: String, source: String, ttlMs: Long) = ResolvedVideo(
        videoId = id,
        videoOnly = VideoStream("https://x/$id", "video/mp4", 640, 360, 400_000, hasAudio = false),
        muxed = null,
        audioUrl = null,
        isArtTrack = false,
        durationSec = 200,
        expiresAtMs = clock + ttlMs,
        source = source,
    )

    private inner class Fake(
        override val name: String,
        val fail: Boolean = false,
        val ttlMs: Long = 6 * 3_600_000L,
        val hits: List<VideoSearchHit> = emptyList(),
    ) : StreamStrategy, VideoStreamStrategy, VideoSearchStrategy {
        var calls = 0
        var searches = 0

        override suspend fun resolve(videoId: String, quality: AudioQuality): Result<ResolvedStream> = Result.failure(IllegalStateException("solo vídeo"))

        override suspend fun resolveVideo(videoId: String): Result<ResolvedVideo> {
            calls++
            delay(10)
            return if (fail) Result.failure(IllegalStateException("$name falló")) else Result.success(resolved(videoId, name, ttlMs))
        }

        override suspend fun searchVideos(query: String): Result<List<VideoSearchHit>> {
            searches++
            return if (fail) Result.failure(IllegalStateException("$name falló")) else Result.success(hits)
        }
    }

    private val audioOnly = object : StreamStrategy {
        override val name = "audio"
        override suspend fun resolve(videoId: String, quality: AudioQuality): Result<ResolvedStream> = Result.failure(IllegalStateException("n/a"))
    }

    private fun resolver(vararg strategies: StreamStrategy) =
        StreamResolver(strategies.toList(), ResolvedStreamCache(), ResolvedVideoCache(capacity = 2, safetyMarginMs = 60_000) { clock })

    @Test
    fun `first video strategy that works wins and is cached`() = runTest {
        val a = Fake("a", fail = true)
        val b = Fake("b")
        val c = Fake("c")
        val resolver = resolver(audioOnly, a, b, c)
        assertEquals("b", resolver.resolveVideo("v1")?.source)
        assertEquals("b", resolver.resolveVideo("v1")?.source)
        assertEquals("b", resolver.cachedVideo("v1")?.source)
        assertEquals(1, a.calls)
        assertEquals(1, b.calls)
        assertEquals(0, c.calls)
    }

    @Test
    fun `no video anywhere returns null instead of throwing`() = runTest {
        assertNull(resolver(audioOnly, Fake("a", fail = true)).resolveVideo("v1"))
        assertNull(resolver(audioOnly).resolveVideo("v1"))
    }

    @Test
    fun `expired and invalidated videos are resolved again`() = runTest {
        val a = Fake("a", ttlMs = 120_000)
        val resolver = resolver(a)
        resolver.resolveVideo("v1")
        clock += 30_000
        resolver.resolveVideo("v1")
        assertEquals(1, a.calls)
        clock += 40_000 // quedan 50 s < margen de 60 s → caducado
        resolver.resolveVideo("v1")
        assertEquals(2, a.calls)
        resolver.invalidateVideo("v1")
        assertNull(resolver.cachedVideo("v1"))
        resolver.resolveVideo("v1")
        assertEquals(3, a.calls)
    }

    @Test
    fun `concurrent video resolves of the same id hit the network once`() = runTest {
        val a = Fake("a")
        val resolver = resolver(a)
        List(8) { async { resolver.resolveVideo("v1") } }.awaitAll()
        assertEquals(1, a.calls)
    }

    @Test
    fun `video cache evicts the least recently used entry`() {
        val cache = ResolvedVideoCache(capacity = 2, safetyMarginMs = 0) { clock }
        cache.put("1", resolved("1", "a", 3_600_000))
        cache.put("2", resolved("2", "a", 3_600_000))
        cache.get("1")
        cache.put("3", resolved("3", "a", 3_600_000))
        assertNull(cache.get("2"))
        assertEquals(2, cache.size)
    }

    @Test
    fun `video search uses the first strategy with results`() = runTest {
        val hit = VideoSearchHit("vid", "Título", "Artista", 200)
        val failing = Fake("a", fail = true)
        val empty = Fake("b")
        val good = Fake("c", hits = listOf(hit))
        assertEquals(listOf(hit), resolver(failing, empty, good).searchVideos("título artista"))
        assertEquals(1, failing.searches)
        assertEquals(1, empty.searches)
        assertTrue(resolver(failing, empty).searchVideos("x").isEmpty())
    }
}
