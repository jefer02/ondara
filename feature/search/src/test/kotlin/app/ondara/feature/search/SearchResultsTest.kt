package app.ondara.feature.search

import app.ondara.core.model.Album
import app.ondara.core.model.Artist
import app.ondara.core.model.ArtistRef
import app.ondara.core.model.MusicItem
import app.ondara.core.model.MusicVideoType
import app.ondara.core.model.Playlist
import app.ondara.core.model.SearchFilter
import app.ondara.core.model.Song
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SearchResultsTest {
    private val audio = Song("audio", "Canción", listOf(ArtistRef("a", "Ñandú")), durationSec = 200, videoType = MusicVideoType.SONG)
    private val video = audio.copy(id = "video", title = "CANCIÓN (Official Video) feat. Invitado", videoType = MusicVideoType.OFFICIAL_VIDEO)

    @Test
    fun `prioriza la equivalente y ordena secciones con videos al final`() {
        val items = listOf(
            MusicItem.SongItem(video), MusicItem.ArtistItem(Artist("a", "Ñandú")),
            MusicItem.AlbumItem(Album("a", "Álbum")), MusicItem.SongItem(audio),
            MusicItem.PlaylistItem(Playlist("p", "Lista")), MusicItem.SongItem(audio.copy(id = "otra")),
        )
        val result = searchResults(items, SearchFilter.ALL)
        assertEquals(listOf("Resultado principal", "Canciones", "Álbumes", "Artistas", "Playlists", "Vídeos"), result.rows.mapNotNull { it.heading })
        assertEquals(audio, (result.rows.first { it.item != null }.item as MusicItem.SongItem).song)
        assertEquals(audio, result.equivalents[video.id])
        assertEquals(video, (result.rows.last().item as MusicItem.SongItem).song)
    }

    @Test
    fun `ids repetidos y snapshots decrecientes conservan claves unicas y contenido propio`() {
        val items = listOf(MusicItem.SongItem(video), MusicItem.SongItem(audio), MusicItem.SongItem(audio))
        val previous = searchResults(items, SearchFilter.ALL)
        for (size in items.size downTo 0) {
            val snapshot = items.take(size)
            val rows = searchResults(snapshot, SearchFilter.ALL).rows
            assertEquals(rows.size, rows.map { it.key }.distinct().size)
            rows.filter { it.item != null }.forEach { assertEquals(snapshot[it.index], it.item) }
        }
        assertEquals(3, previous.rows.count { it.item != null })
    }

    @Test
    fun `canciones anteceden a videos equivalentes en el filtro canciones`() {
        val result = searchResults(listOf(MusicItem.SongItem(video), MusicItem.SongItem(audio)), SearchFilter.SONGS)
        assertEquals(listOf(1, 0), result.rows.map { it.index })
        assertTrue(result.rows.none { it.heading != null })
    }

    @Test
    fun `equivalencia exige artista y menos de diez segundos salvo duracion desconocida`() {
        fun match(candidate: Song): Song? = searchResults(
            listOf(MusicItem.SongItem(video), MusicItem.SongItem(candidate)), SearchFilter.ALL,
        ).equivalents[video.id]
        assertEquals(audio.copy(durationSec = 209), match(audio.copy(durationSec = 209)))
        assertNull(match(audio.copy(durationSec = 210)))
        assertNull(match(audio.copy(artists = listOf(ArtistRef(null, "Otro")))))
        assertEquals(audio.copy(durationSec = 0), match(audio.copy(durationSec = 0)))
        assertEquals(audio.copy(title = "Cancion [Video Oficial]"), match(audio.copy(title = "Cancion [Video Oficial]")))
    }

    @Test
    fun `pagina vacia no crea cabeceras ni equivalencias`() {
        assertEquals(SearchResults(emptyList(), emptyMap()), searchResults(emptyList(), SearchFilter.ALL))
    }
}
