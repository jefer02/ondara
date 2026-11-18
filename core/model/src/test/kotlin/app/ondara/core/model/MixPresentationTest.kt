package app.ondara.core.model

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MixPresentationTest {
    @Test
    fun `subtitulo cuenta canciones y ordena artistas por frecuencia`() {
        val songs = listOf("B", "A", "A", "C", "D").mapIndexed { index, name ->
            Song("$index", "Canción", listOf(ArtistRef(null, name)))
        }
        assertEquals("5 canciones · A, B, C…", mixSubtitle(songs))
        assertEquals("0 canciones", mixSubtitle(emptyList()))
        assertEquals("1 canción", mixSubtitle(listOf(Song("id", "Canción"))))
    }

    @Test
    fun `motivo no duplica titulo aunque cambien espacios o mayusculas`() {
        assertEquals("Elegido para ti", distinctPickLabel(" TUS FAVORITAS ", "Tus favoritas"))
        assertEquals("Elegido para ti", distinctPickLabel("", "Mix"))
        assertEquals("Lo que más escuchas", distinctPickLabel("Lo que más escuchas", "Tus favoritas"))
    }

    @Test
    fun `playlist antigua conserva compatibilidad y nueva conserva caratulas`() {
        assertEquals(emptyList<String>(), Json.decodeFromString<Playlist>("""{"id":"mix","title":"Mix"}""").artworkUrls)
        val playlist = Playlist("mix", "Mix", artworkUrls = listOf("https://imagen/1", "https://imagen/2"))
        assertEquals(playlist, Json.decodeFromString<Playlist>(Json.encodeToString(Playlist.serializer(), playlist)))
    }
}
