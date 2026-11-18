package app.ondara.core.model

import kotlinx.serialization.Serializable

/** Tarjeta grande de "Top Picks": un elemento con la razón por la que se recomienda. */
@Serializable
data class TopPick(val label: String, val item: MusicItem)

/**
 * Secciones personales del Inicio, calculadas a partir del historial local y de las cuentas conectadas.
 * Los mixes son playlists con id `mix_*` (ver [MixIds]); su portada la genera Ondara.
 */
@Serializable
data class PersonalHome(
    val topPicks: List<TopPick> = emptyList(),
    val mixes: List<Playlist> = emptyList(),
    val releases: List<MusicItem> = emptyList(),
    val stations: List<Artist> = emptyList(),
    val replay: List<Song> = emptyList(),
    val replayYear: Int = 0,
)

/** Ids reservados de playlists virtuales (no viven en Room ni en YouTube). */
object MixIds {
    const val PREFIX = "mix_"
    const val FAVORITES_MIX = "mix_favoritos"
    const val NEW = "mix_novedades"
    const val CALM = "mix_calma"
    const val DISCOVER = "mix_descubrir"
    const val REPLAY = "mix_replay"

    /** "Tus favoritas": la lista de favoritos expuesta como playlist. */
    const val FAVORITES = "favorites"

    /** Prefijo de una estación de artista: `station_<browseId>`. */
    const val STATION_PREFIX = "station_"

    fun isVirtual(id: String) = id.startsWith(PREFIX) || id == FAVORITES || id.startsWith(STATION_PREFIX)
}

/** Estado de ánimo dominante de lo escuchado; elige la carita del perfil. */
enum class Mood { HAPPY, CALM, ENERGETIC, MELANCHOLY, NEUTRAL }

data class ListeningStats(
    val minutesThisMonth: Int = 0,
    val topGenre: String? = null,
    val topArtist: Artist? = null,
    val mood: Mood = Mood.NEUTRAL,
    val songOfTheDay: Song? = null,
    /** Portadas más escuchadas (de ellas sale "Tu paleta"). */
    val topArtworks: List<String> = emptyList(),
)

/** Elemento guardado en la biblioteca (álbum o artista seguido) con su fecha. */
data class SavedItem(val item: MusicItem, val addedAtMs: Long)

@Serializable
data class YouTubeAccount(val id: String, val name: String, val handle: String? = null, val avatarUrl: String? = null)

@Serializable
data class SpotifyAccount(val id: String, val name: String, val avatarUrl: String? = null, val experimental: Boolean = false)

data class AccountsState(
    val youtube: List<YouTubeAccount> = emptyList(),
    val activeYouTubeId: String? = null,
    val spotify: SpotifyAccount? = null,
) {
    val activeYouTube: YouTubeAccount? get() = youtube.firstOrNull { it.id == activeYouTubeId }
    val anyConnected: Boolean get() = youtube.isNotEmpty() || spotify != null
}

/** Progreso de una importación/sincronización de cuentas ("Importando 120 de 340 canciones…"). */
data class SyncProgress(
    val running: Boolean = false,
    val message: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val error: String? = null,
) {
    val fraction: Float get() = if (total > 0) done.toFloat() / total else 0f
}

/** Canción de Spotify para la que no se encontró equivalente fiable en YouTube Music. */
data class UnmatchedTrack(val spotifyId: String, val title: String, val artist: String, val durationMs: Long)
