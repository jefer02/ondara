package app.ondara.feature.player

import app.ondara.core.model.Song
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class QueueEntriesTest {
    @Test
    fun `keys stay unique when a song repeats in the queue`() {
        val queue = listOf(Song("a", "A"), Song("b", "B"), Song("a", "A"), Song("a", "A"))
        val entries = queueEntries(queue)
        assertEquals(listOf("a#1", "b#1", "a#2", "a#3"), entries.map { it.key })
        assertEquals(queue, entries.map { it.song })
        assertEquals(entries.size, entries.map { it.key }.toSet().size)
    }
}
