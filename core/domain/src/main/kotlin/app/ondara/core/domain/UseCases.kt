package app.ondara.core.domain

import app.ondara.core.model.Song
import javax.inject.Inject

/**
 * "Ondara Flow": cola automática que mezcla relacionadas del endpoint `next` con el historial.
 *
 * Toma como semilla la canción actual; si no hay, la más escuchada. Las candidatas se filtran
 * contra [exclude] (lo que ya está en cola) con un `HashSet`.
 *
 * Complejidad: O(r + e) tiempo y O(e) espacio, con r relacionadas y e excluidas.
 */
class BuildFlowQueueUseCase @Inject constructor(
    private val catalog: CatalogRepository,
    private val library: LibraryRepository,
) {
    suspend operator fun invoke(seed: Song?, exclude: Collection<String>, limit: Int = 25): List<Song> {
        val base = seed ?: library.mostPlayed(1).firstOrNull() ?: return emptyList()
        val seen = HashSet(exclude).apply { add(base.id) }
        val related = runCatching { catalog.radio(base.id) }.getOrDefault(emptyList())
        val out = ArrayList<Song>(limit)
        for (song in related) {
            if (out.size >= limit) break
            if (seen.add(song.id)) out += song
        }
        if (out.size < limit / 2) {
            // Sin red o radio corta: completa con lo que el usuario más escucha.
            for (song in library.mostPlayed(limit)) {
                if (out.size >= limit) break
                if (seen.add(song.id)) out += song
            }
        }
        return out
    }
}

/** Parser mínimo de CSV (RFC 4180: comillas dobles, comas y saltos de línea dentro de campos). */
object CsvParser {
    /** Complejidad: O(n) tiempo y espacio sobre la longitud del texto. */
    fun parse(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> { row += field.toString(); field.setLength(0) }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                    row += field.toString(); field.setLength(0)
                    if (row.any { it.isNotBlank() }) rows += row
                    row = ArrayList()
                }
                else -> field.append(c)
            }
            i++
        }
        row += field.toString()
        if (row.any { it.isNotBlank() }) rows += row
        return rows
    }

    data class Track(val title: String, val artist: String)

    /**
     * Extrae (título, artista) de un CSV. Reconoce las cabeceras de Exportify / Spotify
     * ("Track Name", "Artist Name(s)") y variantes en español; sin cabecera asume `título,artista`.
     */
    fun tracks(text: String): List<Track> {
        val rows = parse(text.removePrefix("﻿"))
        if (rows.isEmpty()) return emptyList()
        val header = rows.first().map { it.trim().lowercase() }
        val titleCol = header.indexOfFirst { it in TITLE_HEADERS }
        val artistCol = header.indexOfFirst { it in ARTIST_HEADERS }
        val hasHeader = titleCol >= 0
        val t = if (hasHeader) titleCol else 0
        val a = if (hasHeader) artistCol else 1
        return rows.drop(if (hasHeader) 1 else 0).mapNotNull { r ->
            val title = r.getOrNull(t)?.trim().orEmpty()
            if (title.isEmpty()) null else Track(title, r.getOrNull(a)?.trim().orEmpty().substringBefore(';'))
        }
    }

    private val TITLE_HEADERS = setOf("track name", "title", "name", "track", "song", "título", "titulo", "canción", "cancion")
    private val ARTIST_HEADERS = setOf("artist name(s)", "artist name", "artist", "artists", "artista", "artistas")
}
