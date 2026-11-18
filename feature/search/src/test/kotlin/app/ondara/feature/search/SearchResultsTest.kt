package app.ondara.feature.search

import app.ondara.core.model.Album
import app.ondara.core.model.Artist
import app.ondara.core.model.MusicItem
import app.ondara.core.model.Playlist
import app.ondara.core.model.SearchFilter
import app.ondara.core.model.Song
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SearchResultsTest {
    @Test
    fun `agrupa la primera pagina de forma estable y anexa las siguientes`() {
        val items = listOf(
            MusicItem.AlbumItem(Album("album", "Álbum")),
            MusicItem.SongItem(Song("s1", "Primera")),
            MusicItem.ArtistItem(Artist("artist", "Artista")),
            MusicItem.SongItem(Song("s2", "Segunda")),
            MusicItem.PlaylistItem(Playlist("playlist", "Playlist")),
            MusicItem.SongItem(Song("s3", "Tercera")),
            MusicItem.AlbumItem(Album("append", "Álbum siguiente")),
        )
        val rows = resultRows(items, 5, SearchFilter.ALL)
        assertEquals(listOf("Canciones", "Artistas", "Álbumes", "Playlists"), rows.mapNotNull { it.heading })
        assertEquals(listOf(1, 3, 2, 0, 4, 5, 6), rows.filter { it.heading == null }.map { it.index })
    }

    @Test
    fun `un filtro concreto mantiene el orden sin cabeceras`() {
        val items = listOf(MusicItem.SongItem(Song("a", "A")), MusicItem.SongItem(Song("b", "B")))
        assertEquals(listOf(SearchResultRow(0), SearchResultRow(1)), resultRows(items, 1, SearchFilter.SONGS))
    }

    @Test
    fun `la pagina vacia no crea cabeceras`() {
        assertEquals(emptyList<SearchResultRow>(), resultRows(emptyList(), 0, SearchFilter.ALL))
    }
}
