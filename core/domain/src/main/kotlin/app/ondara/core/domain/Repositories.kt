package app.ondara.core.domain

import androidx.paging.PagingData
import app.ondara.core.model.AccountsState
import app.ondara.core.model.AlbumPage
import app.ondara.core.model.Artist
import app.ondara.core.model.ListeningStats
import app.ondara.core.model.MoodCategory
import app.ondara.core.model.PersonalHome
import app.ondara.core.model.SavedItem
import app.ondara.core.model.SpotifyAccount
import app.ondara.core.model.SyncProgress
import app.ondara.core.model.UnmatchedTrack
import app.ondara.core.model.YouTubeAccount
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
import kotlinx.coroutines.flow.StateFlow

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

    /** Pestaña Novedades: lanzamientos, listas de éxitos y vídeos populares. */
    fun news(forceRefresh: Boolean = false): Flow<Resource<List<HomeShelf>>>

    /** Géneros y estados de ánimo (categorías de Buscar y chips del Inicio). */
    fun moods(): Flow<Resource<List<MoodCategory>>>

    /** Una portada representativa de la categoría (primera playlist), cacheada; `null` si no hay. */
    suspend fun moodArtwork(mood: MoodCategory): String?
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

    // ---- v2: fijados, guardados y recientes ----

    /** Elementos fijados en la cabecera de la Biblioteca (máx. [MAX_PINS]), del más reciente al más antiguo. */
    fun pins(): Flow<List<MusicItem>>
    fun isPinned(key: String): Flow<Boolean>

    /** Fija o desfija. @return `false` si no se pudo fijar porque ya hay [MAX_PINS]. */
    suspend fun togglePin(item: MusicItem): Boolean

    /** Álbumes guardados y artistas seguidos. */
    fun saved(): Flow<List<SavedItem>>
    fun isSaved(key: String): Flow<Boolean>
    suspend fun toggleSaved(item: MusicItem): Boolean

    /** Lo último abierto o reproducido (canciones, álbumes y playlists mezclados). */
    fun recentItems(limit: Int = 20): Flow<List<MusicItem>>
    suspend fun recordOpened(item: MusicItem)

    /** Reproducciones desde [sinceMs], una entrada por escucha (con repetidos), de la más reciente a la más antigua. */
    suspend fun playsSince(sinceMs: Long, limit: Int = 5000): List<Song>

    /** Fecha de la primera reproducción registrada, o `null` si no hay historial. */
    suspend fun firstPlayAt(): Long?

    companion object {
        const val MAX_PINS = 6
    }
}

/** Secciones personales (Inicio, perfil): se derivan del historial local y de las cuentas conectadas. */
interface PersonalRepository {
    /** Emite primero lo cacheado y después la versión recalculada. */
    fun home(forceRefresh: Boolean = false): Flow<PersonalHome>

    /** Canciones de una playlist virtual (`mix_*`, `favorites`, `station_*`), o `null` si el id no existe. */
    suspend fun virtualPlaylist(id: String): PlaylistPage?

    suspend fun stats(): ListeningStats

    /** Artistas más escuchados (base de "Estaciones para ti" y de la pestaña Radio). */
    suspend fun topArtists(limit: Int = 12): List<Artist>
}

/** Cuentas conectadas. Las cookies y tokens se guardan cifrados (Android Keystore), nunca en claro. */
interface AccountRepository {
    val accounts: StateFlow<AccountsState>

    /** Valida la cookie de `music.youtube.com`, guarda la cuenta y la activa. @throws IllegalArgumentException si no hay sesión. */
    suspend fun addYouTubeAccount(cookie: String): YouTubeAccount
    suspend fun switchYouTubeAccount(id: String)

    /** Cierra la sesión y borra sus cookies. */
    suspend fun removeYouTubeAccount(id: String)

    /** Modo oficial (PKCE): URL que hay que abrir en una Custom Tab. */
    suspend fun spotifyAuthorizationUrl(): String

    /** Canjea el redirect `ondara://spotify-callback?...`. */
    suspend fun completeSpotifyAuthorization(callbackUri: String): SpotifyAccount

    /** Modo experimental: cookie `sp_dc` capturada en el WebView. */
    suspend fun connectSpotifyWithCookie(spDc: String): SpotifyAccount
    suspend fun disconnectSpotify()
}

/** Importación y sincronización de las bibliotecas de las cuentas conectadas. */
interface SyncRepository {
    val progress: StateFlow<SyncProgress>

    /** Encola una sincronización inmediata (única: si ya hay una en curso no se duplica). */
    fun syncNow()

    /** Programa (o cancela, si no hay cuentas) la re-sincronización periódica cada 12 h. */
    fun schedulePeriodic()

    /** Trabajo real de sincronización; lo invoca el `Worker`. */
    suspend fun runSync()

    fun unmatched(): Flow<List<UnmatchedTrack>>

    /** Candidatos de YouTube Music para elegir a mano. */
    suspend fun candidates(track: UnmatchedTrack): List<Song>
    suspend fun resolveManually(track: UnmatchedTrack, song: Song)

    /** Si el ajuste está activo y hay sesión, replica el "Me gusta" en YouTube Music. */
    suspend fun pushLike(song: Song, liked: Boolean)
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
