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
    fun `youtube busca primero el oficial y recorta siete segundos`() = runBlocking {
        val expected = video()
        val strategy = object : StreamStrategy, VideoStreamStrategy, VideoSearchStrategy {
            override val name = "prueba"
            override suspend fun resolve(videoId: String, quality: AudioQuality): Result<ResolvedStream> = Result.failure(IllegalStateException())
            override suspend fun resolveVideo(videoId: String): Result<ResolvedVideo> {
                assertEquals("official", videoId)
                return Result.success(expected)
            }
            override suspend fun searchVideos(query: String) = Result.success(listOf(
                VideoSearchHit("wrong", "Canción azul", "Otro artista", 200),
                VideoSearchHit("official", "Artista Canción azul Official Video", "Artista", 200),
            ))
        }
        val resolver = StreamResolver(listOf(strategy), ResolvedStreamCache())
        val lyrics = object : app.ondara.core.domain.LyricsRepository {
            override suspend fun lyrics(song: Song, forceRefresh: Boolean): app.ondara.core.model.Lyrics? = null
        }
        val result = YouTubeCanvasProvider(resolver, lyrics) { true }.resolve(song)!!.canvas
        assertEquals(app.ondara.core.model.CanvasSource.YOUTUBE_CLIP, result.source)
        assertEquals(96_500L, result.clipStartMs)
        assertEquals(103_500L, result.clipEndMs)
        assertNull(YouTubeCanvasProvider(resolver, lyrics) { false }.resolve(song))
    }

    @Test
    fun `respaldo propio rechaza art track y autor distinto`() = runBlocking {
        var own = video().copy(author = "Artista", isArtTrack = true)
        val strategy = object : StreamStrategy, VideoStreamStrategy {
            override val name = "prueba"
            override suspend fun resolve(videoId: String, quality: AudioQuality): Result<ResolvedStream> = Result.failure(IllegalStateException())
            override suspend fun resolveVideo(videoId: String) = Result.success(own)
        }
        val resolver = StreamResolver(listOf(strategy), ResolvedStreamCache())
        val lyrics = object : app.ondara.core.domain.LyricsRepository {
            override suspend fun lyrics(song: Song, forceRefresh: Boolean): app.ondara.core.model.Lyrics? = null
        }
        val provider = YouTubeCanvasProvider(resolver, lyrics) { true }
        assertNull(provider.resolve(song))
        own = own.copy(isArtTrack = false, author = "Otro")
        resolver.invalidateVideo(song.id)
        assertNull(provider.resolve(song))
        own = own.copy(author = "Artista")
        resolver.invalidateVideo(song.id)
        assertEquals(song.id, provider.resolve(song)?.canvas?.songId)
    }
}
