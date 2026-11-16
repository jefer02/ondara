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
data class PlaylistEntity(@PrimaryKey val id: String, val name: String, val createdAt: Long)

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
)

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
