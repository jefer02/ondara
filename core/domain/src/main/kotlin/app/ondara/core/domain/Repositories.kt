package app.ondara.core.domain

import androidx.paging.PagingData
import app.ondara.core.model.AlbumPage
import app.ondara.core.model.ArtistPage
import app.ondara.core.model.AudioQuality
import app.ondara.core.model.DownloadInfo
import app.ondara.core.model.HomeFeed
import app.ondara.core.model.HomeShelf
import app.ondara.core.model.Lyrics
import app.ondara.core.model.MusicItem
import app.ondara.core.model.Playlist
import app.ondara.core.model.PlaylistPage
import app.ondara.core.model.SearchFilter
import app.ondara.core.model.Song
import app.ondara.core.model.UserSettings
import kotlinx.coroutines.flow.Flow

/** Estado de carga de un recurso offline-first: primero lo que hay en Room, luego lo que llega de la red. */
sealed interface Resource<out T> {
    data object Loading : Resource<Nothing>
    data class Success<T>(val data: T, val fromCache: Boolean) : Resource<T>
    data class Error<T>(val message: String, val cached: T? = null) : Resource<T>
}

/** Catálogo público de YouTube Music. Room es la fuente de verdad; la red la refresca. */
interface CatalogRepository {
    fun home(forceRefresh: Boolean = false): Flow<Resource<HomeFeed>>
    fun moodShelves(browseId: String, params: String?): Flow<Resource<List<HomeShelf>>>
    fun album(browseId: String): Flow<Resource<AlbumPage>>
    fun artist(browseId: String): Flow<Resource<ArtistPage>>
    fun playlist(playlistId: String): Flow<Resource<PlaylistPage>>
    fun search(query: String, filter: SearchFilter): Flow<PagingData<MusicItem>>
    suspend fun suggestions(query: String): List<String>

    /** Canciones relacionadas con [videoId] (endpoint `next`), base de la radio / Ondara Flow. */
    suspend fun radio(videoId: String, playlistId: String? = null): List<Song>
}

/** Biblioteca local: favoritos, playlists propias, historial de escucha y de búsqueda. */
interface LibraryRepository {
    fun favorites(): Flow<List<Song>>
    fun isFavorite(songId: String): Flow<Boolean>
    suspend fun toggleFavorite(song: Song): Boolean

    fun playlists(): Flow<List<Playlist>>
    fun playlistSongs(playlistId: String): Flow<List<Song>>
    fun playlist(playlistId: String): Flow<Playlist?>
    suspend fun createPlaylist(name: String, songs: List<Song> = emptyList()): String
    suspend fun renamePlaylist(playlistId: String, name: String)
    suspend fun deletePlaylist(playlistId: String)
    suspend fun addToPlaylist(playlistId: String, songs: List<Song>)
    suspend fun removeFromPlaylist(playlistId: String, songId: String)
    suspend fun movePlaylistSong(playlistId: String, from: Int, to: Int)

    fun history(limit: Int = 200): Flow<List<Song>>
    suspend fun recordPlay(song: Song)
    suspend fun clearHistory()

    /** Las canciones más escuchadas; semilla de Ondara Flow. */
    suspend fun mostPlayed(limit: Int): List<Song>

    fun searchHistory(): Flow<List<String>>
    suspend fun addSearch(query: String)
    suspend fun removeSearch(query: String)
}

interface LyricsRepository {
    /** Devuelve la letra en caché o lanza la carrera de proveedores. `null` si nadie la tiene. */
    suspend fun lyrics(song: Song, forceRefresh: Boolean = false): Lyrics?
}

interface SettingsRepository {
    val settings: Flow<UserSettings>
    suspend fun update(transform: (UserSettings) -> UserSettings)
}

interface DownloadRepository {
    fun downloads(): Flow<List<DownloadInfo>>
    fun download(songId: String): Flow<DownloadInfo?>
    suspend fun enqueue(songs: List<Song>, quality: AudioQuality)
    suspend fun remove(songId: String)
}

/** Resultado de importar una playlist externa. */
data class ImportResult(val playlistId: String, val matched: Int, val total: Int)

interface ImportRepository {
    /** Importa una playlist pública (o privada con sesión iniciada) de YouTube Music por URL o id. */
    suspend fun importYouTubePlaylist(urlOrId: String): ImportResult

    /**
     * Importa un CSV (formato Exportify/Spotify o `title,artist`). Cada fila se busca en el catálogo.
     * [onProgress] recibe (procesadas, total).
     */
    suspend fun importCsv(name: String, csv: String, onProgress: (Int, Int) -> Unit = { _, _ -> }): ImportResult
}

interface BackupRepository {
    /** Serializa biblioteca + ajustes a JSON; con [password] el contenido va cifrado (AES-256-GCM). */
    suspend fun export(password: String?): ByteArray

    /** @throws IllegalArgumentException si el archivo no es válido o la contraseña no coincide. */
    suspend fun import(bytes: ByteArray, password: String?)
}
