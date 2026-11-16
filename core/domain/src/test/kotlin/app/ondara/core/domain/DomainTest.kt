package app.ondara.core.domain

import app.ondara.core.model.Song
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class DomainTest {
    @Test
    fun `shuffle keeps current first and is a permutation`() {
        for (size in 0..40) {
            for (current in listOf(-1, 0, size / 2, size - 1)) {
                val order = QueueShuffler.shuffledOrder(size, current, Random(size * 31L + current))
                assertEquals((0 until size).toList(), order.sorted())
                if (current in 0 until size) assertEquals(current, order[0])
            }
        }
    }

    @Test
    fun `shuffle is roughly uniform`() {
        // Con 4 elementos y el actual fijo quedan 3! = 6 permutaciones equiprobables.
        val counts = HashMap<List<Int>, Int>()
        val random = Random(7)
        repeat(60_000) { counts.merge(QueueShuffler.shuffledOrder(4, 2, random).toList(), 1, Int::plus) }
        assertEquals(6, counts.size)
        assertTrue(counts.values.all { it in 9_400..10_600 }, counts.toString())
    }

    @Test
    fun `csv parser handles quotes, commas and newlines`() {
        val rows = CsvParser.parse("a,\"b,1\",\"say \"\"hi\"\"\"\r\n\"multi\nline\",x,\n\n")
        assertEquals(listOf(listOf("a", "b,1", "say \"hi\""), listOf("multi\nline", "x", "")), rows)
    }

    @Test
    fun `csv tracks understand exportify headers and headerless files`() {
        val exportify = "﻿Track URI,Track Name,Album Name,Artist Name(s)\nspotify:1,\"Get Lucky\",RAM,\"Daft Punk;Pharrell\"\nspotify:2,,x,y\n"
        assertEquals(listOf(CsvParser.Track("Get Lucky", "Daft Punk")), CsvParser.tracks(exportify))
        assertEquals(listOf(CsvParser.Track("One More Time", "Daft Punk")), CsvParser.tracks("One More Time,Daft Punk"))
        assertEquals(listOf(CsvParser.Track("Solo", "")), CsvParser.tracks("Título\nSolo"))
    }

    @Test
    fun `flow queue dedupes against the current queue and falls back to history`() = runTest {
        fun song(id: String) = Song(id, "t$id")
        val catalog = mockk<CatalogRepository>()
        val library = mockk<LibraryRepository>()
        coEvery { catalog.radio("seed", null) } returns listOf(song("seed"), song("a"), song("b"), song("a"), song("q"))
        coEvery { library.mostPlayed(any()) } returns listOf(song("h1"), song("b"), song("h2"))
        val out = BuildFlowQueueUseCase(catalog, library)(song("seed"), exclude = listOf("q"), limit = 10)
        assertEquals(listOf("a", "b", "h1", "h2"), out.map { it.id })

        coEvery { catalog.radio("h1", null) } throws IllegalStateException("sin red")
        assertEquals(listOf("b", "h2"), BuildFlowQueueUseCase(catalog, library)(null, emptyList(), 10).map { it.id })
    }
}
