package app.ondara.core.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import app.ondara.core.database.CacheDao
import app.ondara.core.database.PageCacheEntity
import app.ondara.core.domain.CatalogRepository
import app.ondara.core.domain.Resource
import app.ondara.core.domain.SongEquivalenceIndex
import app.ondara.core.domain.isMusicVideo
import app.ondara.core.model.AlbumPage
import app.ondara.core.model.ArtistPage
import app.ondara.core.model.HomeFeed
import app.ondara.core.model.HomeShelf
import app.ondara.core.model.MusicItem
import app.ondara.core.model.PlaylistPage
import app.ondara.core.model.SearchFilter
import app.ondara.core.model.Song
import app.ondara.source.innertube.InnerTubeClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import app.ondara.core.model.MoodCategory
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Catálogo offline-first: cada página se emite primero desde Room (`page_cache`) y luego se
 * refresca desde InnerTube si está caducada. Sin red, lo cacheado sigue disponible.
 */
@Singleton
class CatalogRepositoryImpl @Inject constructor(
    private val innerTube: InnerTubeClient,
    private val cache: CacheDao,
) : CatalogRepository {
    private val json = Json { ignoreUnknownKeys = true }

    override fun home(forceRefresh: Boolean): Flow<Resource<HomeFeed>> =
        cached("home", HomeFeed.serializer(), HOME_TTL_MS, forceRefresh) {
            coroutineScope {
                val home = async { innerTube.home(continuations = 1) }
                val charts = async { runCatching { innerTube.charts() }.getOrDefault(emptyList()) }
                val moods = async { runCatching { innerTube.moods() }.getOrDefault(emptyList()) }
                HomeFeed(home.await() + charts.await(), moods.await())
            }
        }

    override fun moodShelves(browseId: String, params: String?): Flow<Resource<List<HomeShelf>>> =
        cached("mood:$browseId:$params", ListSerializer(HomeShelf.serializer()), PAGE_TTL_MS, false) {
            innerTube.moodShelves(browseId, params)
        }

    override fun album(browseId: String): Flow<Resource<AlbumPage>> =
        cached("album:$browseId", AlbumPage.serializer(), PAGE_TTL_MS, false) { innerTube.album(browseId) }

    override fun artist(browseId: String): Flow<Resource<ArtistPage>> =
        cached("artist:$browseId", ArtistPage.serializer(), PAGE_TTL_MS, false) { innerTube.artist(browseId) }

    override fun playlist(playlistId: String): Flow<Resource<PlaylistPage>> =
        cached("playlist:$playlistId", PlaylistPage.serializer(), HOME_TTL_MS, false) { innerTube.playlist(playlistId) }

    override fun search(query: String, filter: SearchFilter): Flow<PagingData<MusicItem>> =
        Pager(PagingConfig(pageSize = 20, enablePlaceholders = false)) { SearchPagingSource(innerTube, query, filter) }.flow

    override suspend fun suggestions(query: String): List<String> =
        if (query.isBlank()) emptyList() else runCatching { innerTube.suggestions(query) }.getOrDefault(emptyList())

    override suspend fun radio(videoId: String, playlistId: String?): List<Song> = innerTube.next(videoId, playlistId)

    /** Explorar + listas de éxitos + lanzamientos; las estanterías repetidas (mismo título) se descartan. */
    override fun news(forceRefresh: Boolean): Flow<Resource<List<HomeShelf>>> =
        cached("news", ListSerializer(HomeShelf.serializer()), HOME_TTL_MS, forceRefresh) {
            coroutineScope {
                val explore = async { runCatching { innerTube.explore() }.getOrDefault(emptyList()) }
                val releases = async { runCatching { innerTube.newReleases() }.getOrDefault(emptyList()) }
                val charts = async { runCatching { innerTube.charts() }.getOrDefault(emptyList()) }
                val home = async { runCatching { innerTube.home(continuations = 0) }.getOrDefault(emptyList()) }
                val all = (releases.await() + explore.await() + charts.await() + home.await()).filter { it.items.isNotEmpty() }
                check(all.isNotEmpty()) { "Sin conexión" }
                all.distinctBy { it.title.lowercase() }
            }
        }

    override fun moods(): Flow<Resource<List<MoodCategory>>> =
        cached("moods", ListSerializer(MoodCategory.serializer()), PAGE_TTL_MS, false) { innerTube.moods() }

    override suspend fun moodArtwork(mood: MoodCategory): String? = withContext(Dispatchers.IO) {
        val key = "moodart:${mood.browseId}:${mood.params}"
        cache.page(key)?.let { return@withContext it.json.ifEmpty { null } }
        val shelves = runCatching { innerTube.moodShelves(mood.browseId, mood.params) }.getOrNull() ?: return@withContext null
        val url = shelves.asSequence().flatMap { it.items }.firstNotNullOfOrNull { item ->
            when (item) {
                is MusicItem.PlaylistItem -> item.playlist.thumbnailUrl
                is MusicItem.AlbumItem -> item.album.thumbnailUrl
                is MusicItem.SongItem -> item.song.thumbnailUrl
                is MusicItem.ArtistItem -> null
            }
        }
        cache.putPage(PageCacheEntity(key, url.orEmpty(), System.currentTimeMillis()))
        url
    }

    private fun <T> cached(
        key: String,
        serializer: KSerializer<T>,
        ttlMs: Long,
        force: Boolean,
        fetch: suspend () -> T,
    ): Flow<Resource<T>> = flow {
        val row = cache.page(key)
        val stored = row?.let { runCatching { json.decodeFromString(serializer, it.json) }.getOrNull() }
        if (stored != null) emit(Resource.Success(stored, fromCache = true)) else emit(Resource.Loading)
        val fresh = row != null && System.currentTimeMillis() - row.updatedAt < ttlMs
        if (stored != null && fresh && !force) return@flow
        try {
            val data = fetch()
            cache.putPage(PageCacheEntity(key, json.encodeToString(serializer, data), System.currentTimeMillis()))
            emit(Resource.Success(data, fromCache = false))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Con caché se conserva lo mostrado; sin ella se informa del error.
            if (stored == null) emit(Resource.Error(e.message ?: "Sin conexión")) else if (force) emit(Resource.Error(e.message ?: "Sin conexión", stored))
        }
    }.flowOn(Dispatchers.IO)

    private companion object {
        const val HOME_TTL_MS = 30 * 60_000L
        const val PAGE_TTL_MS = 24 * 3_600_000L
    }
}

/** Paging 3 sobre los tokens de continuación de InnerTube (clave = token; `null` = primera página). */
internal class SearchPagingSource(
    private val innerTube: InnerTubeClient,
    private val query: String,
    private val filter: SearchFilter,
) : PagingSource<String, MusicItem>() {
    /**
     * Completa el resultado principal solo en la primera página de Todo: como máximo 1 petición extra.
     * O(n + m + l) tiempo y espacio para n resultados, m candidatos y l caracteres de metadatos.
     * Un fallo auxiliar conserva la página original; la cancelación siempre se propaga.
     */
    private val seen = HashSet<String>()

    private suspend fun preferSong(items: List<MusicItem>): List<MusicItem> {
        val first = (items.firstOrNull() as? MusicItem.SongItem)?.song ?: return items
        if (!first.isMusicVideo() || SongEquivalenceIndex(items).equivalent(first) != null) return items
        val candidates = try {
            innerTube.search(query, SearchFilter.SONGS).items
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return items
        }
        val song = SongEquivalenceIndex(candidates).equivalent(first) ?: return items
        return listOf(MusicItem.SongItem(song)) + items
    }

    override fun getRefreshKey(state: PagingState<String, MusicItem>): String? = null

    override suspend fun load(params: LoadParams<String>): LoadResult<String, MusicItem> = try {
        val page = params.key?.let { innerTube.searchContinuation(it) } ?: innerTube.search(query, filter)
        val ranked = if (params.key == null && filter == SearchFilter.ALL) preferSong(page.items) else page.items
        // Sin repetidos entre páginas (la UI ya no depende de ello para sus claves). O(n) con HashSet.
        val items = ranked.filter { seen.add(it.key) }
        LoadResult.Page(items, prevKey = null, nextKey = page.continuation?.takeIf { items.isNotEmpty() })
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LoadResult.Error(e)
    }
}
