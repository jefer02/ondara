package app.ondara.core.data

import app.ondara.core.database.ArtistTagsEntity
import app.ondara.core.database.CacheDao
import app.ondara.core.database.LibraryDao
import app.ondara.core.database.PageCacheEntity
import app.ondara.core.domain.CatalogRepository
import app.ondara.core.domain.LibraryRepository
import app.ondara.core.domain.PersonalRepository
import app.ondara.core.domain.Resource
import app.ondara.core.model.Artist
import app.ondara.core.model.ArtistPage
import app.ondara.core.model.ArtistRef
import app.ondara.core.model.ListeningStats
import app.ondara.core.model.MixIds
import app.ondara.core.model.Mood
import app.ondara.core.model.MusicItem
import app.ondara.core.model.PersonalHome
import app.ondara.core.model.Playlist
import app.ondara.core.model.PlaylistPage
import app.ondara.core.model.Song
import app.ondara.core.model.TopPick
import app.ondara.core.network.array
import app.ondara.core.network.get
import app.ondara.core.network.int
import app.ondara.core.network.string
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Géneros de un artista a partir de las etiquetas de **MusicBrainz** (API pública, sin clave),
 * cacheadas 30 días en Room. MusicBrainz pide como máximo 1 petición por segundo: las consultas
 * sin caché se espacian.
 */
@Singleton
class GenreResolver @Inject constructor(private val http: HttpClient, private val dao: LibraryDao) {
    private val json = Json { ignoreUnknownKeys = true }
    private val tagList = ListSerializer(String.serializer())

    /** Hasta 5 etiquetas en minúsculas, de más a menos votada. O(1) con caché; una petición sin ella. */
    suspend fun tags(artist: String): List<String> {
        val key = artist.trim().lowercase()
        if (key.isEmpty()) return emptyList()
        dao.artistTags(key)?.takeIf { System.currentTimeMillis() - it.fetchedAt < TTL_MS }?.let { row ->
            return runCatching { json.decodeFromString(tagList, row.tags) }.getOrDefault(emptyList())
        }
        val tags = try {
            delay(RATE_LIMIT_MS)
            val body: JsonObject = http.get("https://musicbrainz.org/ws/2/artist/") {
                parameter("query", "artist:\"${artist.replace("\"", "")}\"")
                parameter("fmt", "json")
                parameter("limit", 1)
                header("User-Agent", "Ondara/2.0 (reproductor de musica de codigo abierto)")
            }.body()
            body["artists"][0]["tags"].array
                .sortedByDescending { it["count"].int ?: 0 }
                .mapNotNull { it["name"].string?.lowercase() }
                .take(MAX_TAGS)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return emptyList()
        }
        dao.putArtistTags(ArtistTagsEntity(key, json.encodeToString(tagList, tags), System.currentTimeMillis()))
        return tags
    }

    private companion object {
        const val TTL_MS = 30L * 24 * 3_600_000
        const val RATE_LIMIT_MS = 1_100L
        const val MAX_TAGS = 5
    }
}

/** Clasificación de etiquetas de género en un estado de ánimo (heurística por palabras clave). */
internal object MoodClassifier {
    private val calm = listOf("ambient", "classical", "lo-fi", "lofi", "acoustic", "jazz", "chill", "new age", "bossa", "folk", "instrumental", "piano")
    private val energetic =
        listOf("rock", "metal", "punk", "edm", "electro", "house", "techno", "reggaeton", "hip hop", "hip-hop", "rap", "trap", "drum", "dance")
    private val happy = listOf("pop", "latin", "salsa", "cumbia", "vallenato", "merengue", "funk", "disco", "reggae", "tropical", "k-pop", "bachata")
    private val melancholy =
        listOf("blues", "ballad", "balada", "bolero", "sad", "emo", "ranchera", "soul", "singer-songwriter", "tango", "fado", "mariachi")

    fun moodOf(tag: String): Mood? = when {
        calm.any { it in tag } -> Mood.CALM
        melancholy.any { it in tag } -> Mood.MELANCHOLY
        energetic.any { it in tag } -> Mood.ENERGETIC
        happy.any { it in tag } -> Mood.HAPPY
        else -> null
    }

    /** Estado de ánimo más votado; cada etiqueta pesa lo que su artista. O(t) con t etiquetas. */
    fun dominant(weighted: List<Pair<String, Int>>): Mood {
        val votes = HashMap<Mood, Int>()
        for ((tag, weight) in weighted) moodOf(tag)?.let { votes[it] = (votes[it] ?: 0) + weight }
        return votes.maxByOrNull { it.value }?.key ?: Mood.NEUTRAL
    }
}

/** Cuenta apariciones y ordena de más a menos frecuente (estable ante empates). O(n) + O(u log u) con u únicos. */
internal fun <T, K> List<T>.rankBy(key: (T) -> K?): List<Pair<T, Int>> {
    val counts = LinkedHashMap<K, Pair<T, Int>>()
    for (item in this) {
        val k = key(item) ?: continue
        counts[k] = (counts[k]?.let { it.first to it.second + 1 }) ?: (item to 1)
    }
    return counts.values.sortedByDescending { it.second }
}

/**
 * Secciones personales del Inicio y del perfil, derivadas del historial local.
 *
 * El Inicio se sirve primero desde la caché (`page_cache`) y se recalcula si tiene más de
 * [HOME_TTL_MS]; los mixes se regeneran una vez al día y el Replay una vez por semana.
 */
@Singleton
class PersonalRepositoryImpl @Inject constructor(
    private val library: LibraryRepository,
    private val catalog: CatalogRepository,
    private val cache: CacheDao,
    private val genres: GenreResolver,
) : PersonalRepository {
    private val json = Json { ignoreUnknownKeys = true }
    private val songList = ListSerializer(Song.serializer())

    override fun home(forceRefresh: Boolean): Flow<PersonalHome> = flow {
        val row = cache.page(HOME_KEY)
        val stored = row?.let { runCatching { json.decodeFromString(PersonalHome.serializer(), it.json) }.getOrNull() }
        if (stored != null) emit(stored)
        val fresh = row != null && System.currentTimeMillis() - row.updatedAt < HOME_TTL_MS
        if (stored != null && fresh && !forceRefresh) return@flow
        val computed = buildHome()
        cache.putPage(PageCacheEntity(HOME_KEY, json.encodeToString(PersonalHome.serializer(), computed), System.currentTimeMillis()))
        emit(computed)
    }.flowOn(Dispatchers.IO)

    /**
     * O(h log h + m·n + a) local para historial h, m mixes y n canciones; O(a + m·s) peticiones
     * cacheadas para artistas a y semillas s. Los metadatos usan las mismas instantáneas que la reproducción.
     */
    private suspend fun buildHome(): PersonalHome {
        val now = System.currentTimeMillis()
        val plays = library.playsSince(now - RECENT_WINDOW_MS)
        val favorites = library.favorites().first()
        val artists = topArtistRefs(plays, STATIONS)
        val pages = artists.mapNotNull { ref -> ref.id?.let { id -> artistPage(id)?.let { ref to it } } }
        val stations = artists.map { ref ->
            val page = pages.firstOrNull { it.first.id == ref.id }?.second
            Artist(ref.id!!, ref.name, page?.artist?.thumbnailUrl ?: plays.firstOrNull { s -> s.artists.any { it.id == ref.id } }?.thumbnailUrl)
        }
        val mixes = MIXES.mapNotNull { (id, _) -> virtualPlaylist(id)?.playlist }
            .filter { it.artworkUrls.isNotEmpty() }
        val picks = ArrayList<TopPick>()
        if (favorites.isNotEmpty()) {
            picks += TopPick("Lo que más escuchas", MusicItem.PlaylistItem(page(MixIds.FAVORITES, "Tus favoritas", favorites).playlist))
        }
        stations.take(3).forEach { artist ->
            virtualPlaylist(MixIds.STATION_PREFIX + artist.id)?.playlist?.takeIf { it.artworkUrls.isNotEmpty() }?.let {
                picks += TopPick("Porque escuchaste ${artist.name}", MusicItem.PlaylistItem(it))
            }
        }
        mixes.firstOrNull { it.id == MixIds.DISCOVER }?.let {
            picks += TopPick("Música nueva para ti", MusicItem.PlaylistItem(it))
        }
        return PersonalHome(
            topPicks = picks,
            mixes = mixes,
            releases = pages.flatMap { (_, page) -> latestReleases(page) }
                .distinctBy { it.key }
                .sortedByDescending { (it as? MusicItem.AlbumItem)?.album?.year ?: 0 }
                .take(RELEASES),
            stations = stations,
            replay = replay(),
            replayYear = Calendar.getInstance().get(Calendar.YEAR),
        )
    }

    private suspend fun <T> firstData(source: () -> Flow<Resource<T>>): T? = try {
        when (val result = source().firstOrNull { it !is Resource.Loading }) {
            is Resource.Success -> result.data
            is Resource.Error -> result.cached
            else -> null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private suspend fun artistPage(id: String): ArtistPage? = firstData { catalog.artist(id) }

    /** Los dos lanzamientos más recientes de las estanterías de álbumes y sencillos del artista. */
    private fun latestReleases(page: ArtistPage): List<MusicItem> =
        page.shelves.flatMap { it.items }.filterIsInstance<MusicItem.AlbumItem>().distinctBy { it.key }.sortedByDescending { it.album.year ?: 0 }.take(2)

    private fun topArtistRefs(plays: List<Song>, limit: Int): List<ArtistRef> =
        plays.mapNotNull { it.artists.firstOrNull { a -> a.id != null } }.rankBy { it.id }.take(limit).map { it.first }

    override suspend fun topArtists(limit: Int): List<Artist> = withContext(Dispatchers.IO) {
        val plays = library.playsSince(System.currentTimeMillis() - RECENT_WINDOW_MS)
        topArtistRefs(plays, limit).map { ref ->
            Artist(ref.id!!, ref.name, plays.firstOrNull { s -> s.artists.firstOrNull { it.id != null }?.id == ref.id }?.thumbnailUrl)
        }
    }

    /** Canciones más escuchadas del año; instantánea semanal. O(h) sobre el historial del año. */
    private suspend fun replay(): List<Song> {
        val calendar = Calendar.getInstance()
        val key = "replay:${calendar.get(Calendar.YEAR)}:${calendar.get(Calendar.WEEK_OF_YEAR)}"
        cachedSongs(key)?.let { return it }
        val start = startOf(Calendar.DAY_OF_YEAR)
        val top = library.playsSince(start).rankBy { it.id }.take(REPLAY_SIZE).map { it.first }
        if (top.isNotEmpty()) storeSongs(key, top)
        return top
    }

    /** Medianoche del primer día del año ([Calendar.DAY_OF_YEAR]) o del mes ([Calendar.DAY_OF_MONTH]). */
    private fun startOf(field: Int): Long = Calendar.getInstance().apply {
        set(field, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private suspend fun cachedSongs(key: String): List<Song>? =
        cache.page(key)?.let { runCatching { json.decodeFromString(songList, it.json) }.getOrNull() }?.takeIf { it.isNotEmpty() }

    private suspend fun storeSongs(key: String, songs: List<Song>) =
        cache.putPage(PageCacheEntity(key, json.encodeToString(songList, songs), System.currentTimeMillis()))

    override suspend fun virtualPlaylist(id: String): PlaylistPage? = withContext(Dispatchers.IO) {
        when {
            id == MixIds.FAVORITES -> page(id, "Tus favoritas", library.favorites().first())
            id == MixIds.REPLAY -> page(id, "Tu Replay ${Calendar.getInstance().get(Calendar.YEAR)}", replay())
            id.startsWith(MixIds.STATION_PREFIX) -> station(id)
            id.startsWith(MixIds.PREFIX) -> {
                val title = MIXES.firstOrNull { it.first == id }?.second ?: return@withContext null
                val day = Calendar.getInstance().let { it.get(Calendar.YEAR) * 1000 + it.get(Calendar.DAY_OF_YEAR) }
                val key = "mix:$id:$day"
                val songs = cachedSongs(key) ?: buildMix(id, day).also { if (it.isNotEmpty()) storeSongs(key, it) }
                page(id, title, songs)
            }
            else -> null
        }
    }

    /** Metadatos de canciones reales: O(n + a log a) tiempo y O(a) espacio para a artistas distintos. */
    private fun page(id: String, title: String, songs: List<Song>) = PlaylistPage(
        Playlist(
            id, title, author = app.ondara.core.model.mixSubtitle(songs), songCount = songs.size,
            artworkUrls = songs.asSequence().mapNotNull { it.thumbnailUrl }.filter { it.isNotBlank() }.distinct().take(4).toList(),
        ),
        songs,
    )

    private suspend fun station(id: String): PlaylistPage? {
        val artist = artistPage(id.removePrefix(MixIds.STATION_PREFIX)) ?: return null
        val seed = artist.topSongs.firstOrNull()
        val radio = seed?.let { runCatching { catalog.radio(it.id, artist.radioPlaylistId) }.getOrNull() }.orEmpty()
        val songs = (artist.topSongs.take(STATION_TOP) + radio).distinctBy { it.id }
        return page(id, "Radio de ${artist.artist.name}", songs)
    }

    /**
     * Genera un mix. Todos parten del historial y del endpoint `next` de YouTube Music:
     * O(s) peticiones para s semillas (≤ 3) y O(n) para deduplicar con `HashSet`.
     */
    private suspend fun buildMix(id: String, seed: Int): List<Song> {
        val played = library.playsSince(0)
        val top = played.rankBy { it.id }.map { it.first }
        val heard = played.mapTo(HashSet()) { it.id }
        return when (id) {
            MixIds.FAVORITES_MIX -> (library.favorites().first() + top.take(MIX_SIZE).ifEmpty { newsSongs() })
                .distinctBy { it.id }.shuffled(Random(seed)).take(MIX_SIZE)
            MixIds.DISCOVER -> related(top.take(SEEDS).ifEmpty { fallbackSeeds() }, heard)
            MixIds.NEW -> (newsSongs() + related(played.distinctBy { it.id }.take(SEEDS), heard)).distinctBy { it.id }.take(MIX_SIZE)
            MixIds.CALM -> calmSongs().ifEmpty { related(fallbackSeeds(), emptySet()) }
            else -> emptyList()
        }
    }

    /** Intercala las radios de cada semilla sin repetir ni incluir [exclude]. */
    private suspend fun related(seeds: List<Song>, exclude: Set<String>): List<Song> {
        val radios = seeds.map { seed -> runCatching { catalog.radio(seed.id) }.getOrDefault(emptyList()) }
        val seen = HashSet(exclude)
        val out = ArrayList<Song>()
        var index = 0
        while (out.size < MIX_SIZE && radios.any { index < it.size }) {
            for (radio in radios) radio.getOrNull(index)?.let { if (seen.add(it.id)) out += it }
            index++
        }
        return out.take(MIX_SIZE)
    }

    /** Canciones reales de Novedades; si solo hay álbumes/playlists, abre hasta tres. O(n), hasta 4 peticiones. */
    private suspend fun newsSongs(): List<Song> {
        val items = firstData { catalog.news() }.orEmpty().flatMap { it.items }
        val songs = items.mapNotNull { (it as? MusicItem.SongItem)?.song }
        if (songs.isNotEmpty()) return songs.distinctBy { it.id }
        return items.filter { it is MusicItem.PlaylistItem || it is MusicItem.AlbumItem }.take(SEEDS).flatMap {
            when (it) {
                is MusicItem.PlaylistItem -> firstData { catalog.playlist(it.playlist.id) }?.songs.orEmpty()
                is MusicItem.AlbumItem -> firstData { catalog.album(it.album.id) }?.songs.orEmpty()
                else -> emptyList()
            }
        }.distinctBy { it.id }.take(MIX_SIZE)
    }

    /** Sin historial, las semillas salen de las tendencias. */
    private suspend fun fallbackSeeds(): List<Song> = newsSongs().take(SEEDS)

    private suspend fun calmSongs(): List<Song> {
        val calm = firstData { catalog.moods() }.orEmpty().firstOrNull { m -> CALM_WORDS.any { it in m.title.lowercase() } } ?: return emptyList()
        val shelves = firstData { catalog.moodShelves(calm.browseId, calm.params) }.orEmpty()
        val playlist = shelves.flatMap { it.items }.filterIsInstance<MusicItem.PlaylistItem>().firstOrNull() ?: return emptyList()
        return firstData { catalog.playlist(playlist.playlist.id) }?.songs.orEmpty().take(MIX_SIZE)
    }

    override suspend fun stats(): ListeningStats = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val recent = library.playsSince(now - RECENT_WINDOW_MS)
        val month = library.playsSince(startOf(Calendar.DAY_OF_MONTH))
        val week = library.playsSince(now - WEEK_MS)
        val rankedArtists = month.ifEmpty { recent }.mapNotNull { it.artists.firstOrNull() }.rankBy { it.name.lowercase() }
        val topArtist = rankedArtists.firstOrNull()?.first?.let { ref ->
            val page = ref.id?.let { artistPage(it) }
            val fallback = recent.firstOrNull { s -> s.artists.firstOrNull()?.name == ref.name }?.thumbnailUrl
            Artist(ref.id ?: ref.name, ref.name, page?.artist?.thumbnailUrl ?: fallback)
        }
        // Género: etiquetas de los 3 artistas más escuchados, ponderadas por reproducciones.
        val genreVotes = HashMap<String, Int>()
        for ((ref, count) in rankedArtists.take(GENRE_ARTISTS)) {
            genres.tags(ref.name).take(2).forEach { tag -> genreVotes[tag] = (genreVotes[tag] ?: 0) + count }
        }
        val weekArtists = week.mapNotNull { it.artists.firstOrNull() }.rankBy { it.name.lowercase() }.take(GENRE_ARTISTS)
        val moodVotes = weekArtists.flatMap { (ref, count) -> genres.tags(ref.name).map { it to count } }
        val topSongs = recent.rankBy { it.id }.map { it.first }
        val pool = topSongs.take(SONG_OF_DAY_POOL).ifEmpty { library.favorites().first() }
        ListeningStats(
            // Aproximación: cada reproducción cuenta la duración completa de la canción.
            minutesThisMonth = (month.sumOf { it.durationSec.toLong() } / 60).toInt(),
            topGenre = genreVotes.maxByOrNull { it.value }?.key?.replaceFirstChar { it.uppercase() },
            topArtist = topArtist,
            mood = MoodClassifier.dominant(moodVotes),
            songOfTheDay = if (pool.isEmpty()) null else pool[Calendar.getInstance().get(Calendar.DAY_OF_YEAR) % pool.size],
            topArtworks = topSongs.mapNotNull { it.thumbnailUrl }.distinct().take(ARTWORKS),
        )
    }

    private companion object {
        const val HOME_KEY = "personal_home_artwork_v2"
        const val HOME_TTL_MS = 6 * 3_600_000L
        const val RECENT_WINDOW_MS = 90L * 24 * 3_600_000
        const val WEEK_MS = 7L * 24 * 3_600_000
        const val STATIONS = 8
        const val RELEASES = 12
        const val REPLAY_SIZE = 50
        const val MIX_SIZE = 40
        const val SEEDS = 3
        const val STATION_TOP = 5
        const val GENRE_ARTISTS = 3
        const val SONG_OF_DAY_POOL = 30
        const val ARTWORKS = 6
        val CALM_WORDS = listOf("calma", "chill", "relax", "relaj", "tranquil")
        val MIXES = listOf(
            MixIds.FAVORITES_MIX to "Mix Favoritos",
            MixIds.NEW to "Mix Novedades",
            MixIds.CALM to "Mix Calma",
            MixIds.DISCOVER to "Mix Descubrir",
        )
    }
}
