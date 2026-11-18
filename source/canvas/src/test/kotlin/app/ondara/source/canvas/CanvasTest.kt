package app.ondara.source.canvas

import app.ondara.core.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CanvasTest {
    @TempDir lateinit var directory: File
    private val song = Song("id", "Azul", listOf(ArtistRef(null, "Artista")), durationSec = 200)
    private fun result(source: CanvasSource = CanvasSource.APPLE_MUSIC, artist: String = "Artista") =
        CanvasResult(Canvas(song.id, "https://canvas", source), artist, 1_000_000)

    @Test
    fun `cadena respeta orden primer acierto e identidad normalizada`() = runBlocking {
        val calls = mutableListOf<Int>()
        val expected = result(artist = "ÁRTISTA")
        val chain = CanvasChain(listOf(
            CanvasProvider { calls += 1; result(artist = "Otro") },
            CanvasProvider { calls += 2; null },
            CanvasProvider { calls += 3; expected },
            CanvasProvider { calls += 4; result() },
        ), now = { 0L })
        assertEquals(expected, chain.resolve(song))
        assertEquals(expected, chain.resolve(song))
        assertEquals(listOf(1, 2, 3), calls)
    }

    @Test
    fun `todos nulos se cachean hasta TTL negativo`() = runBlocking {
        var now = 0L
        var calls = 0
        val chain = CanvasChain(listOf(CanvasProvider { calls++; null }), now = { now })
        assertEquals(listOf(CanvasState.Loading(song.id), CanvasState.None(song.id)), chain.observeCanvas(song).toList())
        assertNull(chain.resolve(song))
        assertEquals(1, calls)
        now = 300_001
        assertNull(chain.resolve(song))
        assertEquals(2, calls)
    }

    @Test
    fun `cache de disco evita red y caduca con la URL`() = runBlocking {
        var now = 0L
        var calls = 0
        val file = File(directory, "canvas.json")
        val providers = listOf(CanvasProvider { calls++; result() })
        CanvasChain(providers, file) { now }.resolve(song)
        val restored = CanvasChain(providers, file) { now }
        assertEquals(result(), restored.resolve(song))
        assertEquals(1, calls)
        now = 940_001
        assertNull(restored.resolve(song))
        assertEquals(2, calls)
    }

    @Test
    fun `error delega pero cancelacion se propaga`() = runBlocking {
        val chain = CanvasChain(listOf(CanvasProvider { error("red") }, CanvasProvider { result() }), now = { 0L })
        assertEquals(result(), chain.resolve(song))
        val cancelled = CanvasChain(listOf(CanvasProvider { throw CancellationException() }))
        var propagated = false
        try { cancelled.resolve(song) } catch (_: CancellationException) { propagated = true }
        assertTrue(propagated)
    }

    @Test
    fun `politica distingue cache movil y wifi`() = runBlocking {
        val chain = CanvasChain(listOf(CanvasProvider { result(CanvasSource.YOUTUBE_CLIP) }), now = { 0L })
        assertNull(chain.resolve(song, youtubeAllowed = false))
        assertEquals(result(CanvasSource.YOUTUBE_CLIP), chain.resolve(song, youtubeAllowed = true))
    }

    @Test
    fun `clip centrado corto vacio y limites`() {
        assertEquals(96_500L to 103_500L, canvasClip(200_000))
        assertEquals(0L to 3_000L, canvasClip(3_000))
        assertEquals(0L to 0L, canvasClip(-1))
        fun lyrics(time: Long) = Lyrics(listOf(LyricLine(time, time + 1000, "Azul"), LyricLine(5000, 6000, "ázul!")), true, "prueba")
        assertEquals(0L to 7_000L, canvasClip(20_000, lyrics(-1)))
        assertEquals(13_000L to 20_000L, canvasClip(20_000, lyrics(99_000)))
        assertEquals(6_500L to 13_500L, canvasClip(20_000, lyrics(1000).copy(synced = false)))
    }

    @Test
    fun `estribillo usa primera aparicion de la linea mas repetida sin contar vacias`() {
        val lines = listOf("", "Hola", "Azul", "azúl!", "hola", "AZUL", "").mapIndexed { i, text ->
            LyricLine(i * 1000L, (i + 1) * 1000L, text)
        }
        assertEquals(2000L to 9000L, canvasClip(30_000, Lyrics(lines, true, "prueba")))
    }

    @Test
    fun `caducidad respeta firma de URL y limite de un dia`() {
        assertEquals(120_000L, canvasUrlExpiry("https://canvas?expire=120", 0))
        assertEquals(86_400_000L, canvasUrlExpiry("https://canvas", 0))
    }

    @Test
    fun `parser apple prefiere editorial vertical y rechaza artista ajeno`() {
        val json = Json.parseToJsonElement("""
            {"data":[{"attributes":{"artistName":"Artista","editorialVideo":{
              "motionSquareVideo1x1":{"video":"https://square.m3u8","width":1080,"height":1080},
              "motionTallVideo3x4":{"video":"https://tall.m3u8","width":1080,"height":1440}
            }}}]}
        """.trimIndent())
        val canvas = parseAppleCanvas(json, song)!!.canvas
        assertEquals("https://tall.m3u8", canvas.url)
        assertEquals(1440, canvas.height)
        assertNull(canvas.clipEndMs)
        assertNull(parseAppleCanvas(json, song.copy(artists = listOf(ArtistRef(null, "Otro")))))
        assertNull(parseAppleCanvas(Json.parseToJsonElement("{}"), song))
    }

    @Test
    fun `parser tidal extrae videoCover 1280 y exige titulo y artista`() {
        val text = """
            {"tracks":{"items":[{"title":"Azul","artists":[{"name":"Artista"}],
              "album":{"videoCover":"12345678-1234-1234-1234-123456789abc"}}]}}
        """.trimIndent()
        val canvas = parseTidalCanvas(Json.parseToJsonElement(text), song)!!.canvas
        assertEquals("https://resources.tidal.com/videos/12345678/1234/1234/1234/123456789abc/1280x1280.mp4", canvas.url)
        assertEquals(1280, canvas.height)
        assertNull(parseTidalCanvas(Json.parseToJsonElement(text.replace("Artista", "Otro")), song))
        assertNull(parseTidalCanvas(Json.parseToJsonElement(text.replace("Azul", "Rojo")), song))
        assertNull(parseTidalCanvas(Json.parseToJsonElement(text.replace("123456789abc", "invalido")), song))
    }
}
