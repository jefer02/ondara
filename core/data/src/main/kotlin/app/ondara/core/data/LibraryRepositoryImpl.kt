package app.ondara.core.data

import app.ondara.core.database.CacheDao
import app.ondara.core.database.FavoriteEntity
import app.ondara.core.database.HistoryEntity
import app.ondara.core.database.LibraryDao
import app.ondara.core.database.LyricsEntity
import app.ondara.core.database.PinEntity
import app.ondara.core.database.PlaylistEntity
import app.ondara.core.database.RecentItemEntity
import app.ondara.core.database.SavedItemEntity
import app.ondara.core.database.PlaylistSongEntity
import app.ondara.core.database.PlaylistWithCount
import app.ondara.core.database.SearchHistoryEntity
import app.ondara.core.database.toEntity
import app.ondara.core.domain.LibraryRepository
import app.ondara.core.domain.LyricsRepository
import app.ondara.core.model.Lyrics
import app.ondara.core.model.LyricsQuery
import app.ondara.core.model.MusicItem
import app.ondara.core.model.Playlist
import app.ondara.core.model.PlaylistSource
import app.ondara.core.model.SavedItem
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
class LibraryRepositoryImpl @Inject constructor(private val dao: LibraryDao, private val likes: LikePusher) : LibraryRepository {
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
        likes.pushAsync(song.id, now)
        return now
    }

    // Las playlists reflejadas de YouTube y Spotify también son locales: se leen de Room.
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
        recordOpened(MusicItem.SongItem(song))
    }

    override suspend fun clearHistory() = dao.clearHistory()

    override suspend fun mostPlayed(limit: Int): List<Song> = dao.mostPlayed(limit).map { it.toModel() }

    override fun searchHistory(): Flow<List<String>> = dao.searchHistory()

    override suspend fun addSearch(query: String) {
        if (query.isNotBlank()) dao.addSearch(SearchHistoryEntity(query.trim(), System.currentTimeMillis()))
    }

    override suspend fun removeSearch(query: String) = dao.removeSearch(query)

    override fun pins(): Flow<List<MusicItem>> = dao.pins().map { list -> list.mapNotNull { decodeItem(it.json) } }

    override fun isPinned(key: String): Flow<Boolean> = dao.isPinned(key)

    override suspend fun togglePin(item: MusicItem): Boolean {
        if (dao.isPinnedNow(item.key)) {
            dao.removePin(item.key)
            return true
        }
        if (dao.pinCount() >= LibraryRepository.MAX_PINS) return false
        dao.addPin(PinEntity(item.key, encodeItem(item), System.currentTimeMillis()))
        return true
    }

    override fun saved(): Flow<List<SavedItem>> =
        dao.saved().map { list -> list.mapNotNull { row -> decodeItem(row.json)?.let { SavedItem(it, row.addedAt) } } }

    override fun isSaved(key: String): Flow<Boolean> = dao.isSaved(key)

    override suspend fun toggleSaved(item: MusicItem): Boolean {
        val now = !dao.isSavedNow(item.key)
        if (now) dao.addSaved(SavedItemEntity(item.key, encodeItem(item), System.currentTimeMillis())) else dao.removeSaved(item.key)
        return now
    }

    override fun recentItems(limit: Int): Flow<List<MusicItem>> = dao.recentItems(limit).map { list -> list.mapNotNull { decodeItem(it.json) } }

    override suspend fun recordOpened(item: MusicItem) {
        dao.addRecent(RecentItemEntity(item.key, encodeItem(item), System.currentTimeMillis()))
        dao.pruneRecent()
    }

    override suspend fun playsSince(sinceMs: Long, limit: Int): List<Song> = dao.playsSince(sinceMs, limit).map { it.toModel() }

    override suspend fun firstPlayAt(): Long? = dao.firstPlayAt()

    private fun encodeItem(item: MusicItem): String = itemJson.encodeToString(MusicItem.serializer(), item)

    private fun decodeItem(text: String): MusicItem? = runCatching { itemJson.decodeFromString(MusicItem.serializer(), text) }.getOrNull()

    private fun PlaylistWithCount.toModel() = Playlist(
        id = id, title = name, author = author, songCount = songCount, thumbnailUrl = thumbnailUrl, isLocal = true,
        source = runCatching { PlaylistSource.valueOf(source) }.getOrDefault(PlaylistSource.ONDARA),
    )

    companion object {
        const val LOCAL_PREFIX = "local_"
        private val itemJson = Json { ignoreUnknownKeys = true }
    }
}

@Singleton
class LyricsRepositoryImpl @Inject constructor(
    private val aggregator: LyricsAggregator,
    private val cache: CacheDao,
    private val translator: LyricsTranslator,
    private val settings: SettingsCache,
) : LyricsRepository {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun lyrics(song: Song, forceRefresh: Boolean): Lyrics? = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            cache.lyrics(song.id)?.let { row ->
                runCatching { json.decodeFromString(Lyrics.serializer(), row.json) }.getOrNull()?.let { return@withContext it }
            }
        }
        val query = LyricsQuery(song.id, song.title, song.artists.firstOrNull()?.name.orEmpty(), song.album?.title, song.durationSec)
        val original = aggregator.find(query)?.lyrics ?: return@withContext null
        val prefs = settings.value
        val found = if (prefs.translateLyrics) translator.translate(original, prefs.contentLanguage) else original
        cache.putLyrics(LyricsEntity(song.id, json.encodeToString(Lyrics.serializer(), found), System.currentTimeMillis()))
        found
    }
}
