package app.ondara.core.data

import app.ondara.core.database.CacheDao
import app.ondara.core.database.FavoriteEntity
import app.ondara.core.database.HistoryEntity
import app.ondara.core.database.LibraryDao
import app.ondara.core.database.LyricsEntity
import app.ondara.core.database.PlaylistEntity
import app.ondara.core.database.PlaylistSongEntity
import app.ondara.core.database.PlaylistWithCount
import app.ondara.core.database.SearchHistoryEntity
import app.ondara.core.database.toEntity
import app.ondara.core.domain.LibraryRepository
import app.ondara.core.domain.LyricsRepository
import app.ondara.core.model.Lyrics
import app.ondara.core.model.LyricsQuery
import app.ondara.core.model.Playlist
import app.ondara.core.model.Song
import app.ondara.source.lyrics.LyricsAggregator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibraryRepositoryImpl @Inject constructor(private val dao: LibraryDao) : LibraryRepository {
    override fun favorites(): Flow<List<Song>> = dao.favorites().map { list -> list.map { it.toModel() } }

    override fun isFavorite(songId: String): Flow<Boolean> = dao.isFavorite(songId)

    override suspend fun toggleFavorite(song: Song): Boolean {
        val now = !dao.isFavoriteNow(song.id)
        if (now) {
            dao.upsertSongs(listOf(song.toEntity()))
            dao.addFavorite(FavoriteEntity(song.id, System.currentTimeMillis()))
        } else {
            dao.removeFavorite(song.id)
        }
        return now
    }

    override fun playlists(): Flow<List<Playlist>> = dao.playlists().map { list -> list.map { it.toModel() } }

    override fun playlist(playlistId: String): Flow<Playlist?> = dao.playlist(playlistId).map { it?.toModel() }

    override fun playlistSongs(playlistId: String): Flow<List<Song>> = dao.playlistSongs(playlistId).map { list -> list.map { it.toModel() } }

    override suspend fun createPlaylist(name: String, songs: List<Song>): String {
        val id = LOCAL_PREFIX + UUID.randomUUID().toString()
        dao.upsertPlaylist(PlaylistEntity(id, name.trim().ifEmpty { "Mi playlist" }, System.currentTimeMillis()))
        if (songs.isNotEmpty()) addToPlaylist(id, songs)
        return id
    }

    override suspend fun renamePlaylist(playlistId: String, name: String) = dao.renamePlaylist(playlistId, name.trim())

    override suspend fun deletePlaylist(playlistId: String) = dao.deletePlaylist(playlistId)

    override suspend fun addToPlaylist(playlistId: String, songs: List<Song>) {
        dao.upsertSongs(songs.map { it.toEntity() })
        val existing = dao.playlistRows(playlistId)
        val known = existing.mapTo(HashSet()) { it.songId }
        var position = (existing.maxOfOrNull { it.position } ?: -1) + 1
        dao.upsertPlaylistSongs(songs.filter { known.add(it.id) }.map { PlaylistSongEntity(playlistId, it.id, position++) })
    }

    override suspend fun removeFromPlaylist(playlistId: String, songId: String) {
        dao.replacePlaylistSongs(playlistId, dao.playlistRows(playlistId).map { it.songId }.filter { it != songId })
    }

    /** Mueve una canción y renumera posiciones. O(n) con n canciones de la playlist. */
    override suspend fun movePlaylistSong(playlistId: String, from: Int, to: Int) {
        val ids = dao.playlistRows(playlistId).map { it.songId }.toMutableList()
        if (from !in ids.indices || to !in ids.indices) return
        ids.add(to, ids.removeAt(from))
        dao.replacePlaylistSongs(playlistId, ids)
    }

    override fun history(limit: Int): Flow<List<Song>> = dao.history(limit).map { list -> list.map { it.toModel() } }

    override suspend fun recordPlay(song: Song) {
        dao.upsertSongs(listOf(song.toEntity()))
        dao.addHistory(HistoryEntity(songId = song.id, playedAt = System.currentTimeMillis()))
    }

    override suspend fun clearHistory() = dao.clearHistory()

    override suspend fun mostPlayed(limit: Int): List<Song> = dao.mostPlayed(limit).map { it.toModel() }

    override fun searchHistory(): Flow<List<String>> = dao.searchHistory()

    override suspend fun addSearch(query: String) {
        if (query.isNotBlank()) dao.addSearch(SearchHistoryEntity(query.trim(), System.currentTimeMillis()))
    }

    override suspend fun removeSearch(query: String) = dao.removeSearch(query)

    private fun PlaylistWithCount.toModel() = Playlist(id, name, author = null, songCount = songCount, thumbnailUrl = thumbnailUrl, isLocal = true)

    companion object {
        const val LOCAL_PREFIX = "local_"
    }
}

@Singleton
class LyricsRepositoryImpl @Inject constructor(
    private val aggregator: LyricsAggregator,
    private val cache: CacheDao,
) : LyricsRepository {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun lyrics(song: Song, forceRefresh: Boolean): Lyrics? = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            cache.lyrics(song.id)?.let { row ->
                runCatching { json.decodeFromString(Lyrics.serializer(), row.json) }.getOrNull()?.let { return@withContext it }
            }
        }
        val query = LyricsQuery(song.id, song.title, song.artists.firstOrNull()?.name.orEmpty(), song.album?.title, song.durationSec)
        val found = aggregator.find(query)?.lyrics ?: return@withContext null
        cache.putLyrics(LyricsEntity(song.id, json.encodeToString(Lyrics.serializer(), found), System.currentTimeMillis()))
        found
    }
}
