package app.ondara.feature.search

import app.ondara.core.model.MusicItem
import app.ondara.core.model.SearchFilter

internal data class SearchResultRow(val index: Int = -1, val heading: String? = null)

/**
 * Agrupa solo la primera página, estable dentro de cada tipo; las demás se anexan en orden de llegada.
 * O(n) tiempo y espacio: cada índice se visita una vez y hay cuatro tipos constantes.
 */
internal fun resultRows(items: List<MusicItem>, firstPageSize: Int, filter: SearchFilter): List<SearchResultRow> {
    if (filter != SearchFilter.ALL) return items.indices.map { SearchResultRow(it) }
    val end = firstPageSize.coerceAtMost(items.size)
    val groups = (0 until end).groupBy { resultType(items[it]) }
    return buildList {
        listOf("Canciones", "Artistas", "Álbumes", "Playlists").forEach { title ->
            groups[title]?.let { indices ->
                add(SearchResultRow(heading = title))
                indices.forEach { add(SearchResultRow(it)) }
            }
        }
        for (index in end until items.size) add(SearchResultRow(index))
    }
}

private fun resultType(item: MusicItem): String = when (item) {
    is MusicItem.SongItem -> "Canciones"
    is MusicItem.ArtistItem -> "Artistas"
    is MusicItem.AlbumItem -> "Álbumes"
    is MusicItem.PlaylistItem -> "Playlists"
}
