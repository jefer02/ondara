package app.ondara.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.ondara.core.designsystem.icon.Icon
import app.ondara.core.designsystem.theme.LocalContentColor
import app.ondara.core.designsystem.theme.LocalTextStyle
import app.ondara.core.designsystem.theme.OndaraTheme

enum class ButtonStyle { FILLED, TONAL, GHOST }

/** Botón de Ondara. La forma (píldora o esquina del tema) sale de `shapes.control`. */
@Composable
fun OndaraButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.FILLED,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val colors = OndaraTheme.colors
    val shape = OndaraTheme.shapes.control
    val (container, content) = when (style) {
        ButtonStyle.FILLED -> colors.accent to colors.onAccent
        ButtonStyle.TONAL -> colors.surfaceHigh to colors.onBackground
        ButtonStyle.GHOST -> Color.Transparent to colors.accent
    }
    Row(
        modifier
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .pressable(enabled = enabled, haptic = true, onClick = onClick)
            .defaultMinSize(minHeight = OndaraTheme.spacing.touch)
            .clip(shape)
            .background(container)
            .then(if (style == ButtonStyle.GHOST) Modifier.border(1.dp, colors.outline, shape) else Modifier)
            .padding(horizontal = OndaraTheme.spacing.xl, vertical = OndaraTheme.spacing.md),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = content, size = 18.dp)
            Spacer(Modifier.width(OndaraTheme.spacing.sm))
        }
        val type = OndaraTheme.type
        Text(if (type.uppercaseLabels) text.uppercase() else text, style = type.bodyStrong, color = content, maxLines = 1)
    }
}

/** Botón de icono con objetivo táctil de 48 dp. [contentDescription] es obligatorio (TalkBack). */
@Composable
fun OndaraIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = 24.dp,
    container: Color = Color.Transparent,
    enabled: Boolean = true,
    haptic: Boolean = false,
) {
    Box(
        modifier
            .pressable(enabled = enabled, haptic = haptic, onClick = onClick)
            .size(maxOf(OndaraTheme.spacing.touch, size + 20.dp))
            .clip(CircleShape)
            .background(container)
            .graphicsLayer { alpha = if (enabled) 1f else 0.35f },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = tint, size = size)
    }
}

/** Chip de filtro/selección. */
@Composable
fun OndaraChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val colors = OndaraTheme.colors
    val motion = OndaraTheme.motion
    val container by animateColorAsState(if (selected) colors.accent else colors.surfaceHigh, motion.fade(motion.fastMs), label = "chip")
    val content by animateColorAsState(if (selected) colors.onAccent else colors.onBackground, motion.fade(motion.fastMs), label = "chipContent")
    Row(
        modifier
            .pressable(haptic = true, role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected }
            .defaultMinSize(minHeight = 36.dp)
            .clip(OndaraTheme.shapes.control)
            .background(container)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = content, size = 16.dp)
            Spacer(Modifier.width(6.dp))
        }
        val type = OndaraTheme.type
        Text(if (type.uppercaseLabels) text.uppercase() else text, style = type.label, color = content, maxLines = 1)
    }
}

/** Conmutador propio: el pulgar viaja con el spring del tema. */
@Composable
fun OndaraSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = OndaraTheme.colors
    val motion = OndaraTheme.motion
    val progress by animateFloatAsState(if (checked) 1f else 0f, motion.spring(), label = "switch")
    val track by animateColorAsState(if (checked) colors.accent else colors.surfaceHigh, motion.fade(motion.fastMs), label = "track")
    val thumb = if (checked) colors.onAccent else colors.onMuted
    val shape = OndaraTheme.shapes.control
    Box(
        modifier
            .pressable(enabled = enabled, haptic = true, role = Role.Switch) { onCheckedChange(!checked) }
            .semantics { toggleableState = if (checked) ToggleableState.On else ToggleableState.Off }
            .size(width = 52.dp, height = 32.dp)
            .clip(shape)
            .background(track)
            .border(1.dp, colors.outline.copy(alpha = if (checked) 0f else 1f), shape)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f },
    ) {
        Box(
            Modifier
                .padding(4.dp)
                .graphicsLayer { translationX = progress * 20.dp.toPx() }
                .size(24.dp)
                .clip(shape)
                .background(thumb),
        )
    }
}

/**
 * Barra de progreso arrastrable (seek). Se dibuja a mano: pista fina que engorda al tocarla.
 *
 * @param value progreso 0..1 que llega del reproductor.
 * @param onSeek se llama **al soltar**, con el valor final; mientras se arrastra solo se informa por [onScrub].
 */
@Composable
fun OndaraSlider(
    value: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onScrub: (Float?) -> Unit = {},
    activeColor: Color = OndaraTheme.colors.accent,
    trackColor: Color = OndaraTheme.colors.onBackground.copy(alpha = 0.18f),
    description: String = "",
    buffered: Float = 0f,
) {
    val motion = OndaraTheme.motion
    val haptics = rememberHaptics()
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val shown = if (dragging) dragValue else value.coerceIn(0f, 1f)
    val thickness by animateFloatAsState(if (dragging) 1f else 0f, motion.bouncy(), label = "sliderThickness")
    val currentSeek by rememberUpdatedState(onSeek)
    val currentScrub by rememberUpdatedState(onScrub)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(OndaraTheme.spacing.touch)
            .semantics {
                if (description.isNotEmpty()) contentDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(shown, 0f..1f)
                stateDescription = "${(shown * 100).toInt()} %"
                setProgress { target ->
                    currentSeek(target.coerceIn(0f, 1f))
                    true
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset -> currentSeek((offset.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        dragValue = (offset.x / size.width).coerceIn(0f, 1f)
                        currentScrub(dragValue)
                    },
                    onDragEnd = {
                        dragging = false
                        currentScrub(null)
                        currentSeek(dragValue)
                    },
                    onDragCancel = {
                        dragging = false
                        currentScrub(null)
                    },
                ) { change, amount ->
                    change.consume()
                    val next = (dragValue + amount / size.width).coerceIn(0f, 1f)
                    if ((next == 0f || next == 1f) && next != dragValue) haptics.edge()
                    dragValue = next
                    currentScrub(next)
                }
            },
    ) {
        val h = (4.dp.toPx() + 6.dp.toPx() * thickness)
        val y = (size.height - h) / 2
        val radius = CornerRadius(h / 2)
        drawRoundRect(trackColor, Offset(0f, y), Size(size.width, h), radius)
        if (buffered > 0f) drawRoundRect(trackColor, Offset(0f, y), Size(size.width * buffered.coerceIn(0f, 1f), h), radius)
        drawRoundRect(activeColor, Offset(0f, y), Size(size.width * shown, h), radius)
        val thumbR = 6.dp.toPx() * (1f - thickness * 0.4f) + 3.dp.toPx() * thickness
        drawCircle(activeColor, thumbR, Offset((size.width * shown).coerceIn(thumbR, size.width - thumbR), size.height / 2))
    }
}

/** Deslizador de valores discretos/continuos para ajustes (crossfade, radio de esquina…). */
@Composable
fun OndaraValueSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    description: String = "",
) {
    val span = range.endInclusive - range.start
    fun snap(fraction: Float): Float {
        val raw = range.start + fraction * span
        if (steps <= 0) return raw
        val step = span / steps
        return range.start + Math.round((raw - range.start) / step) * step
    }
    var scrub by remember { mutableStateOf<Float?>(null) }
    val current by rememberUpdatedState(onValueChange)
    OndaraSlider(
        value = ((scrub?.let(::snap) ?: value) - range.start) / span,
        onSeek = { current(snap(it)) },
        onScrub = { fraction ->
            scrub = fraction
            if (fraction != null) current(snap(fraction))
        },
        modifier = modifier,
        description = description,
    )
}

/** Campo de texto propio sobre `BasicTextField`. */
@Composable
fun OndaraTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    leading: ImageVector? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
    singleLine: Boolean = true,
    password: Boolean = false,
) {
    val colors = OndaraTheme.colors
    val shape = OndaraTheme.shapes.control
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        textStyle = OndaraTheme.type.body.copy(color = colors.onBackground),
        cursorBrush = SolidColor(colors.accent),
        singleLine = singleLine,
        visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        keyboardActions = KeyboardActions(onAny = { onImeAction() }),
        decorationBox = { inner ->
            Row(
                Modifier
                    .defaultMinSize(minHeight = OndaraTheme.spacing.touch)
                    .clip(shape)
                    .background(colors.surfaceHigh)
                    .then(if (OndaraTheme.materials.hairline) Modifier.border(1.dp, colors.outline, shape) else Modifier)
                    .padding(horizontal = OndaraTheme.spacing.lg, vertical = OndaraTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leading != null) {
                    Icon(leading, null, tint = colors.onMuted, size = 20.dp)
                    Spacer(Modifier.width(OndaraTheme.spacing.md))
                }
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, color = colors.onMuted, maxLines = 1)
                    inner()
                }
                if (trailing != null) {
                    CompositionLocalProvider(LocalContentColor provides colors.onMuted) { trailing() }
                }
            }
        },
    )
}

/**
 * Marcador de carga con *shimmer* suave (en Ondara no hay spinners).
 * El brillo se anima en la fase de dibujo, así que no provoca recomposiciones.
 */
@Composable
fun Skeleton(modifier: Modifier = Modifier, shape: Shape = OndaraTheme.shapes.small) {
    val colors = OndaraTheme.colors
    val reduced = OndaraTheme.motion.reduced
    val transition = rememberInfiniteTransition(label = "shimmer")
    val phase by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "phase",
    )
    val base = colors.surfaceHigh
    val shine = colors.onBackground.copy(alpha = 0.08f)
    Box(
        modifier
            .clip(shape)
            .background(base)
            .drawBehind {
                if (reduced) return@drawBehind
                val w = size.width
                val x = (phase * 2f - 0.5f) * w
                drawRect(Brush.horizontalGradient(listOf(Color.Transparent, shine, Color.Transparent), x - w * 0.4f, x + w * 0.4f))
            },
    )
}

/** Separador fino de 1 px, sangrado a la izquierda como en las listas de iOS. */
@Composable
fun Hairline(modifier: Modifier = Modifier, startIndent: Dp = 0.dp) {
    Box(modifier.fillMaxWidth().padding(start = startIndent).height(1.dp).background(OndaraTheme.colors.outline.copy(alpha = 0.55f)))
}

/** Fila de ajuste: título, descripción opcional y un control al final. */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.pressable(onClick = onClick) else Modifier)
            .defaultMinSize(minHeight = 56.dp)
            .padding(horizontal = OndaraTheme.spacing.screen, vertical = OndaraTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = OndaraTheme.colors.accent, size = 22.dp)
            Spacer(Modifier.width(OndaraTheme.spacing.lg))
        }
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(title, style = OndaraTheme.type.headline, maxLines = 2)
            if (subtitle != null) Text(subtitle, style = OndaraTheme.type.caption, color = OndaraTheme.colors.onMuted, maxLines = 3)
        }
        if (trailing != null) {
            Spacer(Modifier.width(OndaraTheme.spacing.md))
            trailing()
        }
    }
}

/** Título de sección dentro de una pantalla. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = OndaraTheme.spacing.screen, vertical = OndaraTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), style = OndaraTheme.type.title, maxLines = 1)
        if (action != null && onAction != null) {
            Text(
                action,
                Modifier.pressable(onClick = onAction).padding(OndaraTheme.spacing.sm),
                style = OndaraTheme.type.label,
                color = OndaraTheme.colors.accent,
            )
        }
    }
}

/** Anima la aparición de un elemento una sola vez (entrada escalonada de listas). */
@Composable
fun Modifier.staggeredEntry(index: Int, enabled: Boolean): Modifier {
    val motion = OndaraTheme.motion
    if (!enabled || motion.reduced) return this
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(index.coerceAtMost(12) * motion.staggerMs.toLong())
        progress.animateTo(1f, motion.spring())
    }
    return graphicsLayer {
        alpha = progress.value.coerceIn(0f, 1f)
        translationY = (1f - progress.value) * 24.dp.toPx()
    }
}

/** Texto con el estilo local cambiado. */
@Composable
fun ProvideTextStyle(style: androidx.compose.ui.text.TextStyle, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.merge(style), content = content)
}
