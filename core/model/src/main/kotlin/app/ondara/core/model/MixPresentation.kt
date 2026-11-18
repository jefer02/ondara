package app.ondara.core.model

/** Cuenta y ordena artistas por presencia: O(n + a log a) tiempo y O(a) espacio. */
fun mixSubtitle(songs: List<Song>): String {
    val artists = songs.flatMap { it.artists }.filter { it.name.isNotBlank() }
        .groupingBy { it.name }.eachCount().entries.sortedByDescending { it.value }
    val names = artists.take(3).joinToString(", ") { it.key }
    val count = if (songs.size == 1) "1 canción" else "${songs.size} canciones"
    return count + if (names.isEmpty()) "" else " · $names" + if (artists.size > 3) "…" else ""
}

/** Evita repetir el título como motivo; O(n) tiempo y espacio sobre las etiquetas. */
fun distinctPickLabel(label: String, title: String): String =
    label.trim().takeUnless { it.isEmpty() || it.equals(title.trim(), ignoreCase = true) } ?: "Elegido para ti"
