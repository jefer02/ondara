package app.ondara.core.model

import kotlinx.serialization.Serializable

/** Referencia ligera a un artista (el nombre siempre existe; el id puede faltar en resultados de YouTube). */
@Serializable
data class ArtistRef(val id: String?, val name: String)

@Serializable
data class AlbumRef(val id: String, val title: String)

/** Una pista reproducible. [id] es el `videoId` de YouTube. */
@Serializable
data class Song(
    val id: String,
    val title: String,
    val artists: List<ArtistRef> = emptyList(),
    val album: AlbumRef? = null,
    val durationSec: Int = 0,
    val thumbnailUrl: String? = null,
    val explicit: Boolean = false,
) {
    val artistLine: String get() = artists.joinToString(", ") { it.name }
}

@Serializable
data class Album(
    val id: String,
    val title: String,
    val artists: List<ArtistRef> = emptyList(),
    val year: Int? = null,
    val thumbnailUrl: String? = null,
    val playlistId: String? = null,
    val explicit: Boolean = false,
)

@Serializable
data class Artist(
    val id: String,
    val name: String,
    val thumbnailUrl: String? = null,
    val subscribers: String? = null,
)

@Serializable
data class Playlist(
    val id: String,
    val title: String,
    val author: String? = null,
    val songCount: Int? = null,
    val thumbnailUrl: String? = null,
    /** `true` si la playlist vive en la base de datos local (creada o importada por el usuario). */
    val isLocal: Boolean = false,
)

/** Cualquier cosa que puede aparecer en una estantería o en la búsqueda. */
@Serializable
sealed interface MusicItem {
    val key: String

    @Serializable
    data class SongItem(val song: Song) : MusicItem {
        override val key get() = "s:" + song.id
    }

    @Serializable
    data class AlbumItem(val album: Album) : MusicItem {
        override val key get() = "a:" + album.id
    }

    @Serializable
    data class ArtistItem(val artist: Artist) : MusicItem {
        override val key get() = "r:" + artist.id
    }

    @Serializable
    data class PlaylistItem(val playlist: Playlist) : MusicItem {
        override val key get() = "p:" + playlist.id
    }
}

/** Fila horizontal del inicio ("Quick picks", "Charts"…). */
@Serializable
data class HomeShelf(val title: String, val subtitle: String? = null, val items: List<MusicItem>)

@Serializable
data class MoodCategory(val title: String, val browseId: String, val params: String?, val color: Long)

@Serializable
data class HomeFeed(val shelves: List<HomeShelf>, val moods: List<MoodCategory> = emptyList())

@Serializable
data class AlbumPage(val album: Album, val songs: List<Song>, val description: String? = null, val otherVersions: List<Album> = emptyList())

@Serializable
data class ArtistPage(
    val artist: Artist,
    val description: String? = null,
    val topSongs: List<Song> = emptyList(),
    val shelves: List<HomeShelf> = emptyList(),
    /** Playlist de radio del artista, si YouTube la expone. */
    val radioPlaylistId: String? = null,
)

@Serializable
data class PlaylistPage(val playlist: Playlist, val songs: List<Song>, val continuation: String? = null)

enum class SearchFilter { ALL, SONGS, ALBUMS, ARTISTS, PLAYLISTS }

data class SearchPage(val items: List<MusicItem>, val continuation: String?)
