package app.ondara.core.model

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SongSerializationTest {
    @Test
    fun `cache anterior sin tipo sigue siendo legible`() {
        val song = Json.decodeFromString<Song>("""{"id":"id","title":"Título"}""")
        assertEquals(MusicVideoType.UNKNOWN, song.videoType)
    }

    @Test
    fun `cache nueva conserva tipo de video`() {
        val song = Song("id", "Título", videoType = MusicVideoType.SONG)
        assertEquals(song, Json.decodeFromString<Song>(Json.encodeToString(Song.serializer(), song)))
    }
}
