package app.ondara.core.designsystem.component

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.ondara.core.designsystem.theme.LocalContentColor
import app.ondara.core.designsystem.theme.LocalTextStyle
import app.ondara.core.designsystem.theme.OndaraTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import kotlin.math.min

/** Texto de Ondara: toma estilo y color de los tokens del tema, nunca valores sueltos. */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    textAlign: TextAlign? = null,
) {
    val resolved = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }
    BasicText(
        text = text,
        modifier = modifier,
        style = style.merge(TextStyle(color = resolved, textAlign = textAlign ?: TextAlign.Unspecified)),
        maxLines = maxLines,
        overflow = overflow,
    )
}

/** Etiqueta pequeña; va en mayúsculas en los temas que lo piden (Mono, Neón). */
@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = OndaraTheme.colors.onMuted, maxLines: Int = 1) {
    val type = OndaraTheme.type
    Text(if (type.uppercaseLabels) text.uppercase() else text, modifier, type.label, color, maxLines)
}

/** Si es `false` no se emite ninguna vibración (ajuste del usuario). */
val LocalHapticsEnabled = staticCompositionLocalOf { true }

/** Hápticos sutiles de Ondara. */
class Haptics internal constructor(private val view: View, private val enabled: Boolean) {
    /** Toque ligero: play/pausa, saltos, conmutadores. */
    fun tick() = perform(HapticFeedbackConstants.CLOCK_TICK)

    /** Confirmación: pulsación larga, soltar un elemento arrastrado. */
    fun confirm() = perform(HapticFeedbackConstants.LONG_PRESS)

    /** Tope de un deslizamiento o de un gesto. */
    fun edge() = perform(HapticFeedbackConstants.VIRTUAL_KEY)

    private fun perform(constant: Int) {
        if (enabled) view.performHapticFeedback(constant)
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    val enabled = LocalHapticsEnabled.current
    return remember(view, enabled) { Haptics(view, enabled) }
}

/**
 * Interacción táctil de Ondara: en lugar de *ripple*, el elemento se encoge con un spring
 * (`motion.pressScale`) y vuelve con un pequeño rebote.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.pressable(
    enabled: Boolean = true,
    haptic: Boolean = false,
    role: Role? = Role.Button,
    onClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val motion = OndaraTheme.motion
    val haptics = rememberHaptics()
    val scale by animateFloatAsState(if (pressed) motion.pressScale else 1f, motion.bouncy(0.55f, 700f), label = "press")
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .combinedClickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            role = role,
            onClickLabel = onClickLabel,
            onLongClick = onLongClick?.let { long ->
                {
                    haptics.confirm()
                    long()
                }
            },
            onClick = {
                if (haptic) haptics.tick()
                onClick()
            },
        )
}

/** Superficie opaca del tema, con borde fino opcional (nunca sombras grises). */
@Composable
fun Surface(
    modifier: Modifier = Modifier,
    shape: Shape = OndaraTheme.shapes.medium,
    color: Color = OndaraTheme.colors.surface,
    contentColor: Color = OndaraTheme.colors.onBackground,
    hairline: Boolean = OndaraTheme.materials.hairline,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    val outline = OndaraTheme.colors.outline
    Box(
        modifier
            .clip(shape)
            .background(color)
            .then(if (hairline) Modifier.border(1.dp, outline.copy(alpha = 0.6f), shape) else Modifier),
        contentAlignment = contentAlignment,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
    }
}

/** Fuente de fondo para el vidrio; la provee la raíz de la app sobre el contenido desplazable. */
val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }

/**
 * Material translúcido: desenfoca lo que hay detrás (API 31+) y lo tiñe con el color de superficie.
 * El radio, la opacidad y el ruido salen de `OndaraMaterials`; con `blur = 0` (tema Mono) es opaco.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = OndaraTheme.shapes.large,
    tint: Color = OndaraTheme.colors.surface,
    contentColor: Color = OndaraTheme.colors.onBackground,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    val materials = OndaraTheme.materials
    val colors = OndaraTheme.colors
    val haze = LocalHazeState.current
    val glass = if (haze != null && materials.blur > 0.dp) {
        Modifier.hazeEffect(
            state = haze,
            style = HazeStyle(
                backgroundColor = colors.background,
                tints = listOf(HazeTint(tint.copy(alpha = materials.surfaceOpacity))),
                blurRadius = materials.blur,
                noiseFactor = materials.noise * 0.3f,
                fallbackTint = HazeTint(tint.copy(alpha = (materials.surfaceOpacity + 0.3f).coerceAtMost(0.97f))),
            ),
        )
    } else {
        Modifier.background(tint.copy(alpha = if (materials.blur > 0.dp) 0.94f else 1f))
    }
    Box(
        modifier
            .clip(shape)
            .then(glass)
            .then(if (materials.hairline) Modifier.border(1.dp, colors.outline.copy(alpha = 0.5f), shape) else Modifier),
        contentAlignment = contentAlignment,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
    }
}

/**
 * Esquina "squircle": curvatura continua (aprox. superelipse) como la de iOS, en vez del arco
 * circular de `RoundedCornerShape`. Cada esquina son dos cúbicas de Bézier.
 */
class SquircleShape(private val radius: Dp, private val smoothing: Float = 0.6f) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = min(with(density) { radius.toPx() }, min(size.width, size.height) / 2f)
        if (r <= 0f) return Outline.Rectangle(androidx.compose.ui.geometry.Rect(Offset.Zero, size))
        // La curva empieza antes que un arco normal: se extiende (1 + smoothing)·r por cada lado.
        val extent = min(r * (1f + smoothing), min(size.width, size.height) / 2f)
        val k = extent * 0.36f // distancia del punto de control al vértice
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(extent, 0f)
            lineTo(w - extent, 0f)
            cubicTo(w - k, 0f, w, k, w, extent)
            lineTo(w, h - extent)
            cubicTo(w, h - k, w - k, h, w - extent, h)
            lineTo(extent, h)
            cubicTo(k, h, 0f, h - k, 0f, h - extent)
            lineTo(0f, extent)
            cubicTo(0f, k, k, 0f, extent, 0f)
            close()
        }
        return Outline.Generic(path)
    }

    override fun equals(other: Any?) = other is SquircleShape && other.radius == radius && other.smoothing == smoothing

    override fun hashCode() = radius.hashCode() * 31 + smoothing.hashCode()
}
