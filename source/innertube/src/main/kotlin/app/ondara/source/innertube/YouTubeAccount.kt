package app.ondara.source.innertube

/** Cuenta de YouTube con sesión iniciada, tal como la muestra el menú de cuenta de YouTube Music. */
data class YouTubeAccountInfo(val name: String, val handle: String?, val avatarUrl: String?)

/** Valoración de un vídeo: [LIKE] marca el "Me gusta", [INDIFFERENT] lo quita. */
enum class LikeStatus { LIKE, INDIFFERENT }
