package app.ondara.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.ondara.core.designsystem.icon.Icon
import app.ondara.core.designsystem.icon.OndaraIcons
import app.ondara.core.designsystem.theme.BackgroundStyle
import app.ondara.core.designsystem.theme.NavStyle
import app.ondara.core.designsystem.theme.OndaraTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Espacio que ocupan la navegación y el mini-reproductor; las listas lo suman a su padding inferior. */
val LocalBottomInset = staticCompositionLocalOf { 0.dp }

/** Espacio lateral que ocupa el riel de navegación (tema Mono). */
val LocalStartInset = staticCompositionLocalOf { 0.dp }

/**
 * Andamio de pantalla con lista. No existe `TopAppBar`: el encabezado depende del tema.
 *
 * - `largeTitles` (Cupertino): título enorme que se encoge al hacer scroll mientras aparece una
 *   barra compacta de vidrio con el título centrado (*large title collapse*).
 * - Resto: el título es parte del contenido, con la tipografía de display del tema.
 */
@Composable
fun ScreenScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    listState: LazyListState = rememberLazyListState(),
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val spec = OndaraTheme.spec
    val type = OndaraTheme.type
    val status = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val barHeight = 52.dp
    val collapse by remember(listState) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f else (listState.firstVisibleItemScrollOffset / 160f).coerceIn(0f, 1f)
        }
    }
    Box(modifier.fillMaxSize().padding(start = LocalStartInset.current)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = status + barHeight, bottom = LocalBottomInset.current + OndaraTheme.spacing.xl),
        ) {
            item(key = "__title", contentType = "title") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = OndaraTheme.spacing.screen)
                        .padding(top = OndaraTheme.spacing.xs, bottom = OndaraTheme.spacing.lg)
                        .graphicsLayer {
                            if (spec.largeTitles) {
                                // El título grande se encoge hacia su esquina y se desvanece.
                                val s = 1f - collapse * 0.22f
                                scaleX = s
                                scaleY = s
                                alpha = 1f - collapse
                                transformOrigin = TransformOrigin(0f, 1f)
                            }
                        },
                ) {
                    Text(
                        if (type.uppercaseLabels) title.uppercase() else title,
                        Modifier.semantics { heading() },
                        style = if (spec.largeTitles) type.hero else type.display,
                        maxLines = 2,
                    )
                    if (subtitle != null) Text(subtitle, style = type.caption, color = OndaraTheme.colors.onMuted, maxLines = 2)
                }
            }
            content()
        }
        // Barra superior: vidrio en Cupertino (aparece al colapsar); transparente en el resto.
        val barAlpha = if (spec.largeTitles) collapse else 0f
        Box(Modifier.fillMaxWidth().height(status + barHeight)) {
            if (spec.largeTitles) {
                GlassSurface(Modifier.fillMaxSize().graphicsLayer { alpha = barAlpha }, shape = RoundedCornerShape(0.dp)) {}
                Text(
                    title,
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 15.dp).widthIn(max = 220.dp).graphicsLayer { alpha = barAlpha },
                    style = type.headline,
                    maxLines = 1,
                )
            }
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().height(barHeight).padding(horizontal = OndaraTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    OndaraIconButton(
                        OndaraIcons.Back, "Atrás", onBack,
                        tint = if (spec.largeTitles) OndaraTheme.colors.accent else OndaraTheme.colors.onBackground,
                        container = if (spec.largeTitles) Color.Transparent else OndaraTheme.colors.surfaceHigh.copy(alpha = 0.7f),
                    )
                }
                Spacer(Modifier.weight(1f))
                actions()
            }
        }
    }
}

data class NavItem(val icon: ImageVector, val label: String)

/** Navegación principal; su forma depende de `ThemeSpec.navigation`. */
@Composable
fun OndaraNavigation(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    when (OndaraTheme.spec.navigation) {
        NavStyle.FLOATING_GLASS_PILL -> GlassPillNav(items, selected, onSelect, modifier)
        NavStyle.BOTTOM_BAR -> BottomBarNav(items, selected, onSelect, modifier)
        NavStyle.SIDE_RAIL -> SideRailNav(items, selected, onSelect, modifier)
    }
}

@Composable
private fun GlassPillNav(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier) {
    val colors = OndaraTheme.colors
    val motion = OndaraTheme.motion
    val slot = 64.dp
    val indicatorX by animateDpAsState(slot * selected, motion.spring(), label = "navIndicator")
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = OndaraTheme.spacing.md), contentAlignment = Alignment.Center) {
        GlassSurface(shape = CircleShape) {
            Box(Modifier.padding(6.dp)) {
                Box(Modifier.offset { IntOffset(indicatorX.roundToPx(), 0) }.size(slot, 48.dp).clip(CircleShape).background(colors.accent))
                Row {
                    items.forEachIndexed { i, item ->
                        Box(
                            Modifier
                                .pressable(haptic = true, role = Role.Tab) { onSelect(i) }
                                .semantics { this.selected = i == selected }
                                .size(slot, 48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(item.icon, item.label, tint = if (i == selected) colors.onAccent else colors.onMuted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomBarNav(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier) {
    val colors = OndaraTheme.colors
    GlassSurface(modifier.fillMaxWidth(), shape = RoundedCornerShape(0.dp)) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(58.dp), verticalAlignment = Alignment.CenterVertically) {
            items.forEachIndexed { i, item ->
                val tint = if (i == selected) colors.accent else colors.onMuted
                Column(
                    Modifier
                        .weight(1f)
                        .pressable(haptic = true, role = Role.Tab) { onSelect(i) }
                        .semantics(mergeDescendants = true) { this.selected = i == selected },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(item.icon, null, tint = tint)
                    Spacer(Modifier.height(3.dp))
                    Label(item.label, color = tint)
                }
            }
        }
    }
}

@Composable
private fun SideRailNav(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier) {
    val colors = OndaraTheme.colors
    Row(modifier.fillMaxHeight().background(colors.background)) {
        Column(
            Modifier.fillMaxHeight().width(RailWidth).statusBarsPadding().navigationBarsPadding(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items.forEachIndexed { i, item ->
                val active = i == selected
                val mark by animateFloatAsState(if (active) 1f else 0f, OndaraTheme.motion.spring(), label = "rail")
                Row(
                    Modifier
                        .pressable(haptic = true, role = Role.Tab) { onSelect(i) }
                        .semantics { this.selected = active }
                        .fillMaxWidth()
                        .height(64.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(3.dp).height(28.dp).graphicsLayer { scaleY = mark }.background(colors.accent))
                    Spacer(Modifier.weight(1f))
                    Icon(item.icon, item.label, tint = if (active) colors.onBackground else colors.onMuted)
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.width(3.dp))
                }
            }
        }
        Box(Modifier.fillMaxHeight().width(1.dp).background(colors.outline))
    }
}

val RailWidth = 60.dp

/**
 * Hoja inferior propia: entra con el spring del tema y se cierra arrastrando hacia abajo
 * (con háptico al soltar pasado el umbral).
 */
@Composable
fun OndaraSheet(onDismiss: () -> Unit, modifier: Modifier = Modifier, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val motion = OndaraTheme.motion
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(1f) } // fracción de la altura: 1 = oculta
    val drag = remember { Animatable(0f) }
    LaunchedEffect(Unit) { offset.animateTo(0f, motion.spring()) }
    fun close() {
        scope.launch {
            offset.animateTo(1f, motion.fade(motion.fastMs))
            onDismiss()
        }
    }
    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 1f }
                .background(Color.Black.copy(alpha = 0.5f * (1f - offset.value.coerceIn(0f, 1f))))
                .pointerInput(Unit) { detectTapGestures { close() } },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                modifier
                    .fillMaxWidth()
                    .offset { IntOffset(0, (offset.value * 1200f + drag.value).roundToInt()) }
                    .pointerInput(Unit) { detectTapGestures { } }
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                if (drag.value > 140.dp.toPx()) {
                                    haptics.edge()
                                    close()
                                } else {
                                    scope.launch { drag.animateTo(0f, motion.spring()) }
                                }
                            },
                        ) { change, amount ->
                            change.consume()
                            scope.launch { drag.snapTo((drag.value + amount).coerceAtLeast(0f)) }
                        }
                    },
                shape = RoundedCornerShape(topStart = OndaraTheme.shapes.mediumRadius + 8.dp, topEnd = OndaraTheme.shapes.mediumRadius + 8.dp),
                color = OndaraTheme.colors.surface,
            ) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = OndaraTheme.spacing.lg)) {
                    Box(
                        Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp, bottom = 6.dp)
                            .size(40.dp, 4.dp).clip(CircleShape).background(OndaraTheme.colors.outline),
                    )
                    if (title != null) {
                        Text(
                            title,
                            Modifier.padding(horizontal = OndaraTheme.spacing.screen, vertical = OndaraTheme.spacing.md).semantics { heading() },
                            style = OndaraTheme.type.title,
                            maxLines = 2,
                        )
                    }
                    content()
                }
            }
        }
    }
}

/** Diálogo de confirmación / entrada propio. */
@Composable
fun OndaraDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    text: String? = null,
    dismissText: String = "Cancelar",
    confirmEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val motion = OndaraTheme.motion
    val scale = remember { Animatable(0.9f) }
    LaunchedEffect(Unit) { scale.animateTo(1f, motion.bouncy()) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier.padding(OndaraTheme.spacing.xxl).widthIn(max = 420.dp).graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            },
            shape = OndaraTheme.shapes.large,
        ) {
            Column(Modifier.padding(OndaraTheme.spacing.xl)) {
                Text(title, Modifier.semantics { heading() }, style = OndaraTheme.type.title)
                if (text != null) {
                    Spacer(Modifier.height(OndaraTheme.spacing.sm))
                    Text(text, color = OndaraTheme.colors.onMuted)
                }
                Spacer(Modifier.height(OndaraTheme.spacing.lg))
                content()
                Spacer(Modifier.height(OndaraTheme.spacing.lg))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(OndaraTheme.spacing.md, Alignment.End)) {
                    OndaraButton(dismissText, onDismiss, style = ButtonStyle.GHOST)
                    OndaraButton(confirmText, onConfirm, enabled = confirmEnabled)
                }
            }
        }
    }
}

/**
 * Fondo de la app según `ThemeSpec.background`:
 * sólido, mesh gradient animado, o la portada desenfocada al máximo ([blurredArtwork]).
 * Añade el grano del tema por encima.
 */
@Composable
fun OndaraBackground(
    modifier: Modifier = Modifier,
    blurredArtwork: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val spec = OndaraTheme.spec
    val colors = OndaraTheme.colors
    Box(modifier.fillMaxSize().background(colors.background).grain(spec.grain)) {
        when (spec.background) {
            BackgroundStyle.SOLID -> Unit
            BackgroundStyle.MESH_GRADIENT -> MeshGradientBackground(OndaraTheme.artwork, Modifier.fillMaxSize(), dim = 0.62f)
            BackgroundStyle.BLURRED_ART -> if (blurredArtwork != null) {
                blurredArtwork()
                Box(Modifier.fillMaxSize().background(colors.background.copy(alpha = if (colors.isDark) 0.72f else 0.8f)))
            }
        }
        content()
    }
}

/** Estado vacío / de error con acción opcional. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(OndaraTheme.spacing.huge),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = OndaraTheme.colors.onMuted, size = 44.dp)
        Spacer(Modifier.height(OndaraTheme.spacing.lg))
        Text(title, style = OndaraTheme.type.title, textAlign = TextAlign.Center)
        if (message != null) {
            Spacer(Modifier.height(OndaraTheme.spacing.xs))
            Text(message, color = OndaraTheme.colors.onMuted, textAlign = TextAlign.Center)
        }
        if (action != null && onAction != null) {
            Spacer(Modifier.height(OndaraTheme.spacing.xl))
            OndaraButton(action, onAction, style = ButtonStyle.TONAL)
        }
    }
}

/** Padding inferior estándar para contenido que no usa [ScreenScaffold]. */
@Composable
fun bottomContentPadding(extra: Dp = 0.dp): Dp =
    LocalBottomInset.current + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + extra
