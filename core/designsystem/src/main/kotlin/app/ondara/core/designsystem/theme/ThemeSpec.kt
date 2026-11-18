package app.ondara.core.designsystem.theme

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Descripción **data-driven** de un tema completo. Se carga desde `assets/themes/<id>.json`
 * (o desde los temas creados por el usuario) y gobierna colores, tipografía, formas, materiales,
 * movimiento, layout del reproductor, navegación y fondo: cambiar de tema cambia toda la interfaz.
 *
 * Este archivo es Kotlin puro (sin tipos de Compose) para poder validarlo en tests JVM.
 */
@Serializable
data class ThemeSpec(
    val id: String,
    val name: String,
    val description: String = "",
    val colors: ColorTokens,
    val typography: TypographyTokens = TypographyTokens(),
    val shapes: ShapeTokens = ShapeTokens(),
    val materials: MaterialTokens = MaterialTokens(),
    val motion: MotionTokens = MotionTokens(),
    val playerLayout: PlayerLayout = PlayerLayout.ARTWORK_HERO,
    val navigation: NavStyle = NavStyle.FLOATING_GLASS_PILL,
    val background: BackgroundStyle = BackgroundStyle.SOLID,
    val list: ListStyle = ListStyle.PLAIN,
    /** Títulos grandes que colapsan al hacer scroll (estilo Cupertino). */
    val largeTitles: Boolean = false,
    /** Los acentos laten con la amplitud del audio (tema Neón). */
    val accentPulse: Boolean = false,
    /** Intensidad 0..1 de la textura de grano (tema Vinilo). */
    val grain: Float = 0f,
    /** Familia de iconos de navegación y reproducción. */
    val icons: IconStyle = IconStyle.STROKE,
    /** Portadas con tramado de 1 bit a dos tonos (tema Tinta); la que suena vuelve a color. */
    val ditherArtwork: Boolean = false,
    /** `true` para los temas creados en el editor. */
    val custom: Boolean = false,
)

/** `BENTO` es el reproductor en bloques del tema Tinta; `CINEMATIC` ("Cine") pone el vídeo de la canción a pantalla completa. */
enum class PlayerLayout { ARTWORK_HERO, IMMERSIVE, MINIMAL, VINYL, BENTO, CINEMATIC }

enum class IconStyle { STROKE, HAND_DRAWN }

enum class NavStyle { FLOATING_GLASS_PILL, BOTTOM_BAR, SIDE_RAIL }

enum class BackgroundStyle { SOLID, MESH_GRADIENT, BLURRED_ART }

enum class ListStyle { PLAIN, SEPARATORS, CARDS }

enum class ColorMode { DARK, LIGHT, SYSTEM }

@Serializable
data class ColorTokens(
    val mode: ColorMode = ColorMode.DARK,
    /** Si es `true`, la paleta se deriva en tiempo real de la portada en reproducción. */
    val dynamic: Boolean = false,
    val dark: Palette,
    val light: Palette? = null,
)

/** Colores en hex `#RRGGBB` o `#AARRGGBB`. */
@Serializable
data class Palette(
    val background: String,
    val surface: String,
    val surfaceHigh: String,
    val onBackground: String,
    val onMuted: String,
    val accent: String,
    val onAccent: String,
    val outline: String,
    val error: String = "#FF5A5F",
    /** Bloques de las composiciones *bento* (perfil, reproductor Tinta). Si faltan se derivan de la paleta. */
    val blockPrimary: String? = null,
    val blockLight: String? = null,
    val blockMuted: String? = null,
    /** Texto sobre los bloques claros. */
    val onBlock: String? = null,
)

enum class FontToken { GROTESK, INTER, MONO, DOT, SERIF, WIDE, ROUNDED, NUNITO }

@Serializable
data class TypographyTokens(
    val display: FontToken = FontToken.GROTESK,
    val body: FontToken = FontToken.INTER,
    /** Tiempos, contadores y metadatos. */
    val mono: FontToken = FontToken.MONO,
    val displayWeight: Int = 700,
    val titleWeight: Int = 600,
    val bodyWeight: Int = 400,
    /** Multiplicador global del tamaño. */
    val scale: Float = 1f,
    /** Tracking de los titulares en em (negativo = más apretado). */
    val displayTracking: Float = -0.02f,
    val uppercaseLabels: Boolean = false,
)

@Serializable
data class ShapeTokens(
    /** Radios en dp. */
    val small: Int = 10,
    val medium: Int = 18,
    val large: Int = 28,
    val artwork: Int = 20,
    /** Esquinas suavizadas (superelipse) en vez de arcos circulares. */
    val squircle: Boolean = false,
    /** Botones y chips totalmente redondeados. */
    val pillControls: Boolean = true,
)

@Serializable
data class MaterialTokens(
    /** Radio de desenfoque de las capas de vidrio, en dp (0 = sin blur). */
    val blur: Int = 24,
    /** Opacidad del tinte sobre el vidrio, 0..1. */
    val surfaceOpacity: Float = 0.62f,
    /** Saturación aplicada al fondo desenfocado (1 = sin cambio). */
    val saturation: Float = 1.3f,
    /** Borde fino de 1 px en superficies. */
    val hairline: Boolean = true,
    /** Ruido sutil sobre el vidrio, 0..1. */
    val noise: Float = 0f,
)

@Serializable
data class MotionTokens(
    val stiffness: Float = 380f,
    val dampingRatio: Float = 0.82f,
    val fastMs: Int = 150,
    val mediumMs: Int = 300,
    val slowMs: Int = 500,
    /** Escala al presionar un elemento táctil. */
    val pressScale: Float = 0.96f,
    /** Retardo entre elementos en la entrada escalonada de listas. */
    val staggerMs: Int = 30,
)

object ThemeJson {
    val format: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
        isLenient = true
    }

    fun decode(text: String): ThemeSpec = format.decodeFromString(ThemeSpec.serializer(), text)

    fun encode(spec: ThemeSpec): String = format.encodeToString(ThemeSpec.serializer(), spec)
}
