package app.ondara.core.data

import app.ondara.core.model.LyricLine
import app.ondara.core.model.Lyrics
import app.ondara.core.model.Mood
import app.ondara.core.network.HttpClientFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PersonalTest {
    @Test
    fun `rankBy orders by frequency and keeps first-seen order on ties`() {
        val ranked = listOf("b", "a", "b", "c", "a", "b").rankBy { it }
        assertEquals(listOf("b" to 3, "a" to 2, "c" to 1), ranked)
        assertEquals(listOf("x" to 1, "y" to 1), listOf("x", "y").rankBy { it })
        assertTrue(listOf("x").rankBy<String, String> { null }.isEmpty())
    }

    @Test
    fun `mood classifier maps genre tags weighted by plays`() {
        assertEquals(Mood.CALM, MoodClassifier.moodOf("ambient"))
        assertEquals(Mood.ENERGETIC, MoodClassifier.moodOf("reggaeton"))
        assertEquals(Mood.HAPPY, MoodClassifier.moodOf("latin pop"))
        assertEquals(Mood.MELANCHOLY, MoodClassifier.moodOf("bolero"))
        assertNull(MoodClassifier.moodOf("colombian"))
        assertEquals(Mood.MELANCHOLY, MoodClassifier.dominant(listOf("rock" to 2, "ranchera" to 5, "mariachi" to 1)))
        assertEquals(Mood.NEUTRAL, MoodClassifier.dominant(emptyList()))
    }

    private fun lyrics(vararg lines: String) = Lyrics(lines.mapIndexed { i, t -> LyricLine(i * 1000L, i * 1000L + 900, t) }, synced = true, provider = "test")

    private fun translator(body: String) = LyricsTranslator(
        HttpClientFactory.create(MockEngine { respond(body, headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())) }),
    )

    @Test
    fun `translator attaches one translation per line`() = runTest {
        val body = """[[["Quiero estar donde tú estés\n","I want to be wherever you are\n",null,null,3],["Juntos hasta el fin","Together until the end",null,null,3]],null,"en"]"""
        val out = translator(body).translate(lyrics("I want to be wherever you are", "", "Together until the end"), "es")
        assertEquals("Quiero estar donde tú estés", out.lines[0].translation)
        assertNull(out.lines[1].translation)
        assertEquals("Juntos hasta el fin", out.lines[2].translation)
    }

    @Test
    fun `translator leaves lyrics untouched when already in the target language or misaligned`() = runTest {
        val same = """[[["Ahí donde estás tú","Ahí donde estás tú",null,null,3]],null,"es"]"""
        val original = lyrics("Ahí donde estás tú")
        assertEquals(original, translator(same).translate(original, "es"))
        val misaligned = """[[["una sola línea","a\nb",null,null,3]],null,"en"]"""
        val two = lyrics("a", "b")
        assertEquals(two, translator(misaligned).translate(two, "es"))
    }

    @Test
    fun `translator batches stay under the size limit and keep every line`() {
        val lines = (0 until 200).map { it to "línea número $it de una letra bastante larga" }
        val batches = translator("[]").batches(lines)
        assertTrue(batches.size > 1)
        assertTrue(batches.all { b -> b.sumOf { it.second.length + 1 } <= 1_400 })
        assertEquals(lines, batches.flatten())
    }
}
