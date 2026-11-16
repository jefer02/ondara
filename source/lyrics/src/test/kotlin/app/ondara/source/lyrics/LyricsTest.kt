package app.ondara.source.lyrics

import app.ondara.core.model.LyricLine
import app.ondara.core.model.LyricWord
import app.ondara.core.model.Lyrics
import app.ondara.core.model.LyricsQuery
import app.ondara.core.network.HttpClientFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.zip.Deflater

class LyricsTest {
    private val query = LyricsQuery("vid", "Get Lucky (feat. Pharrell Williams)", "Daft Punk", "Random Access Memories", 369)

    private fun synced(provider: String, words: Boolean = false) = Lyrics(
        listOf(LyricLine(0, 1000, "a", if (words) listOf(LyricWord(0, 1000, "a")) else emptyList())), true, provider,
    )

    @Test
    fun `lrc parser handles multi tags, offset and ordering`() {
        val lrc = "[ti:x]\n[offset:500]\n[00:12.50][01:00.00]Chorus\n[00:01.5]First\n\n[00:20.123]Third"
        val lyrics = LrcParser.parse(lrc, "t", durationMs = 90_000)!!
        assertEquals(listOf(1000L, 12_000L, 19_623L, 59_500L), lyrics.lines.map { it.startMs })
        assertEquals(listOf("First", "Chorus", "Third", "Chorus"), lyrics.lines.map { it.text })
        assertEquals(12_000L, lyrics.lines[0].endMs)
        assertEquals(90_000L, lyrics.lines.last().endMs)
        assertNull(LrcParser.parse("just text\nno tags", "t"))
    }

    @Test
    fun `enhanced lrc yields word timings`() {
        val lyrics = LrcParser.parse("[00:10.00]<00:10.00>Hello <00:10.50>big <00:11.20>world\n[00:13.00]next", "t")!!
        val line = lyrics.lines.first()
        assertEquals("Hello big world", line.text)
        assertEquals(listOf(10_000L, 10_500L, 11_200L), line.words.map { it.startMs })
        assertEquals(listOf(10_500L, 11_200L, 13_000L), line.words.map { it.endMs })
        assertTrue(lyrics.wordSynced)
    }

    @Test
    fun `krc round trip decodes and parses words`() {
        val text = "[ti:Song]\n[0,500]<0,500,0>Written by：X\n[1000,2000]<0,400,0>Up <400,600,0>all <1000,1000,0>night\n[4000,1000]<0,1000,0>again"
        val lyrics = KrcParser.parse(KrcParser.decode(encodeKrc(text)), "KuGou")!!
        assertEquals(2, lyrics.lines.size)
        assertEquals("Up all night", lyrics.lines[0].text)
        assertEquals(listOf(1000L, 1400L, 2000L), lyrics.lines[0].words.map { it.startMs })
        assertEquals(3000L, lyrics.lines[0].endMs)
        assertTrue(lyrics.wordSynced)
    }

    private fun encodeKrc(text: String): String {
        val key = intArrayOf(64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, 206, 210, 110, 105)
        val deflater = Deflater().apply { setInput(text.toByteArray()); finish() }
        val buf = ByteArray(4096)
        val n = deflater.deflate(buf)
        val body = ByteArray(n) { i -> (buf[i].toInt() xor key[i % 16]).toByte() }
        return Base64.getEncoder().encodeToString("krc1".toByteArray() + body)
    }

    @Test
    fun `active line uses binary search semantics`() {
        val lyrics = Lyrics(List(100) { LyricLine(it * 1000L + 500, it * 1000L + 1500, "l$it") }, true, "t")
        assertEquals(-1, lyrics.activeLineIndex(0))
        assertEquals(-1, lyrics.activeLineIndex(499))
        assertEquals(0, lyrics.activeLineIndex(500))
        assertEquals(0, lyrics.activeLineIndex(1499))
        assertEquals(42, lyrics.activeLineIndex(42_999))
        assertEquals(99, lyrics.activeLineIndex(10_000_000))
        // Igual que la búsqueda lineal en todo el rango.
        for (t in 0L..101_000L step 137) assertEquals(lyrics.lines.indexOfLast { it.startMs <= t }, lyrics.activeLineIndex(t))
        assertEquals(-1, Lyrics(lyrics.lines, false, "t").activeLineIndex(5000))
    }

    @Test
    fun `jaro winkler matches reference values`() {
        assertEquals(1.0, JaroWinkler.similarity("abc", "abc"))
        assertEquals(0.0, JaroWinkler.similarity("abc", ""))
        assertEquals(0.961, JaroWinkler.similarity("martha", "marhta"), 0.001)
        assertEquals(0.840, JaroWinkler.similarity("dwayne", "duane"), 0.001)
        assertEquals(0.813, JaroWinkler.similarity("dixon", "dicksonx"), 0.001)
        assertEquals(0.0, JaroWinkler.similarity("abc", "xyz"))
    }

    @Test
    fun `scoring prefers word sync, punishes duration gap and rejects other songs`() {
        val word = LyricsScorer.score(query, LyricsCandidate(synced("a", words = true), "Get Lucky", "Daft Punk", 369))!!
        val line = LyricsScorer.score(query, LyricsCandidate(synced("b"), "Get Lucky", "Daft Punk", 369))!!
        val radioEdit = LyricsScorer.score(query, LyricsCandidate(synced("c"), "Get Lucky", "Daft Punk", 249))!!
        val plain = LyricsScorer.score(query, LyricsCandidate(LrcParser.plain("x\ny", "d")!!, "Get Lucky", "Daft Punk", 369))!!
        assertEquals(1.0, word, 1e-9)
        assertTrue(word > line && line > radioEdit && line > plain)
        assertNull(LyricsScorer.score(query, LyricsCandidate(synced("e", true), "Instant Crush", "Daft Punk", 369)))
        assertEquals("get lucky", LyricsScorer.normalize("Get Lucky (feat. Pharrell Williams) [Remastered 2020]"))
    }

    private class FakeProvider(override val name: String, val delayMs: Long, val result: List<LyricsCandidate>?) : LyricsProvider {
        var cancelled = false
        override suspend fun search(query: LyricsQuery): List<LyricsCandidate> {
            try {
                delay(delayMs)
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled = true
                throw e
            }
            return result ?: error("caído")
        }
    }

    @Test
    fun `aggregator runs providers in parallel and picks the best score`() = runTest {
        val slowLine = FakeProvider("line", 3000, listOf(LyricsCandidate(synced("line"), "Get Lucky", "Daft Punk", 369)))
        val broken = FakeProvider("broken", 100, null)
        val fastPlain = FakeProvider("plain", 200, listOf(LyricsCandidate(LrcParser.plain("x", "plain")!!, "Get Lucky", "Daft Punk", 369)))
        val best = LyricsAggregator(listOf(fastPlain, broken, slowLine)).find(query)
        assertEquals("line", best?.lyrics?.provider)
        assertEquals(3000, currentTime) // max de latencias, no la suma
    }

    @Test
    fun `aggregator exits early on a good enough result and cancels the rest`() = runTest {
        val fastWord = FakeProvider("word", 300, listOf(LyricsCandidate(synced("word", true), "Get Lucky", "Daft Punk", 368)))
        val slow = FakeProvider("slow", 5000, listOf(LyricsCandidate(synced("slow"), "Get Lucky", "Daft Punk", 369)))
        val best = LyricsAggregator(listOf(slow, fastWord)).find(query)
        assertEquals("word", best?.lyrics?.provider)
        assertEquals(300, currentTime)
        assertTrue(slow.cancelled)
    }

    @Test
    fun `aggregator times out hanging providers and returns null when nothing matches`() = runTest {
        val hang = FakeProvider("hang", 60_000, emptyList())
        assertNull(LyricsAggregator(listOf(hang), timeoutMs = 1000).find(query))
        assertEquals(1000, currentTime)
        assertNull(LyricsAggregator(emptyList()).find(query))
    }

    @Test
    fun `lrclib provider maps synced and plain results`() = runTest {
        val body = """[
          {"trackName":"Get Lucky","artistName":"Daft Punk","duration":367.0,"syncedLyrics":"[00:01.00]Like the legend\n[00:05.00]of the phoenix","plainLyrics":"x"},
          {"trackName":"Get Lucky","artistName":"Daft Punk","duration":249.0,"syncedLyrics":null,"plainLyrics":"Like the legend\nof the phoenix"},
          {"trackName":"Empty","artistName":"x","duration":1.0,"syncedLyrics":null,"plainLyrics":null}
        ]"""
        var requested = ""
        val engine = MockEngine { req ->
            requested = req.url.toString()
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val result = LrcLibProvider(HttpClientFactory.create(engine)).search(query)
        assertEquals(2, result.size)
        assertTrue(result[0].lyrics.synced)
        assertEquals(367, result[0].durationSec)
        assertEquals(367_000L, result[0].lyrics.lines.last().endMs)
        assertTrue(!result[1].lyrics.synced)
        assertTrue(requested.contains("track_name=get+lucky") || requested.contains("track_name=get%20lucky"))
    }
}
