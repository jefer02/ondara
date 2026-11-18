package app.ondara.playback

import app.ondara.core.model.AudioQuality
import app.ondara.core.model.ResolvedStream
import app.ondara.core.model.ArtistRef
import app.ondara.core.model.Song
import app.ondara.source.resolver.StreamResolver
import app.ondara.source.resolver.StreamStrategy
import app.ondara.source.resolver.ResolvedStreamCache
import app.ondara.source.resolver.VideoStreamStrategy
import app.ondara.source.resolver.VideoSearchStrategy
import app.ondara.source.resolver.ResolvedVideo
import app.ondara.source.resolver.VideoSearchHit
import app.ondara.source.resolver.VideoStream
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VideoTest {
    private val song = Song(id = "song", title = "Canción azul", artists = listOf(ArtistRef(null, "Artista")), durationSec = 200)
    private fun video() = ResolvedVideo(
        "official", VideoStream("https://video", "video/mp4", 640, 360, 100, false), null, "https://audio", false,
        200, Long.MAX_VALUE, "fake",
    )

    @Test
    fun `sincronizacion respeta umbral pausa y final corto`() {
        assertNull(VideoSync.correction(1000, 850, 5000, true))
        assertNull(VideoSync.correction(1000, 1150, 5000, true))
        assertEquals(1000L, VideoSync.correction(1000, 849, 5000, true))
        assertEquals(1000L, VideoSync.correction(1000, 1151, 5000, true))
        assertNull(VideoSync.correction(1000, 0, 5000, false))
        for (position in 2000L..3000L) assertNull(VideoSync.correction(position, 1999, 2000, true))
        assertEquals(0L, VideoSync.correction(-100, 500, -1, true))
    }

    @Test
    fun `red restringe redes medidas y permite ethernet`() {
        assertTrue(VideoNetworkPolicy.allowed(true, true, true, true, true))
        assertTrue(VideoNetworkPolicy.allowed(true, false, true, false, false))
        assertEquals(false, VideoNetworkPolicy.allowed(false, false, true, true, true))
        assertEquals(false, VideoNetworkPolicy.allowed(true, false, false, true, true))
        assertEquals(false, VideoNetworkPolicy.allowed(true, true, true, false, true))
        assertEquals(false, VideoNetworkPolicy.allowed(true, true, true, true, false))
    }

    @Test
    fun `puntuacion acepta titulo oficial y rechaza duracion o titulo ajenos`() {
        val hit = VideoSearchHit("v", "Artista - Cancion Azul (Official Video)", "Artista", 208)
        assertTrue(VideoSearchScore.score(song, hit) > 0)
        assertEquals(0.0, VideoSearchScore.score(song, hit.copy(durationSec = 209)))
        assertEquals(0.0, VideoSearchScore.score(song, hit.copy(durationSec = 0)))
        assertEquals(0.0, VideoSearchScore.score(song, hit.copy(title = "Otra canción")))
        assertEquals(0.0, VideoSearchScore.score(song, hit.copy(title = "Canción azul live extended remix karaoke concierto")))
    }

    @Test
    fun `cadena prioriza oficial y cachea aciertos`() = runBlocking {
        var officialCalls = 0
        var searchCalls = 0
        val expected = video()
        val chain = VideoSourceChain(listOf(
            VideoSource { officialCalls++; expected }, VideoSource { searchCalls++; expected }, ArtworkVideoSource(),
        ))
        assertEquals(expected, chain.resolve(song))
        assertEquals(expected, chain.resolve(song))
        assertEquals(1, officialCalls)
        assertEquals(0, searchCalls)
    }

    @Test
    fun `cadena delega en busqueda y cachea negativos hasta caducar`() = runBlocking {
        var calls = 0
        var now = 0L
        val chain = VideoSourceChain(listOf(VideoSource { calls++; null }, ArtworkVideoSource())) { now }
        assertNull(chain.resolve(song))
        assertNull(chain.resolve(song))
        assertEquals(1, calls)
        now = 300_001
        assertNull(chain.resolve(song))
        assertEquals(2, calls)
        val expected = video()
        val searched = VideoSourceChain(listOf(VideoSource { null }, VideoSource { expected }, ArtworkVideoSource()))
        assertEquals(expected, searched.resolve(song))
        searched.invalidate(song.id)
        assertEquals(expected, searched.resolve(song))
    }

    @Test
    fun `fuentes reales descartan art track y buscan candidato equivalente`() = runBlocking {
        val expected = video()
        val strategy = object : StreamStrategy, VideoStreamStrategy, VideoSearchStrategy {
            override val name = "fake"
            override suspend fun resolve(videoId: String, quality: AudioQuality): Result<ResolvedStream> = Result.failure(IllegalStateException())
            override suspend fun resolveVideo(videoId: String): Result<ResolvedVideo> =
                Result.success(if (videoId == song.id) expected.copy(isArtTrack = true) else expected)
            override suspend fun searchVideos(query: String): Result<List<VideoSearchHit>> {
                assertEquals("Canción azul Artista", query)
                return Result.success(listOf(
                    VideoSearchHit("wrong", "Otra canción", "Artista", 200),
                    VideoSearchHit("official", "Artista Canción azul Official Video", "Artista", 202),
                ))
            }
        }
        val resolver = StreamResolver(listOf(strategy), ResolvedStreamCache())
        assertNull(OfficialVideoSource(resolver).resolve(song))
        assertEquals(expected, SearchVideoSource(resolver).resolve(song))
    }
}
