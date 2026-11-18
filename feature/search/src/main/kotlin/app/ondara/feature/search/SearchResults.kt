package app.ondara.feature.search

import app.ondara.core.domain.SongEquivalenceIndex
import app.ondara.core.domain.isMusicVideo
import app.ondara.core.model.MusicItem
import app.ondara.core.model.MusicVideoType
import app.ondara.core.model.SearchFilter
import app.ondara.core.model.Song

internal data class SearchResultRow(val index: Int = -1, val heading: String? = null, val item: MusicItem? = null) {
    val key: String get() = heading?.let { "heading-$it" } ?: "${item?.key?.replace(':', '_')}_$index"
}

internal data class SearchResults(val rows: List<SearchResultRow>, val equivalents: Map<String, Song>)

/**
 * Ordena el snapshot completo y calcula vídeo → canción en O(n + l) tiempo y espacio (l caracteres).
 * Las consultas al índice de duración tienen coste constante; no hay búsquedas anidadas por candidato.
 * Los índices originales identifican duplicados y sirven solo para solicitar precarga, nunca para pintar.
 */
internal fun searchResults(items: List<MusicItem>, filter: SearchFilter): SearchResults {
    val index = SongEquivalenceIndex(items)
    val equivalents = mutableMapOf<String, Song>()
    val groups = linkedMapOf<String, MutableList<SearchResultRow>>()
    var firstSong: SearchResultRow? = null
    var equivalentTop: SearchResultRow? = null
    val first = (items.firstOrNull() as? MusicItem.SongItem)?.song
    val preferred = first?.let(index::equivalent)
    val rows = items.mapIndexed { position, item ->
        val row = SearchResultRow(position, item = item)
        val song = (item as? MusicItem.SongItem)?.song
        if (song != null) {
            index.equivalent(song)?.let { equivalents[song.id] = it }
            if (song.videoType == MusicVideoType.SONG && firstSong == null) firstSong = row
            if (song == preferred && equivalentTop == null) equivalentTop = row
        }
        groups.getOrPut(resultType(item)) { mutableListOf() }.add(row)
        row
    }
    if (filter != SearchFilter.ALL) {
        val ordered = if (filter == SearchFilter.SONGS) rows.filter { (it.item as? MusicItem.SongItem)?.song?.isMusicVideo() != true } +
            rows.filter { (it.item as? MusicItem.SongItem)?.song?.isMusicVideo() == true } else rows
        return SearchResults(ordered, equivalents.toMap())
    }
    val top = equivalentTop ?: firstSong ?: rows.firstOrNull()
    return SearchResults(buildList {
        if (top != null) {
            add(SearchResultRow(heading = "Resultado principal"))
            add(top)
        }
        for (title in listOf("Canciones", "Álbumes", "Artistas", "Playlists", "Vídeos")) {
            val group = groups[title].orEmpty().filter { it.index != top?.index }
            if (group.isNotEmpty()) {
                add(SearchResultRow(heading = title))
                addAll(group)
            }
        }
    }, equivalents.toMap())
}

/** Clasifica un elemento en tiempo y espacio O(1). */
private fun resultType(item: MusicItem): String = when (item) {
    is MusicItem.SongItem -> if (item.song.isMusicVideo()) "Vídeos" else "Canciones"
    is MusicItem.ArtistItem -> "Artistas"
    is MusicItem.AlbumItem -> "Álbumes"
    is MusicItem.PlaylistItem -> "Playlists"
}
