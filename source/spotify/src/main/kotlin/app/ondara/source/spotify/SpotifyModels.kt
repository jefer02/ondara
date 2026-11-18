package app.ondara.source.spotify

/** Perfil del usuario que inició sesión. */
data class SpotifyUser(val id: String, val displayName: String, val imageUrl: String?)

/**
 * Playlist propia o seguida. [trackCount] es 0 y [snapshotId] es null cuando Spotify no los informa
 * (ocurre en el modo experimental, cuya API de biblioteca no los incluye).
 */
data class SpotifyPlaylist(
    val id: String,
    val name: String,
    val owner: String?,
    val imageUrl: String?,
    val trackCount: Int,
    val snapshotId: String?,
)

/** Canción de Spotify; [artists] va en el orden de Spotify, con el artista principal primero. */
data class SpotifyTrack(
    val id: String,
    val title: String,
    val artists: List<String>,
    val album: String?,
    val durationMs: Long,
    val imageUrl: String?,
)

data class SpotifyAlbum(val id: String, val title: String, val artists: List<String>, val imageUrl: String?, val year: Int?)
