package app.ondara.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import kotlinx.coroutines.flow.Flow

@Dao
abstract class LibraryDao {
    @Upsert abstract suspend fun upsertSongs(songs: List<SongEntity>)

    @Query("SELECT * FROM songs WHERE id = :id") abstract suspend fun song(id: String): SongEntity?

    // ---- Favoritos ----
    @Query("SELECT s.* FROM songs s JOIN favorites f ON f.songId = s.id ORDER BY f.addedAt DESC")
    abstract fun favorites(): Flow<List<SongEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE songId = :songId)") abstract fun isFavorite(songId: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE songId = :songId)") abstract suspend fun isFavoriteNow(songId: String): Boolean

    @Upsert abstract suspend fun addFavorite(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE songId = :songId") abstract suspend fun removeFavorite(songId: String)

    @Query("SELECT * FROM favorites") abstract suspend fun allFavorites(): List<FavoriteEntity>

    // ---- Playlists ----
    @Query(
        """
        SELECT p.id, p.name, p.createdAt,
               (SELECT COUNT(*) FROM playlist_songs ps WHERE ps.playlistId = p.id) AS songCount,
               (SELECT s.thumbnailUrl FROM playlist_songs ps JOIN songs s ON s.id = ps.songId
                 WHERE ps.playlistId = p.id ORDER BY ps.position LIMIT 1) AS thumbnailUrl
        FROM playlists p ORDER BY p.createdAt DESC
        """,
    )
    abstract fun playlists(): Flow<List<PlaylistWithCount>>

    @Query(
        """
        SELECT p.id, p.name, p.createdAt,
               (SELECT COUNT(*) FROM playlist_songs ps WHERE ps.playlistId = p.id) AS songCount,
               (SELECT s.thumbnailUrl FROM playlist_songs ps JOIN songs s ON s.id = ps.songId
                 WHERE ps.playlistId = p.id ORDER BY ps.position LIMIT 1) AS thumbnailUrl
        FROM playlists p WHERE p.id = :id
        """,
    )
    abstract fun playlist(id: String): Flow<PlaylistWithCount?>

    @Query("SELECT s.* FROM songs s JOIN playlist_songs ps ON ps.songId = s.id WHERE ps.playlistId = :playlistId ORDER BY ps.position")
    abstract fun playlistSongs(playlistId: String): Flow<List<SongEntity>>

    @Query("SELECT * FROM playlist_songs WHERE playlistId = :playlistId ORDER BY position")
    abstract suspend fun playlistRows(playlistId: String): List<PlaylistSongEntity>

    @Query("SELECT * FROM playlists") abstract suspend fun allPlaylists(): List<PlaylistEntity>

    @Upsert abstract suspend fun upsertPlaylist(playlist: PlaylistEntity)

    @Query("UPDATE playlists SET name = :name WHERE id = :id") abstract suspend fun renamePlaylist(id: String, name: String)

    @Query("DELETE FROM playlists WHERE id = :id") abstract suspend fun deletePlaylist(id: String)

    @Upsert abstract suspend fun upsertPlaylistSongs(rows: List<PlaylistSongEntity>)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId") abstract suspend fun clearPlaylist(playlistId: String)

    /** Reescribe el orden completo de una playlist. O(n) filas. */
    @Transaction
    open suspend fun replacePlaylistSongs(playlistId: String, songIds: List<String>) {
        clearPlaylist(playlistId)
        upsertPlaylistSongs(songIds.distinct().mapIndexed { i, id -> PlaylistSongEntity(playlistId, id, i) })
    }

    // ---- Historial ----
    @Query(
        """
        SELECT s.* FROM songs s JOIN (SELECT songId, MAX(playedAt) AS lastPlayed FROM history GROUP BY songId) h
        ON h.songId = s.id ORDER BY h.lastPlayed DESC LIMIT :limit
        """,
    )
    abstract fun history(limit: Int): Flow<List<SongEntity>>

    @Insert abstract suspend fun addHistory(entry: HistoryEntity)

    @Query("DELETE FROM history") abstract suspend fun clearHistory()

    @Query("SELECT * FROM history ORDER BY playedAt DESC LIMIT 5000") abstract suspend fun allHistory(): List<HistoryEntity>

    @Insert abstract suspend fun addHistoryAll(entries: List<HistoryEntity>)

    @Query(
        """
        SELECT s.* FROM songs s JOIN (SELECT songId, COUNT(*) AS plays FROM history GROUP BY songId) h
        ON h.songId = s.id ORDER BY h.plays DESC LIMIT :limit
        """,
    )
    abstract suspend fun mostPlayed(limit: Int): List<SongEntity>

    // ---- Búsquedas ----
    @Query("SELECT `query` FROM search_history ORDER BY at DESC LIMIT 30") abstract fun searchHistory(): Flow<List<String>>

    @Upsert abstract suspend fun addSearch(entry: SearchHistoryEntity)

    @Query("DELETE FROM search_history WHERE `query` = :query") abstract suspend fun removeSearch(query: String)

    /** Canciones referenciadas por la biblioteca (para el backup). */
    @Query(
        """
        SELECT * FROM songs WHERE id IN (SELECT songId FROM favorites)
           OR id IN (SELECT songId FROM playlist_songs) OR id IN (SELECT songId FROM history)
        """,
    )
    abstract suspend fun librarySongs(): List<SongEntity>
}

@Dao
interface CacheDao {
    @Query("SELECT * FROM page_cache WHERE `key` = :key") suspend fun page(key: String): PageCacheEntity?

    @Upsert suspend fun putPage(page: PageCacheEntity)

    @Query("DELETE FROM page_cache WHERE updatedAt < :olderThan") suspend fun prunePages(olderThan: Long)

    @Query("SELECT * FROM lyrics WHERE songId = :songId") suspend fun lyrics(songId: String): LyricsEntity?

    @Upsert suspend fun putLyrics(lyrics: LyricsEntity)
}

@Dao
interface DownloadDao {
    @Query(
        """
        SELECT d.songId, d.status, d.progress, d.quality, d.bytes,
               s.id AS s_id, s.title AS s_title, s.artists AS s_artists, s.albumId AS s_albumId, s.albumTitle AS s_albumTitle,
               s.durationSec AS s_durationSec, s.thumbnailUrl AS s_thumbnailUrl, s.explicit AS s_explicit
        FROM downloads d JOIN songs s ON s.id = d.songId ORDER BY d.updatedAt DESC
        """,
    )
    fun downloads(): Flow<List<DownloadWithSong>>

    @Query(
        """
        SELECT d.songId, d.status, d.progress, d.quality, d.bytes,
               s.id AS s_id, s.title AS s_title, s.artists AS s_artists, s.albumId AS s_albumId, s.albumTitle AS s_albumTitle,
               s.durationSec AS s_durationSec, s.thumbnailUrl AS s_thumbnailUrl, s.explicit AS s_explicit
        FROM downloads d JOIN songs s ON s.id = d.songId WHERE d.songId = :songId
        """,
    )
    fun download(songId: String): Flow<DownloadWithSong?>

    @Query("SELECT * FROM downloads WHERE songId = :songId") suspend fun get(songId: String): DownloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(download: DownloadEntity)

    @Query("UPDATE downloads SET status = :status, progress = :progress, bytes = :bytes, updatedAt = :now WHERE songId = :songId")
    suspend fun update(songId: String, status: String, progress: Float, bytes: Long, now: Long)

    @Query("DELETE FROM downloads WHERE songId = :songId") suspend fun delete(songId: String)
}
