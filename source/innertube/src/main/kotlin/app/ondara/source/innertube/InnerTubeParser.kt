package app.ondara.source.innertube

import app.ondara.core.model.Album
import app.ondara.core.model.AlbumPage
import app.ondara.core.model.AlbumRef
import app.ondara.core.model.Artist
import app.ondara.core.model.ArtistPage
import app.ondara.core.model.ArtistRef
import app.ondara.core.model.HomeShelf
import app.ondara.core.model.MoodCategory
import app.ondara.core.model.MusicItem
import app.ondara.core.model.Playlist
import app.ondara.core.model.PlaylistPage
import app.ondara.core.model.SearchPage
import app.ondara.core.model.Song
import app.ondara.core.network.array
import app.ondara.core.network.findAll
import app.ondara.core.network.findFirst
import app.ondara.core.network.get
import app.ondara.core.network.long
import app.ondara.core.network.path
import app.ondara.core.network.string
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Convierte respuestas de InnerTube en modelos de dominio.
 *
 * Es deliberadamente tolerante: busca *renderers* por nombre en vez de depender de rutas exactas,
 * así un cambio de envoltorio por parte de YouTube no rompe el parseo.
 * Todos los métodos son O(n) sobre el tamaño del JSON.
 */
object InnerTubeParser {
    private const val LIST_ITEM = "musicResponsiveListItemRenderer"
    private const val TWO_ROW = "musicTwoRowItemRenderer"
    private val DURATION = Regex("""^\d{1,2}(:\d{2}){1,2}$""")
    private val YEAR = Regex("""^(19|20)\d{2}$""")
    private val SIZE = Regex("""=w\d+-h\d+""")
    private val COUNT = Regex("""([\d.,]+)\s*(tracks?|songs?|canciones|canción|pistas?)""", RegexOption.IGNORE_CASE)

    // ---- Búsqueda ---------------------------------------------------------------------------

    fun search(json: JsonObject): SearchPage {
        val shelves = json["contents"].findAll("musicShelfRenderer")
        // La búsqueda sin filtro mezcla `musicCardShelfRenderer` e `itemSectionRenderer`: se toma toda fila de lista.
        val items = json["contents"].findAll(LIST_ITEM).mapNotNull(::listItem)
        // Solo hay paginación cuando la búsqueda está filtrada (una única estantería).
        val token = shelves.singleOrNull()?.let(::continuationOf)
        return SearchPage(items.distinctBy { it.key }, token)
    }

    fun searchContinuation(json: JsonObject): SearchPage {
        val shelf = json.path("continuationContents", "musicShelfContinuation")
        return SearchPage(shelf["contents"].array.mapNotNull { listItem(it[LIST_ITEM]) }, shelf?.let(::continuationOf))
    }

    fun suggestions(json: JsonObject): List<String> = json.findAll("searchSuggestionRenderer")
        .mapNotNull { it.path("suggestion", "runs").runsText() }
        .filter { it.isNotBlank() }

    // ---- Estanterías (home, charts, moods, artista) --------------------------------------------

    fun shelves(json: JsonElement?): List<HomeShelf> {
        val out = ArrayList<HomeShelf>()
        for (shelf in json.findAll("musicCarouselShelfRenderer")) {
            val header = shelf.path("header", "musicCarouselShelfBasicHeaderRenderer")
            val title = header.path("title", "runs").runsText() ?: continue
            val items = shelf["contents"].array.mapNotNull { anyItem(it) }
            if (items.isNotEmpty()) out += HomeShelf(title, header.path("strapline", "runs").runsText(), items)
        }
        // Las páginas de mood usan rejillas en vez de carruseles.
        for (grid in json.findAll("gridRenderer")) {
            val items = grid["items"].array.mapNotNull { anyItem(it) }
            val title = grid.path("header", "gridHeaderRenderer", "title", "runs").runsText().orEmpty()
            if (items.isNotEmpty()) out += HomeShelf(title, null, items)
        }
        return out
    }

    fun sectionContinuation(json: JsonObject): String? =
        (json.findFirst("sectionListRenderer") ?: json.findFirst("sectionListContinuation"))?.let(::continuationOf)

    fun moods(json: JsonObject): List<MoodCategory> = json.findAll("musicNavigationButtonRenderer").mapNotNull { b ->
        val endpoint = b.path("clickCommand", "browseEndpoint")
        MoodCategory(
            title = b.path("buttonText", "runs").runsText() ?: return@mapNotNull null,
            browseId = endpoint["browseId"].string ?: return@mapNotNull null,
            params = endpoint["params"].string,
            color = b.path("solid", "leftStripeColor").long ?: 0xFF888888,
        )
    }.distinctBy { it.title }

    // ---- Álbum / artista / playlist -----------------------------------------------------------

    fun album(browseId: String, json: JsonObject): AlbumPage {
        val header = json.findFirst("musicResponsiveHeaderRenderer") ?: json.findFirst("musicDetailHeaderRenderer")
        val title = header.path("title", "runs").runsText().orEmpty()
        val artists = header.path("straplineTextOne", "runs").artistRefs()
            .ifEmpty { header.path("subtitle", "runs").artistRefs() }
        val thumb = header["thumbnail"].bestThumbnail() ?: json["background"].bestThumbnail()
        val album = Album(
            id = browseId,
            title = title,
            artists = artists,
            year = header.path("subtitle", "runs").array.firstNotNullOfOrNull { r -> r["text"].string?.takeIf(YEAR::matches)?.toInt() },
            thumbnailUrl = thumb,
            playlistId = json.findAll("playlistId").firstNotNullOfOrNull { it.string?.takeIf { id -> id.startsWith("OLAK") } },
        )
        val ref = AlbumRef(browseId, title)
        val shelf = json.findFirst("musicShelfRenderer")
        val songs = shelf["contents"].array.mapNotNull { (listItem(it[LIST_ITEM]) as? MusicItem.SongItem)?.song }
            .map { s -> s.copy(album = ref, thumbnailUrl = s.thumbnailUrl ?: thumb, artists = s.artists.ifEmpty { artists }) }
        val others = json.findAll("musicCarouselShelfRenderer").flatMap { it["contents"].array }
            .mapNotNull { (anyItem(it) as? MusicItem.AlbumItem)?.album }
        return AlbumPage(album, songs, header.findFirst("musicDescriptionShelfRenderer").path("description", "runs").runsText(), others)
    }

    fun artist(browseId: String, json: JsonObject): ArtistPage {
        val header = json["header"]
        val h = header["musicImmersiveHeaderRenderer"] ?: header["musicVisualHeaderRenderer"] ?: header["musicHeaderRenderer"]
        val artist = Artist(
            id = browseId,
            name = h.path("title", "runs").runsText().orEmpty(),
            thumbnailUrl = h["thumbnail"].bestThumbnail() ?: h["foregroundThumbnail"].bestThumbnail(),
            subscribers = h.path("subscriptionButton", "subscribeButtonRenderer", "subscriberCountText", "runs").runsText(),
        )
        val topSongs = json["contents"].findFirst("musicShelfRenderer")["contents"].array
            .mapNotNull { (listItem(it[LIST_ITEM]) as? MusicItem.SongItem)?.song }
        return ArtistPage(
            artist = artist,
            description = h.path("description", "runs").runsText()
                ?: json.findFirst("musicDescriptionShelfRenderer").path("description", "runs").runsText(),
            topSongs = topSongs,
            shelves = shelves(json["contents"]),
            radioPlaylistId = h["startRadioButton"].findFirst("playlistId").string,
        )
    }

    fun playlist(playlistId: String, json: JsonObject): PlaylistPage {
        val header = json.findFirst("musicResponsiveHeaderRenderer") ?: json.findFirst("musicDetailHeaderRenderer")
        val shelf = json.findFirst("musicPlaylistShelfRenderer")
        val songs = playlistSongs(shelf["contents"])
        val meta = header.path("secondSubtitle", "runs").runsText().orEmpty()
        val playlist = Playlist(
            id = playlistId,
            title = header.path("title", "runs").runsText().orEmpty(),
            author = header.path("straplineTextOne", "runs").runsText()
                ?: header.findFirst("facepile").findFirst("content").string,
            songCount = COUNT.find(meta)?.groupValues?.get(1)?.filter(Char::isDigit)?.toIntOrNull() ?: songs.size,
            thumbnailUrl = header["thumbnail"].bestThumbnail() ?: json["background"].bestThumbnail(),
        )
        return PlaylistPage(playlist, songs, shelf?.let(::continuationOf))
    }

    /** Devuelve (canciones, siguiente token). Soporta el formato antiguo y el de `appendContinuationItemsAction`. */
    fun playlistContinuation(json: JsonObject): Pair<List<Song>, String?> {
        val legacy = json.path("continuationContents", "musicPlaylistShelfContinuation")
        if (legacy != null) return playlistSongs(legacy["contents"]) to continuationOf(legacy)
        val items = json.findFirst("continuationItems")
        return playlistSongs(items) to items?.let(::continuationOf)
    }

    private fun playlistSongs(contents: JsonElement?): List<Song> =
        contents.array.mapNotNull { (listItem(it[LIST_ITEM]) as? MusicItem.SongItem)?.song }

    // ---- Cola / radio -------------------------------------------------------------------------

    fun queue(json: JsonObject): List<Song> = json.findAll("playlistPanelVideoRenderer").mapNotNull { r ->
        val id = r["videoId"].string ?: return@mapNotNull null
        val byline = r.path("longBylineText", "runs")
        Song(
            id = id,
            title = r.path("title", "runs").runsText() ?: return@mapNotNull null,
            artists = byline.artistRefs().ifEmpty {
                listOfNotNull(r.path("shortBylineText", "runs").runsText()?.let { ArtistRef(null, it) })
            },
            album = byline.albumRef(),
            durationSec = r.path("lengthText", "runs").runsText().toSeconds(),
            thumbnailUrl = r["thumbnail"].bestThumbnail(),
            explicit = r["badges"].isExplicit(),
        )
    }.distinctBy { it.id }

    fun lyrics(json: JsonObject): String? =
        json.findFirst("musicDescriptionShelfRenderer").path("description", "runs").runsText()

    // ---- Elementos ---------------------------------------------------------------------------

    private fun anyItem(wrapper: JsonElement?): MusicItem? =
        wrapper[TWO_ROW]?.let(::twoRowItem) ?: wrapper[LIST_ITEM]?.let(::listItem)

    /** Fila de lista: canción, o álbum/artista/playlist en resultados de búsqueda. */
    @Suppress("ReturnCount") // parser tolerante: cada dato ausente es una salida temprana
    fun listItem(r: JsonElement?): MusicItem? {
        if (r == null) return null
        val columns = r["flexColumns"].array.map { it.path("musicResponsiveListItemFlexColumnRenderer", "text", "runs") }
        val titleRun = columns.firstOrNull()[0]
        val title = columns.firstOrNull().runsText() ?: return null
        val detailRuns = columns.drop(1).flatMap { it.array }
        val thumb = r["thumbnail"].bestThumbnail()
        val browse = r.path("navigationEndpoint", "browseEndpoint")
        val detailText = detailRuns.mapNotNull { it["text"].string }

        when (browse.pageType()) {
            "MUSIC_PAGE_TYPE_ALBUM", "MUSIC_PAGE_TYPE_AUDIOBOOK" -> return MusicItem.AlbumItem(
                Album(
                    id = browse["browseId"].string ?: return null,
                    title = title,
                    artists = detailRuns.artistRefsOrLoose(detailText),
                    year = detailText.firstNotNullOfOrNull { it.takeIf(YEAR::matches)?.toInt() },
                    thumbnailUrl = thumb,
                    explicit = r["badges"].isExplicit(),
                ),
            )
            "MUSIC_PAGE_TYPE_ARTIST", "MUSIC_PAGE_TYPE_USER_CHANNEL" -> return MusicItem.ArtistItem(
                Artist(browse["browseId"].string ?: return null, title, thumb, detailText.lastOrNull { it.any(Char::isDigit) }),
            )
            "MUSIC_PAGE_TYPE_PLAYLIST" -> return MusicItem.PlaylistItem(
                Playlist(
                    id = browse["browseId"].string?.removePrefix("VL") ?: return null,
                    title = title,
                    author = looseSegments(detailText).firstOrNull(),
                    thumbnailUrl = thumb,
                ),
            )
        }

        val videoId = r.path("playlistItemData", "videoId").string
            ?: titleRun.path("navigationEndpoint", "watchEndpoint", "videoId").string
            ?: r["overlay"].findFirst("watchEndpoint")["videoId"].string
            ?: return null
        val fixed = r["fixedColumns"].array
            .mapNotNull { it.path("musicResponsiveListItemFixedColumnRenderer", "text", "runs").runsText() }
        return MusicItem.SongItem(
            Song(
                id = videoId,
                title = title,
                artists = detailRuns.artistRefsOrLoose(detailText),
                album = detailRuns.albumRef(),
                durationSec = (detailText + fixed).firstOrNull(DURATION::matches).toSeconds(),
                thumbnailUrl = thumb,
                explicit = r["badges"].isExplicit(),
            ),
        )
    }

    /** Tarjeta de dos filas de los carruseles. */
    fun twoRowItem(r: JsonElement?): MusicItem? {
        val title = r.path("title", "runs").runsText() ?: return null
        val nav = r["navigationEndpoint"]
        val subtitle = r.path("subtitle", "runs")
        val subText = subtitle.array.mapNotNull { it["text"].string }
        val thumb = r["thumbnailRenderer"].bestThumbnail()
        nav.path("watchEndpoint", "videoId").string?.let { id ->
            return MusicItem.SongItem(
                Song(id, title, subtitle.artistRefsOrLoose(subText), subtitle.albumRef(), thumbnailUrl = thumb, explicit = r["subtitleBadges"].isExplicit()),
            )
        }
        val browse = nav["browseEndpoint"]
        val id = browse["browseId"].string ?: return null
        return when (browse.pageType()) {
            "MUSIC_PAGE_TYPE_ALBUM", "MUSIC_PAGE_TYPE_AUDIOBOOK" -> MusicItem.AlbumItem(
                Album(
                    id, title, subtitle.artistRefsOrLoose(subText),
                    year = subText.firstNotNullOfOrNull { it.takeIf(YEAR::matches)?.toInt() },
                    thumbnailUrl = thumb, explicit = r["subtitleBadges"].isExplicit(),
                ),
            )
            "MUSIC_PAGE_TYPE_ARTIST", "MUSIC_PAGE_TYPE_USER_CHANNEL" ->
                MusicItem.ArtistItem(Artist(id, title, thumb, subText.firstOrNull { it.any(Char::isDigit) }))
            "MUSIC_PAGE_TYPE_PLAYLIST" ->
                MusicItem.PlaylistItem(Playlist(id.removePrefix("VL"), title, subText.joinToString("").takeIf { it.isNotBlank() }, thumbnailUrl = thumb))
            else -> null
        }
    }

    // ---- Utilidades ---------------------------------------------------------------------------

    private fun JsonElement?.runsText(): String? =
        array.mapNotNull { it["text"].string }.joinToString("").trim().takeIf { it.isNotEmpty() }

    private fun JsonElement?.pageType(): String? =
        path("browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType").string

    private fun JsonElement?.artistRefs(): List<ArtistRef> = array.artistRefs()

    private fun List<JsonElement>.artistRefs(): List<ArtistRef> = mapNotNull { run ->
        val browse = run.path("navigationEndpoint", "browseEndpoint")
        when (browse.pageType()) {
            "MUSIC_PAGE_TYPE_ARTIST", "MUSIC_PAGE_TYPE_USER_CHANNEL" -> ArtistRef(browse["browseId"].string, run["text"].string.orEmpty())
            else -> null
        }
    }

    private fun JsonElement?.artistRefsOrLoose(texts: List<String>): List<ArtistRef> = array.artistRefsOrLoose(texts)

    /** Si ningún run enlaza a un artista, usa el primer segmento de texto "suelto" como nombre. */
    private fun List<JsonElement>.artistRefsOrLoose(texts: List<String>): List<ArtistRef> =
        artistRefs().ifEmpty { looseSegments(texts).take(1).map { ArtistRef(null, it) } }

    private fun JsonElement?.albumRef(): AlbumRef? = array.albumRef()

    private fun List<JsonElement>.albumRef(): AlbumRef? = firstNotNullOfOrNull { run ->
        val browse = run.path("navigationEndpoint", "browseEndpoint")
        if (browse.pageType() == "MUSIC_PAGE_TYPE_ALBUM") AlbumRef(browse["browseId"].string ?: return@firstNotNullOfOrNull null, run["text"].string.orEmpty()) else null
    }

    /** Segmentos de un subtítulo que no son separadores, tipos ("Song"), años, duraciones ni conteos. */
    private fun looseSegments(texts: List<String>): List<String> = texts.joinToString("").split(" • ")
        .map { it.trim() }
        .filter { s ->
            s.isNotEmpty() && s.lowercase() !in TYPE_LABELS && !DURATION.matches(s) && !YEAR.matches(s) &&
                !s.first().isDigit()
        }

    private val TYPE_LABELS = setOf(
        "song", "video", "album", "single", "ep", "playlist", "artist", "episode", "podcast",
        "canción", "vídeo", "álbum", "sencillo", "artista", "lista de reproducción",
    )

    private fun JsonElement?.isExplicit(): Boolean =
        findAll("iconType").any { it.string == "MUSIC_EXPLICIT_BADGE" }

    /** La miniatura más grande, reescalada a 544 px cuando la URL admite parámetros de tamaño. */
    private fun JsonElement?.bestThumbnail(): String? {
        val url = findFirst("thumbnails").array.lastOrNull()["url"].string ?: return null
        return if (SIZE.containsMatchIn(url)) SIZE.replace(url, "=w544-h544") else url
    }

    private fun continuationOf(container: JsonElement): String? =
        container["continuations"][0].path("nextContinuationData", "continuation").string
            ?: container.findFirst("continuationItemRenderer").findFirst("continuationCommand")["token"].string

    /** "3:45" → 225, "1:02:03" → 3723. */
    internal fun String?.toSeconds(): Int = this?.split(':')?.fold(0) { acc, p -> acc * 60 + (p.toIntOrNull() ?: 0) } ?: 0
}
