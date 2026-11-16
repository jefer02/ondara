package app.ondara.core.designsystem.theme

import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import app.ondara.core.designsystem.R

/** Colores extraídos de la portada en reproducción ("la portada manda"). */
@Immutable
data class ArtworkPalette(val dominant: Color, val vibrant: Color, val muted: Color, val light: Color) {
    companion object {
        /** Paleta neutra derivada del propio tema cuando no hay portada. */
        fun from(colors: OndaraColors) = ArtworkPalette(colors.surfaceHigh, colors.accent, colors.surface, colors.onMuted)
    }
}

val LocalThemeSpec = staticCompositionLocalOf<ThemeSpec> { error("OndaraTheme no está en la composición") }
val LocalOndaraColors = staticCompositionLocalOf<OndaraColors> { error("OndaraTheme no está en la composición") }
val LocalOndaraTypography = staticCompositionLocalOf<OndaraTypography> { error("OndaraTheme no está en la composición") }
val LocalOndaraShapes = staticCompositionLocalOf<OndaraShapes> { error("OndaraTheme no está en la composición") }
val LocalOndaraMotion = staticCompositionLocalOf<OndaraMotion> { error("OndaraTheme no está en la composición") }
val LocalOndaraMaterials = staticCompositionLocalOf<OndaraMaterials> { error("OndaraTheme no está en la composición") }
val LocalArtworkPalette = staticCompositionLocalOf<ArtworkPalette?> { null }
val LocalContentColor = staticCompositionLocalOf { Color.White }
val LocalTextStyle = staticCompositionLocalOf { TextStyle.Default }

/** Amplitud del audio 0..1 como lambda (se lee en fase de dibujo, sin recomponer). */
val LocalAudioAmplitude = staticCompositionLocalOf<() -> Float> { { 0f } }

/** Punto de acceso a los tokens: `OndaraTheme.colors.accent`, `OndaraTheme.type.title`… */
object OndaraTheme {
    val spec: ThemeSpec @Composable @ReadOnlyComposable get() = LocalThemeSpec.current
    val colors: OndaraColors @Composable @ReadOnlyComposable get() = LocalOndaraColors.current
    val type: OndaraTypography @Composable @ReadOnlyComposable get() = LocalOndaraTypography.current
    val shapes: OndaraShapes @Composable @ReadOnlyComposable get() = LocalOndaraShapes.current
    val motion: OndaraMotion @Composable @ReadOnlyComposable get() = LocalOndaraMotion.current
    val materials: OndaraMaterials @Composable @ReadOnlyComposable get() = LocalOndaraMaterials.current
    val spacing: OndaraSpacing get() = OndaraSpacing

    /** Paleta de la portada actual, o una neutra del tema. */
    val artwork: ArtworkPalette
        @Composable @ReadOnlyComposable
        get() = LocalArtworkPalette.current ?: ArtworkPalette.from(LocalOndaraColors.current)
}

/** `true` si el usuario desactivó las animaciones del sistema (ajustes de accesibilidad). */
@Composable
fun rememberReduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/**
 * Aplica un [ThemeSpec] a todo el árbol.
 *
 * @param artwork paleta de la portada; si el tema es dinámico sus colores salen de aquí.
 * @param animateColors funde los colores al cambiar de tema o de canción (crossfade de tokens).
 */
@Composable
fun OndaraTheme(
    spec: ThemeSpec,
    artwork: ArtworkPalette? = null,
    reduceMotion: Boolean = rememberReduceMotion(),
    animateColors: Boolean = true,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val target = remember(spec.colors, artwork, systemDark) { resolveColors(spec.colors, artwork, systemDark) }
    val motion = remember(spec.motion, reduceMotion) { spec.motion.resolve(reduceMotion) }
    val colors = if (animateColors) animated(target, if (spec.colors.dynamic) 800 else motion.mediumMs) else target
    val typography = remember(spec.typography) { spec.typography.resolve() }
    val shapes = remember(spec.shapes) { spec.shapes.resolve() }
    val materials = remember(spec.materials) { spec.materials.resolve() }
    CompositionLocalProvider(
        LocalThemeSpec provides spec,
        LocalOndaraColors provides colors,
        LocalOndaraTypography provides typography,
        LocalOndaraShapes provides shapes,
        LocalOndaraMotion provides motion,
        LocalOndaraMaterials provides materials,
        LocalArtworkPalette provides artwork,
        LocalContentColor provides colors.onBackground,
        LocalTextStyle provides typography.body,
        content = content,
    )
}

internal fun resolveColors(tokens: ColorTokens, artwork: ArtworkPalette?, systemDark: Boolean): OndaraColors {
    val dark = when (tokens.mode) {
        ColorMode.DARK -> true
        ColorMode.LIGHT -> false
        ColorMode.SYSTEM -> systemDark
    }
    if (tokens.dynamic && artwork != null) {
        return ColorMath.paletteFrom(artwork.dominant.toArgb(), artwork.vibrant.toArgb(), dark).toColors()
    }
    return (if (dark) tokens.dark else tokens.light ?: tokens.dark).toColors()
}

@Composable
private fun animated(target: OndaraColors, durationMs: Int): OndaraColors {
    val spec = androidx.compose.animation.core.tween<Color>(durationMs)
    val background by animateColorAsState(target.background, spec, label = "background")
    val surface by animateColorAsState(target.surface, spec, label = "surface")
    val surfaceHigh by animateColorAsState(target.surfaceHigh, spec, label = "surfaceHigh")
    val onBackground by animateColorAsState(target.onBackground, spec, label = "onBackground")
    val onMuted by animateColorAsState(target.onMuted, spec, label = "onMuted")
    val accent by animateColorAsState(target.accent, spec, label = "accent")
    val onAccent by animateColorAsState(target.onAccent, spec, label = "onAccent")
    val outline by animateColorAsState(target.outline, spec, label = "outline")
    val error by animateColorAsState(target.error, spec, label = "error")
    return OndaraColors(background, surface, surfaceHigh, onBackground, onMuted, accent, onAccent, outline, error, target.isDark)
}

private fun variable(resId: Int): FontFamily = FontFamily(
    listOf(300, 400, 500, 600, 700, 800, 900).map { w ->
        Font(resId, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    },
)

private val Grotesk by lazy { variable(R.font.space_grotesk) }
private val Inter by lazy { variable(R.font.inter) }
private val Mono by lazy { variable(R.font.jetbrains_mono) }
private val Dot by lazy { variable(R.font.doto) }
private val Serif by lazy { variable(R.font.fraunces) }
private val Wide by lazy { variable(R.font.unbounded) }

/** Familias tipográficas incluidas (todas OFL, variables). */
fun fontFamily(token: FontToken): FontFamily = when (token) {
    FontToken.GROTESK -> Grotesk
    FontToken.INTER -> Inter
    FontToken.MONO -> Mono
    FontToken.DOT -> Dot
    FontToken.SERIF -> Serif
    FontToken.WIDE -> Wide
}
