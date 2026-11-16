package app.ondara.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.lerp
import app.ondara.core.designsystem.theme.OndaraMotion
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import kotlin.math.sign
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
 * Estado del "cromo" inferior (barra de pestañas + mini-reproductor), compartido entre las pantallas
 * y `:app`. Las listas le informan del scroll mediante [connection]: al bajar más de [thresholdPx] la
 * barra se encoge al icono de la pestaña activa; al subir (o al tocarla) vuelve a expandirse.
 *
 * O(1) por evento de scroll; solo cambia estado (y recompone) al cruzar el umbral.
 */
@Stable
class ChromeState(private val thresholdPx: Float) {
    var collapsed by mutableStateOf(false)
        private set
    private var travelled = 0f

    fun expand() {
        collapsed = false
        travelled = 0f
    }

    val connection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            val dy = consumed.y
            if (dy == 0f) return Offset.Zero
            // Un cambio de sentido reinicia la distancia acumulada.
            travelled = if (travelled.sign != dy.sign) dy else travelled + dy
            if (travelled < -thresholdPx) collapsed = true else if (travelled > thresholdPx) collapsed = false
            return Offset.Zero
        }
    }
}

@Composable
fun rememberChromeState(): ChromeState {
    val threshold = with(LocalDensity.current) { 28.dp.toPx() }
    return remember(threshold) { ChromeState(threshold) }
}

val LocalChromeState = staticCompositionLocalOf<ChromeState?> { null }

/** Alto de la barra compacta superior (sin la barra de estado). */
val TopBarHeight = 52.dp

/**
 * Andamio de pantalla con lista. No existe `TopAppBar`: el encabezado es propio.
 *
 * - **Título grande** en negrita que forma parte del contenido y, al hacer scroll, se encoge y pasa
 *   a una barra superior pequeña de vidrio (*large title collapse*). En cuanto el contenido empieza
 *   a pasar por debajo de la barra de estado aparece un velo con **desenfoque progresivo**, de modo
 *   que nada flota sobre las portadas sin fondo.
 * - Las [actions] (el avatar del perfil en las pestañas) viven en esa barra.
 *
 * La lista es su propia fuente de desenfoque (`HazeState` local), independiente de la de la app.
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
    val type = OndaraTheme.type
    val colors = OndaraTheme.colors
    val materials = OndaraTheme.materials
    val density = LocalDensity.current
    val status = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val large = onBack == null
    val collapseRange = with(density) { 72.dp.toPx() }
    val scrimRange = with(density) { 20.dp.toPx() }
    val offset by remember(listState) {
        derivedStateOf { if (listState.firstVisibleItemIndex > 0) Float.MAX_VALUE else listState.firstVisibleItemScrollOffset.toFloat() }
    }
    val chrome = LocalChromeState.current
    val haze = remember { HazeState() }
    val blur = materials.blur > 0.dp
    Box(modifier.fillMaxSize().padding(start = LocalStartInset.current)) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .then(if (blur) Modifier.hazeSource(haze) else Modifier)
                .then(if (chrome != null) Modifier.nestedScroll(chrome.connection) else Modifier),
            contentPadding = PaddingValues(top = status + TopBarHeight, bottom = LocalBottomInset.current + OndaraTheme.spacing.xxl),
        ) {
            item(key = "__title", contentType = "title") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = OndaraTheme.spacing.screen)
                        .padding(top = if (large) 0.dp else OndaraTheme.spacing.xs, bottom = OndaraTheme.spacing.lg)
                        .graphicsLayer {
                            // El título grande se encoge hacia su esquina y se desvanece.
                            val t = (offset / collapseRange).coerceIn(0f, 1f)
                            val s = 1f - t * 0.2f
                            scaleX = s
                            scaleY = s
                            alpha = 1f - t
                            transformOrigin = TransformOrigin(0f, 1f)
                        },
                ) {
                    Text(
                        if (type.uppercaseLabels) title.uppercase() else title,
                        Modifier.semantics { heading() },
                        style = if (large) type.largeTitle else type.display,
                        maxLines = 2,
                    )
                    if (subtitle != null) {
                        Spacer(Modifier.height(OndaraTheme.spacing.xxs))
                        Text(subtitle, style = type.caption, color = colors.onMuted, maxLines = 2)
                    }
                }
            }
            content()
        }
        Box(Modifier.fillMaxWidth().height(status + TopBarHeight)) {
            // Velo: vidrio con desenfoque progresivo (o tinte sólido en temas mate), visible en cuanto hay scroll.
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = (offset / scrimRange).coerceIn(0f, 1f) }
                    .then(
                        if (blur) {
                            Modifier.hazeEffect(
                                haze,
                                HazeStyle(
                                    backgroundColor = colors.background,
                                    tints = listOf(HazeTint(colors.background.copy(alpha = 0.55f))),
                                    blurRadius = materials.blur,
                                    noiseFactor = 0f,
                                    fallbackTint = HazeTint(colors.background.copy(alpha = 0.92f)),
                                ),
                            ) { progressive = HazeProgressive.verticalGradient(startIntensity = 1f, endIntensity = 0.35f) }
                        } else {
                            Modifier.background(colors.background)
                        },
                    ),
            )
            if (!blur && materials.hairline) {
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(1.dp)
                        .graphicsLayer { alpha = (offset / scrimRange).coerceIn(0f, 1f) }.background(colors.outline.copy(alpha = 0.6f)),
                )
            }
            Text(
                title,
                Modifier.align(Alignment.BottomCenter).padding(bottom = 15.dp).widthIn(max = 220.dp)
                    .graphicsLayer { alpha = ((offset - collapseRange * 0.6f) / (collapseRange * 0.4f)).coerceIn(0f, 1f) },
                style = type.headline,
                maxLines = 1,
            )
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().height(TopBarHeight).padding(horizontal = OndaraTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) OndaraIconButton(OndaraIcons.Back, "Atrás", onBack)
                Spacer(Modifier.weight(1f))
                actions()
            }
        }
    }
}

data class NavItem(val icon: ImageVector, val label: String)

/** Alto de la píldora de pestañas y de los círculos de vidrio de la barra inferior. */
val NavPillHeight = 58.dp

/** Separación de la barra inferior respecto a los bordes de la pantalla. */
val NavPillMargin = 12.dp

/**
 * Navegación principal; su forma depende de `ThemeSpec.navigation`.
 *
 * - `FLOATING_GLASS_PILL`: una píldora de vidrio con las pestañas y, separado a la derecha, un
 *   círculo de vidrio para [search]. Con [collapsed] la píldora se encoge al icono de la pestaña
 *   activa (spring `dampingRatio 0.8`) y deja sitio al mini-reproductor; tocarla llama a [onExpand].
 * - `BOTTOM_BAR` y `SIDE_RAIL`: [search] es un destino más al final.
 *
 * @param selected índice de la pestaña activa, [items].size si lo activo es la búsqueda, -1 si ninguna.
 */
@Composable
fun OndaraNavigation(
    items: List<NavItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    search: NavItem? = null,
    collapsed: Boolean = false,
    onExpand: () -> Unit = {},
) {
    val all = remember(items, search) { if (search != null) items + search else items }
    when (OndaraTheme.spec.navigation) {
        NavStyle.FLOATING_GLASS_PILL -> GlassPillNav(items, selected, onSelect, modifier, search, collapsed, onExpand)
        NavStyle.BOTTOM_BAR -> BottomBarNav(all, selected, onSelect, modifier)
        NavStyle.SIDE_RAIL -> SideRailNav(all, selected, onSelect, modifier)
    }
}

@Composable
private fun GlassPillNav(
    items: List<NavItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier,
    search: NavItem?,
    collapsed: Boolean,
    onExpand: () -> Unit,
) {
    val colors = OndaraTheme.colors
    val motion = OndaraTheme.motion
    val navSpring: FiniteAnimationSpec<Float> = if (motion.reduced) tween(OndaraMotion.REDUCED_MS) else spring(0.8f, motion.stiffness)
    val searchSelected = selected == items.size
    // Con la búsqueda activa (o en una pantalla de detalle) la píldora recuerda la última pestaña.
    var lastTab by rememberSaveable { mutableIntStateOf(0) }
    if (selected in items.indices) lastTab = selected
    val t by animateFloatAsState(if (collapsed) 1f else 0f, navSpring, label = "navCollapse")
    val indicator by animateFloatAsState(lastTab.toFloat(), navSpring, label = "navIndicator")
    val indicatorAlpha by animateFloatAsState(if (selected in items.indices) 1f else 0f, motion.fade(motion.fastMs), label = "navIndicatorAlpha")
    BoxWithConstraints(
        modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = NavPillMargin).padding(bottom = NavPillMargin),
    ) {
        val gap = OndaraTheme.spacing.sm
        val expandedWidth = maxWidth - if (search != null) NavPillHeight + gap else 0.dp
        val inner = 5.dp
        val slot = (expandedWidth - inner * 2) / items.size
        val highlight = colors.onBackground.copy(alpha = if (colors.isDark) 0.14f else 0.1f)
        GlassSurface(
            Modifier
                .align(Alignment.CenterStart)
                .layout { measurable, constraints ->
                    // El ancho se interpola en la fase de layout: la animación no recompone.
                    val width = lerp(expandedWidth, NavPillHeight, t.coerceIn(0f, 1f)).roundToPx()
                    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                    layout(width, placeable.height) { placeable.place(0, 0) }
                }
                .height(NavPillHeight)
                .then(if (collapsed) Modifier.pressable(haptic = true, onClickLabel = "Mostrar pestañas", onClick = onExpand) else Modifier),
            shape = CircleShape,
        ) {
            Box(
                Modifier.fillMaxSize().wrapContentWidth(Alignment.Start, unbounded = true).graphicsLayer {
                    // Al encogerse, la fila se desliza para dejar el icono activo centrado en el círculo.
                    val collapsedShift = -(inner + slot * lastTab + slot / 2 - NavPillHeight / 2).toPx()
                    translationX = collapsedShift * t.coerceIn(0f, 1f)
                },
            ) {
                // Resaltado redondeado que se desliza entre pestañas.
                Box(
                    Modifier
                        .padding(vertical = inner)
                        .graphicsLayer {
                            translationX = (inner + slot * indicator).toPx()
                            alpha = indicatorAlpha * (1f - t).coerceIn(0f, 1f)
                        }
                        .width(slot)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(highlight),
                )
                Row(Modifier.padding(horizontal = inner).fillMaxHeight().width(slot * items.size)) {
                    items.forEachIndexed { i, item ->
                        val active = i == selected
                        val tint = if (active || (i == lastTab && collapsed)) colors.accent else colors.onBackground
                        Column(
                            Modifier
                                .width(slot)
                                .fillMaxHeight()
                                .graphicsLayer { alpha = if (i == lastTab) 1f else (1f - t * 1.6f).coerceIn(0f, 1f) }
                                .then(if (collapsed) Modifier else Modifier.pressable(haptic = true, role = Role.Tab) { onSelect(i) })
                                .semantics(mergeDescendants = true) {
                                    this.selected = active
                                    contentDescription = item.label
                                },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(item.icon, null, Modifier.graphicsLayer { translationY = 7.dp.toPx() * t.coerceIn(0f, 1f) }, tint = tint, size = 22.dp)
                            Label(
                                item.label,
                                Modifier.padding(top = 2.dp).graphicsLayer { alpha = (1f - t * 2f).coerceIn(0f, 1f) },
                                color = tint,
                            )
                        }
                    }
                }
            }
        }
        if (search != null) {
            GlassSurface(
                Modifier
                    .align(Alignment.CenterEnd)
                    .size(NavPillHeight)
                    .pressable(haptic = true, role = Role.Tab) { onSelect(items.size) }
                    .semantics {
                        this.selected = searchSelected
                        contentDescription = search.label
                    },
                shape = CircleShape,
                contentAlignment = Alignment.Center,
            ) {
                Icon(search.icon, null, tint = if (searchSelected) colors.accent else colors.onBackground, size = 22.dp)
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
                        .semantics(mergeDescendants = true) {
                            this.selected = i == selected
                            contentDescription = item.label
                        },
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

/**
 * Hoja de vidrio **sin ventana propia**: se compone en la raíz de la app, como hermana de la fuente
 * de desenfoque, y por eso sí desenfoca de verdad lo que hay detrás (un `Dialog` no puede).
 *
 * Entra con el spring del tema creciendo desde [origin] (p. ej. la esquina del avatar) y se cierra
 * arrastrando hacia abajo, tocando fuera o con [dismissRequests] (botón atrás, que gestiona el llamador).
 */
@Composable
fun GlassOverlaySheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    origin: TransformOrigin = TransformOrigin(0.5f, 1f),
    content: @Composable ColumnScope.() -> Unit,
) {
    val motion = OndaraTheme.motion
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    val drag = remember { Animatable(0f) }
    var rendered by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (visible) {
            rendered = true
            drag.snapTo(0f)
            progress.animateTo(1f, motion.spring())
        } else {
            progress.animateTo(0f, motion.fade(motion.fastMs))
            rendered = false
        }
    }
    if (!rendered) return
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.42f * progress.value.coerceIn(0f, 1f)))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.BottomCenter,
    ) {
        GlassSurface(
            modifier
                .padding(horizontal = OndaraTheme.spacing.sm)
                .navigationBarsPadding()
                .padding(bottom = OndaraTheme.spacing.sm)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .graphicsLayer {
                    val p = progress.value
                    val s = 0.72f + 0.28f * p
                    scaleX = s
                    scaleY = s
                    alpha = p.coerceIn(0f, 1f)
                    transformOrigin = origin
                    translationY = drag.value
                }
                .pointerInput(Unit) { detectTapGestures { } }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (drag.value > 120.dp.toPx()) {
                                haptics.edge()
                                onDismiss()
                            } else {
                                scope.launch { drag.animateTo(0f, motion.spring()) }
                            }
                        },
                    ) { change, amount ->
                        change.consume()
                        scope.launch { drag.snapTo((drag.value + amount).coerceAtLeast(0f)) }
                    }
                },
            shape = OndaraTheme.shapes.large,
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = OndaraTheme.spacing.md)) {
                Box(
                    Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp, bottom = 6.dp)
                        .size(40.dp, 4.dp).clip(CircleShape).background(OndaraTheme.colors.onMuted.copy(alpha = 0.5f)),
                )
                content()
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
