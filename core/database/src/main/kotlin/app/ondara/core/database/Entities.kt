package app.ondara.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import app.ondara.core.model.AlbumRef
import app.ondara.core.model.ArtistRef
import app.ondara.core.model.Song
import kotlinx.serialization.json.Json

@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artists: List<ArtistRef>,
    val albumId: String?,
    val albumTitle: String?,
    val durationSec: Int,
    val thumbnailUrl: String?,
    val explicit: Boolean,
) {
    fun toModel() = Song(id, title, artists, albumId?.let { AlbumRef(it, albumTitle.orEmpty()) }, durationSec, thumbnailUrl, explicit)
}

fun Song.toEntity() = SongEntity(id, title, artists, album?.id, album?.title, durationSec, thumbnailUrl, explicit)

@Entity(tableName = "favorites")
data class FavoriteEntity(@PrimaryKey val songId: String, val addedAt: Long)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    /** `ONDARA`, `YOUTUBE` o `SPOTIFY` (nombre de `PlaylistSource`). */
    @ColumnInfo(defaultValue = "ONDARA") val source: String = "ONDARA",
    /** Id en el servicio de origen (playlist de YouTube o de Spotify). */
    val remoteId: String? = null,
    val thumbnailUrl: String? = null,
    val author: String? = null,
)

@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "songId"],
    foreignKeys = [ForeignKey(PlaylistEntity::class, ["id"], ["playlistId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("playlistId", "position")],
)
data class PlaylistSongEntity(val playlistId: String, val songId: String, val position: Int)

@Entity(tableName = "history", indices = [Index("songId"), Index("playedAt")])
data class HistoryEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val songId: String, val playedAt: Long)

@Entity(tableName = "search_history")
data class SearchHistoryEntity(@PrimaryKey val query: String, val at: Long)

@Entity(tableName = "lyrics")
data class LyricsEntity(@PrimaryKey val songId: String, val json: String, val fetchedAt: Long)

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val songId: String,
    val status: String,
    val progress: Float,
    val quality: String,
    val bytes: Long,
    val updatedAt: Long,
)

/** Caché de páginas del catálogo (home, álbum, artista, playlist) serializadas como JSON. */
@Entity(tableName = "page_cache")
data class PageCacheEntity(@PrimaryKey val key: String, val json: String, val updatedAt: Long)

data class PlaylistWithCount(
    val id: String,
    val name: String,
    val createdAt: Long,
    val songCount: Int,
    @ColumnInfo(name = "thumbnailUrl") val thumbnailUrl: String?,
    val source: String,
    val remoteId: String?,
    val author: String?,
)

/** Elemento fijado en la Biblioteca; [json] es el `MusicItem` serializado. */
@Entity(tableName = "pins")
data class PinEntity(@PrimaryKey val key: String, val json: String, val pinnedAt: Long)

/** Álbum guardado o artista seguido. */
@Entity(tableName = "saved_items")
data class SavedItemEntity(@PrimaryKey val key: String, val json: String, val addedAt: Long)

/** Último acceso a un álbum, playlist o canción (carrusel "Escuchado recientemente"). */
@Entity(tableName = "recent_items", indices = [Index("at")])
data class RecentItemEntity(@PrimaryKey val key: String, val json: String, val at: Long)

/**
 * Emparejamiento Spotify → YouTube Music. `videoId = null` significa "sin coincidencia fiable".
 * La clave primaria hace que cada re-sincronización sea un lookup O(1) por canción.
 */
@Entity(tableName = "spotify_matches")
data class SpotifyMatchEntity(
    @PrimaryKey val spotifyId: String,
    val videoId: String?,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val score: Double,
    val updatedAt: Long,
)

/** Orden original de las canciones de cada playlist importada de Spotify. */
@Entity(tableName = "spotify_playlist_tracks", primaryKeys = ["playlistId", "spotifyId"], indices = [Index("spotifyId")])
data class SpotifyPlaylistTrackEntity(val playlistId: String, val spotifyId: String, val position: Int)

/** Géneros de un artista (MusicBrainz), en minúsculas; [tags] es una lista JSON. */
@Entity(tableName = "artist_tags")
data class ArtistTagsEntity(@PrimaryKey val artist: String, val tags: String, val fetchedAt: Long)

data class DownloadWithSong(
    val songId: String,
    val status: String,
    val progress: Float,
    val quality: String,
    val bytes: Long,
    @androidx.room.Embedded(prefix = "s_") val song: SongEntity,
)

class Converters {
    @TypeConverter
    fun artistsToJson(value: List<ArtistRef>): String = Json.encodeToString(value)

    @TypeConverter
    fun jsonToArtists(value: String): List<ArtistRef> = runCatching { Json.decodeFromString<List<ArtistRef>>(value) }.getOrDefault(emptyList())
}
