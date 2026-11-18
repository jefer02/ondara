package app.ondara.core.data

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.ondara.core.database.LibraryDao
import app.ondara.core.database.PlaylistEntity
import app.ondara.core.database.SavedItemEntity
import app.ondara.core.database.SpotifyDao
import app.ondara.core.database.SpotifyMatchEntity
import app.ondara.core.database.SpotifyPlaylistTrackEntity
import app.ondara.core.database.toEntity
import app.ondara.core.domain.SettingsRepository
import app.ondara.core.domain.SyncRepository
import app.ondara.core.model.MusicItem
import app.ondara.core.model.PlaylistSource
import app.ondara.core.model.SearchFilter
import app.ondara.core.model.Song
import app.ondara.core.model.SyncProgress
import app.ondara.core.model.UnmatchedTrack
import app.ondara.source.innertube.InnerTubeClient
import app.ondara.source.innertube.LikeStatus
import app.ondara.source.spotify.SpotifyClient
import app.ondara.source.spotify.SpotifyTrack
import app.ondara.source.spotify.TrackMatcher
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** Datos locales derivados de las cuentas (playlists reflejadas); se borran al desconectar. */
@Singleton
class SpotifyLocalData @Inject constructor(private val library: LibraryDao, private val spotify: SpotifyDao) {
    suspend fun clearSpotify() {
        library.deletePlaylistsBySource(PlaylistSource.SPOTIFY.name)
        spotify.clearAllTracks()
    }

    suspend fun clearYouTubeMirror() = library.deletePlaylistsBySource(PlaylistSource.YOUTUBE.name)
}

/** Replica un "Me gusta" en YouTube Music si el usuario activó la sincronización y hay sesión. */
@Singleton
class LikePusher @Inject constructor(
    private val innerTube: InnerTubeClient,
    private val accounts: AccountStore,
    private val settings: SettingsCache,
    @ApplicationScope private val scope: CoroutineScope,
) {
    suspend fun push(songId: String, liked: Boolean) {
        if (!settings.value.syncLikes || accounts.activeCookie.isBlank()) return
        runCatching { innerTube.rate(songId, if (liked) LikeStatus.LIKE else LikeStatus.INDIFFERENT) }
    }

    /** Versión que no bloquea al llamador (la red va en segundo plano). */
    fun pushAsync(songId: String, liked: Boolean) {
        scope.launch(Dispatchers.IO) { push(songId, liked) }
    }
}

/**
 * Importa y mantiene al día las bibliotecas de las cuentas conectadas.
 *
 * **YouTube Music**: refleja las playlists de la biblioteca y los "Me gusta" como playlists locales
 * con origen `YOUTUBE`.
 *
 * **Spotify**: importa playlists propias y seguidas, "Canciones que te gustan" (playlist "Me gusta de
 * Spotify") y álbumes guardados. Como Spotify no permite reproducir su audio, cada canción se
 * **empareja** con su equivalente de YouTube Music ([TrackMatcher]): se buscan `"{título} {artista}"`
 * (filtro *canciones*), se puntúan los 5 primeros y se acepta el mejor si `score ≥ 0.8`; si no, queda
 * como "sin coincidencia" para elegirla a mano.
 *
 * Complejidad: la primera importación hace O(n) búsquedas de red y O(n · k) puntuaciones (n canciones
 * distintas, k = 5 candidatos), con un máximo de [PARALLEL_SEARCHES] búsquedas simultáneas
 * (`Semaphore`). Los emparejamientos se guardan en Room (`spotifyId → videoId`), así que las
 * re-sincronizaciones son un lookup O(1) por canción (clave primaria) y solo buscan las nuevas.
 */
@Singleton
class SyncRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val innerTube: InnerTubeClient,
    private val spotify: SpotifyClient,
    private val accounts: AccountStore,
    private val library: LibraryDao,
    private val matches: SpotifyDao,
    private val settings: SettingsRepository,
    private val likes: LikePusher,
) : SyncRepository {
    private val _progress = MutableStateFlow(SyncProgress())
    override val progress: StateFlow<SyncProgress> = _progress.asStateFlow()
    private val running = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    override fun syncNow() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(NETWORK).build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.KEEP, request)
    }

    override fun schedulePeriodic() {
        val manager = WorkManager.getInstance(context)
        if (!accounts.state.value.anyConnected) {
            manager.cancelUniqueWork(WORK_PERIODIC)
            return
        }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(SYNC_HOURS, TimeUnit.HOURS).setConstraints(NETWORK).build()
        manager.enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override suspend fun runSync() {
        if (!running.tryLock()) return
        try {
            withContext(Dispatchers.IO) {
                accounts.awaitLoaded()
                val state = accounts.state.value
                _progress.value = SyncProgress(running = true, message = "Preparando…")
                val errors = ArrayList<String>()
                if (state.activeYouTube != null) runCatching { syncYouTube() }.onFailure { rethrowIfCancelled(it); errors += "YouTube: ${it.message}" }
                if (state.spotify != null) runCatching { syncSpotify() }.onFailure { rethrowIfCancelled(it); errors += "Spotify: ${it.message}" }
                _progress.value = SyncProgress(running = false, error = errors.joinToString(" · ").ifEmpty { null })
            }
        } finally {
            _progress.update { it.copy(running = false) }
            running.unlock()
        }
    }

    private fun rethrowIfCancelled(error: Throwable) {
        if (error is CancellationException) throw error
    }

    /** Refleja la biblioteca de YouTube: O(p) peticiones para p playlists (más sus continuaciones). */
    private suspend fun syncYouTube() {
        val remote = innerTube.libraryPlaylists()
        val total = remote.size + 1
        var done = 0
        fun step(name: String) {
            _progress.value = SyncProgress(true, "Importando de YouTube Music: $name", done++, total)
        }
        val keep = HashSet<String>()
        step("Me gusta")
        val liked = runCatching { innerTube.likedSongs() }.getOrDefault(emptyList())
        if (liked.isNotEmpty()) {
            keep += YT_LIKED
            mirror(YT_LIKED, "Me gusta de YouTube Music", PlaylistSource.YOUTUBE, "LM", liked.firstOrNull()?.thumbnailUrl, null, liked)
        }
        for (playlist in remote) {
            step(playlist.title)
            val songs = runCatching { innerTube.playlist(playlist.id).songs }.getOrNull() ?: continue
            val id = YT_PREFIX + playlist.id
            keep += id
            mirror(id, playlist.title, PlaylistSource.YOUTUBE, playlist.id, playlist.thumbnailUrl, playlist.author, songs)
        }
        library.playlistsBySource(PlaylistSource.YOUTUBE.name).filter { it.id !in keep }.forEach { library.deletePlaylist(it.id) }
    }

    private suspend fun mirror(id: String, name: String, source: PlaylistSource, remoteId: String, thumbnail: String?, author: String?, songs: List<Song>) {
        val created = library.playlistsBySource(source.name).firstOrNull { it.id == id }?.createdAt ?: System.currentTimeMillis()
        library.upsertSongs(songs.map { it.toEntity() })
        library.upsertPlaylist(PlaylistEntity(id, name, created, source.name, remoteId, thumbnail, author))
        library.replacePlaylistSongs(id, songs.map { it.id })
    }

    private suspend fun syncSpotify() {
        _progress.value = SyncProgress(true, "Leyendo tu biblioteca de Spotify…")
        val playlists = spotify.playlists()
        val sources = ArrayList<Triple<String, PlaylistEntity, List<SpotifyTrack>>>()
        val now = System.currentTimeMillis()
        val existing = library.playlistsBySource(PlaylistSource.SPOTIFY.name).associateBy { it.id }
        val errors = ArrayList<String>()
        val keep = playlists.mapTo(HashSet()) { SP_PREFIX + it.id }
        val liked = readSpotify("Me gusta", errors) { spotify.savedTracks() }
        if (liked == null || liked.isNotEmpty()) keep += SP_LIKED
        if (!liked.isNullOrEmpty()) {
            val entity = PlaylistEntity(SP_LIKED, "Me gusta de Spotify", existing[SP_LIKED]?.createdAt ?: now,
                PlaylistSource.SPOTIFY.name, "liked", liked.first().imageUrl, null)
            sources += Triple(SP_LIKED, entity, liked)
        }
        playlists.forEachIndexed { index, playlist ->
            _progress.value = SyncProgress(true, "Leyendo playlist de Spotify: ${playlist.name}", index, playlists.size)
            val tracks = readSpotify(playlist.name, errors) { spotify.playlistTracks(playlist.id) } ?: return@forEachIndexed
            val id = SP_PREFIX + playlist.id
            val entity = PlaylistEntity(id, playlist.name, existing[id]?.createdAt ?: now,
                PlaylistSource.SPOTIFY.name, playlist.id, playlist.imageUrl, playlist.owner)
            sources += Triple(id, entity, tracks)
        }
        val unique = LinkedHashMap<String, SpotifyTrack>()
        sources.forEach { (_, _, tracks) -> tracks.forEach { unique.putIfAbsent(it.id, it) } }
        val searchFailures = matchAll(unique.values.toList())
        if (searchFailures > 0) errors += "Quedaron $searchFailures canciones por buscar. Vuelve a importar para reintentarlo."
        for ((id, entity, tracks) in sources) {
            library.upsertPlaylist(entity)
            matches.clearTracks(id)
            matches.putTracks(tracks.distinctBy { it.id }.mapIndexed { i, t -> SpotifyPlaylistTrackEntity(id, t.id, i) })
            materialize(id)
        }
        existing.keys.filter { it !in keep }.forEach { library.deletePlaylist(it); matches.clearTracks(it) }
        readSpotify("Álbumes", errors) { importAlbums() }
        if (errors.isNotEmpty()) {
            val more = (errors.size - 3).takeIf { it > 0 }?.let { " · y $it más" }.orEmpty()
            throw IOException("Importación parcial. ${errors.take(3).joinToString(" · ")}$more")
        }
        settings.update { it.copy(lastSpotifySyncMs = System.currentTimeMillis()) }
    }

    private suspend fun <T> readSpotify(label: String, errors: MutableList<String>, read: suspend () -> T): T? =
        runCatching { read() }.onFailure {
            rethrowIfCancelled(it)
            errors += "$label: ${it.message ?: "no se pudo leer"}"
        }.getOrNull()

    /**
     * Empareja las canciones que aún no están en la tabla `spotify_matches`.
     * Lookup por lotes de 500 ids (límite de variables de SQLite); búsquedas limitadas a 4 simultáneas.
     */
    private suspend fun matchAll(tracks: List<SpotifyTrack>): Int {
        val known = HashSet<String>()
        tracks.map { it.id }.chunked(SQL_BATCH).forEach { ids -> matches.matches(ids).mapTo(known) { it.spotifyId } }
        val total = tracks.size
        val done = AtomicInteger(known.size.coerceAtMost(total))
        val failures = AtomicInteger()
        fun report() {
            _progress.value = SyncProgress(true, "Importando ${done.get()} de $total canciones…", done.get(), total)
        }
        report()
        val gate = Semaphore(PARALLEL_SEARCHES)
        coroutineScope {
            tracks.filter { it.id !in known }.map { track ->
                async {
                    gate.withPermit {
                        val result = runCatching { bestSpotifyMatch(track) }.onFailure(::rethrowIfCancelled)
                        // Un fallo de red no se guarda: la canción se reintentará en la próxima sincronización.
                        if (result.isSuccess) {
                            val best = result.getOrNull()
                            val accepted = best?.takeIf { it.score >= TrackMatcher.ACCEPT_SCORE }
                            accepted?.let { library.upsertSongs(listOf(it.song.toEntity())) }
                            matches.putMatch(
                                SpotifyMatchEntity(
                                    track.id, accepted?.song?.id, track.title, track.artists.joinToString(", "), track.durationMs,
                                    best?.score ?: 0.0, System.currentTimeMillis(),
                                ),
                            )
                        } else {
                            failures.incrementAndGet()
                        }
                        done.incrementAndGet()
                        report()
                    }
                }
            }.awaitAll()
        }
        return failures.get()
    }

    private suspend fun bestSpotifyMatch(track: SpotifyTrack): TrackMatcher.Match? {
        val query = TrackMatcher.query(track)
        val first = TrackMatcher.best(track, searchSongs(query))
        if ((first?.score ?: 0.0) >= TrackMatcher.ACCEPT_SCORE) return first
        val fallback = listOfNotNull(TrackMatcher.normalize(track.title), track.artists.firstOrNull()).joinToString(" ").trim()
        if (fallback.isBlank() || fallback.equals(query, ignoreCase = true)) return first
        val alternative = TrackMatcher.best(track, searchSongs(fallback))
        return if ((alternative?.score ?: 0.0) > (first?.score ?: 0.0)) alternative else first
    }

    private suspend fun searchSongs(query: String): List<Song> =
        innerTube.search(query, SearchFilter.SONGS).items.mapNotNull { (it as? MusicItem.SongItem)?.song }

    /** Reescribe las canciones de una playlist importada con los emparejamientos vigentes, en su orden original. O(n). */
    private suspend fun materialize(playlistId: String) {
        val rows = matches.tracks(playlistId)
        val byId = HashMap<String, String>()
        rows.map { it.spotifyId }.chunked(SQL_BATCH).forEach { ids -> matches.matches(ids).forEach { m -> m.videoId?.let { byId[m.spotifyId] = it } } }
        library.replacePlaylistSongs(playlistId, rows.mapNotNull { byId[it.spotifyId] })
    }

    /** Álbumes guardados en Spotify → álbum equivalente de YouTube Music (similitud de título ≥ 0.8). */
    private suspend fun importAlbums() {
        val albums = spotify.savedAlbums()
        val gate = Semaphore(PARALLEL_SEARCHES)
        val found = coroutineScope {
            albums.map { album ->
                async {
                    gate.withPermit {
                        val wanted = TrackMatcher.normalize(album.title)
                        runCatching { innerTube.search("${album.title} ${album.artists.firstOrNull().orEmpty()}".trim(), SearchFilter.ALBUMS) }.getOrNull()
                            ?.items?.filterIsInstance<MusicItem.AlbumItem>()?.take(TrackMatcher.CANDIDATES)
                            ?.maxByOrNull { TrackMatcher.jaroWinkler(wanted, TrackMatcher.normalize(it.album.title)) }
                            ?.takeIf { TrackMatcher.jaroWinkler(wanted, TrackMatcher.normalize(it.album.title)) >= TrackMatcher.ACCEPT_SCORE }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        val now = System.currentTimeMillis()
        library.addSavedAll(
            found.distinctBy { it.key }.mapIndexed { i, item -> SavedItemEntity(item.key, json.encodeToString(MusicItem.serializer(), item), now - i) },
        )
    }

    override fun unmatched(): Flow<List<UnmatchedTrack>> =
        matches.unmatched().map { list -> list.map { UnmatchedTrack(it.spotifyId, it.title, it.artist, it.durationMs) } }

    override suspend fun candidates(track: UnmatchedTrack): List<Song> = withContext(Dispatchers.IO) {
        val query = "${track.title} ${track.artist.substringBefore(',')}".trim()
        val songs = runCatching { searchSongs(query) }.getOrDefault(emptyList())
        songs.ifEmpty { runCatching { searchSongs(track.title) }.getOrDefault(emptyList()) }.take(MANUAL_CANDIDATES)
    }

    override suspend fun resolveManually(track: UnmatchedTrack, song: Song) = withContext(Dispatchers.IO) {
        library.upsertSongs(listOf(song.toEntity()))
        matches.putMatch(SpotifyMatchEntity(track.spotifyId, song.id, track.title, track.artist, track.durationMs, 1.0, System.currentTimeMillis()))
        matches.playlistsOf(track.spotifyId).forEach { materialize(it.playlistId) }
    }

    override suspend fun pushLike(song: Song, liked: Boolean) = likes.push(song.id, liked)

    private companion object {
        const val PARALLEL_SEARCHES = 4
        const val SQL_BATCH = 500
        const val MANUAL_CANDIDATES = 10
        const val SYNC_HOURS = 12L
        const val WORK_NOW = "ondara-sync-now"
        const val WORK_PERIODIC = "ondara-sync-periodic"
        const val YT_PREFIX = "yt_"
        const val YT_LIKED = "yt_LM"
        const val SP_PREFIX = "sp_"
        const val SP_LIKED = "sp_liked"
        val NETWORK = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    }
}

/** Sincronización en segundo plano (manual o periódica cada 12 h). */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val sync: SyncRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        sync.runSync()
        return if (sync.progress.value.error == null) Result.success() else Result.retry()
    }
}
