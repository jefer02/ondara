package app.ondara.core.designsystem.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.ondara.core.designsystem.component.SquircleShape

/** Colores resueltos del tema activo. */
@Immutable
data class OndaraColors(
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val onBackground: Color,
    val onMuted: Color,
    val accent: Color,
    val onAccent: Color,
    val outline: Color,
    val error: Color,
    val isDark: Boolean,
) {
    /** Velo para superponer sobre imágenes y que el texto siga siendo legible. */
    val scrim: Color get() = background.copy(alpha = 0.72f)
}

fun Palette.toColors(): OndaraColors {
    fun c(hex: String) = Color(ColorMath.parseHex(hex))
    return OndaraColors(
        background = c(background), surface = c(surface), surfaceHigh = c(surfaceHigh),
        onBackground = c(onBackground), onMuted = c(onMuted), accent = c(accent), onAccent = c(onAccent),
        outline = c(outline), error = c(error),
        isDark = ColorMath.luminance(ColorMath.parseHex(background)) < 0.4,
    )
}

/** Escala tipográfica. Los estilos `mono*` son para tiempos, contadores y metadatos. */
@Immutable
data class OndaraTypography(
    val hero: TextStyle,
    val display: TextStyle,
    val title: TextStyle,
    val headline: TextStyle,
    val body: TextStyle,
    val bodyStrong: TextStyle,
    val caption: TextStyle,
    val label: TextStyle,
    val mono: TextStyle,
    val monoLarge: TextStyle,
    val lyric: TextStyle,
    val uppercaseLabels: Boolean,
)

internal fun TypographyTokens.resolve(): OndaraTypography {
    val displayFamily = fontFamily(display)
    val bodyFamily = fontFamily(body)
    val monoFamily = fontFamily(mono)
    fun style(family: FontFamily, size: Float, line: Float, weight: Int, tracking: Float = 0f) = TextStyle(
        fontFamily = family,
        fontSize = (size * scale).sp,
        lineHeight = (line * scale).sp,
        fontWeight = FontWeight(weight.coerceIn(100, 900)),
        letterSpacing = tracking.em,
    )
    // La fuente de matriz de puntos y la ancha ocupan más: se compensan para que la jerarquía no reviente líneas.
    val wide = if (display == FontToken.WIDE || display == FontToken.DOT) 0.84f else 1f
    return OndaraTypography(
        hero = style(displayFamily, 40 * wide, 44 * wide, displayWeight, displayTracking),
        display = style(displayFamily, 30 * wide, 35 * wide, displayWeight, displayTracking),
        title = style(displayFamily, 21 * wide, 27 * wide, titleWeight, displayTracking / 2),
        headline = style(bodyFamily, 17f, 23f, titleWeight),
        body = style(bodyFamily, 15f, 21f, bodyWeight),
        bodyStrong = style(bodyFamily, 15f, 21f, (bodyWeight + 200).coerceAtMost(700)),
        caption = style(bodyFamily, 13f, 17f, bodyWeight),
        label = style(bodyFamily, 12f, 15f, 600, if (uppercaseLabels) 0.08f else 0.01f),
        mono = style(monoFamily, if (mono == FontToken.DOT) 14f else 12f, 16f, if (mono == FontToken.DOT) 800 else 500, 0.02f),
        monoLarge = style(monoFamily, if (mono == FontToken.DOT) 30f else 24f, 32f, if (mono == FontToken.DOT) 900 else 500),
        lyric = style(displayFamily, 26 * wide, 34 * wide, displayWeight, displayTracking),
        uppercaseLabels = uppercaseLabels,
    )
}

@Immutable
data class OndaraShapes(
    val small: Shape,
    val medium: Shape,
    val large: Shape,
    val artwork: Shape,
    /** Botones, chips y campos. */
    val control: Shape,
    val artworkRadius: Dp,
    val mediumRadius: Dp,
)

internal fun ShapeTokens.resolve(): OndaraShapes {
    fun shape(radius: Int): Shape = if (squircle) SquircleShape(radius.dp) else RoundedCornerShape(radius.dp)
    return OndaraShapes(
        small = shape(small), medium = shape(medium), large = shape(large), artwork = shape(artwork),
        control = if (pillControls) CircleShape else shape(small),
        artworkRadius = artwork.dp, mediumRadius = medium.dp,
    )
}

/** Espaciado en rejilla de 4 dp; es constante entre temas para que los layouts no salten. */
@Immutable
object OndaraSpacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 28.dp
    val huge = 40.dp

    /** Margen horizontal de pantalla. */
    val screen = 20.dp

    /** Alto mínimo de un objetivo táctil (accesibilidad). */
    val touch = 48.dp
}

@Immutable
data class OndaraMaterials(
    val blur: Dp,
    val surfaceOpacity: Float,
    val saturation: Float,
    val hairline: Boolean,
    val noise: Float,
)

/** Curvas y duraciones del tema. Con "reducir animaciones" los springs pasan a fundidos cortos. */
@Immutable
data class OndaraMotion(
    val stiffness: Float,
    val dampingRatio: Float,
    val fastMs: Int,
    val mediumMs: Int,
    val slowMs: Int,
    val pressScale: Float,
    val staggerMs: Int,
    val reduced: Boolean,
) {
    /** Spring principal del tema. */
    fun <T> spring(visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
        if (reduced) tween(REDUCED_MS) else spring(dampingRatio, stiffness, visibilityThreshold)

    /** Spring con rebote propio (p. ej. la portada que respira usa 0.7). */
    fun <T> bouncy(dampingRatio: Float = 0.7f, stiffness: Float = this.stiffness): FiniteAnimationSpec<T> =
        if (reduced) tween(REDUCED_MS) else spring(dampingRatio, stiffness)

    /** Spring suave para transiciones grandes (reveal de tema, hojas). */
    fun <T> gentle(): FiniteAnimationSpec<T> = if (reduced) tween(REDUCED_MS) else spring(1f, stiffness * 0.35f)

    fun <T> fade(durationMs: Int = mediumMs): FiniteAnimationSpec<T> = tween(if (reduced) REDUCED_MS.coerceAtMost(durationMs) else durationMs)

    companion object {
        const val REDUCED_MS = 150
    }
}

internal fun MotionTokens.resolve(reduced: Boolean) =
    OndaraMotion(stiffness, dampingRatio, fastMs, mediumMs, slowMs, pressScale, staggerMs, reduced)

internal fun MaterialTokens.resolve() = OndaraMaterials(blur.dp, surfaceOpacity, saturation, hairline, noise)
