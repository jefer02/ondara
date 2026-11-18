package app.ondara.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import app.ondara.core.designsystem.icon.Icon
import app.ondara.core.designsystem.icon.OndaraIcons
import app.ondara.core.designsystem.theme.ColorMath
import app.ondara.core.designsystem.theme.LocalContentColor
import app.ondara.core.designsystem.theme.OndaraTheme
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.launch

/** Fracción de la altura de pantalla que ocupa la imagen héroe. */
private const val HERO_FRACTION = 0.55f

/**
 * Página inmersiva de Artista, Álbum y Playlist.
 *
 * - **Imagen héroe a sangre** ([hero]) que ocupa todo el ancho y pasa por debajo de la barra de estado,
 *   con una altura del 55 % de la pantalla.
 * - **Desenfoque progresivo** en su mitad inferior (de 0 a blur fuerte, `HazeProgressive.verticalGradient`;
 *   en APIs sin soporte Haze degrada a un velo) que se funde con [pageColor], el color dominante de
 *   la imagen, que continúa en toda la página.
 * - **Nombre centrado** sobre la zona desenfocada, con una etiqueta opcional encima ([eyebrow]), una
 *   línea de metadatos ([meta]) y debajo la fila de botones de vidrio ([centerActions]).
 * - **Botones superiores flotantes de vidrio**: atrás a la izquierda, [topActions] a la derecha.
 * - **Scroll**: *parallax* (la imagen va a la mitad de velocidad), *stretch* (al sobre-desplazar hacia
 *   abajo la imagen crece anclada arriba) y, al pasar la imagen, una **barra superior de vidrio** con
 *   el nombre pequeño centrado (fundido + desplazamiento).
 *
 * Todo lo que cambia por frame se lee en `graphicsLayer {}`: el scroll no recompone.
 *
 * @param contentColor color del texto sobre [pageColor] (el llamador garantiza el contraste AA).
 */
@Composable
fun ImmersiveHeroScaffold(
    title: String,
    pageColor: Color,
    contentColor: Color,
    onBack: () -> Unit,
    hero: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
    artworkTop: Color = pageColor,
    eyebrow: String? = null,
    meta: String? = null,
    listState: LazyListState = rememberLazyListState(),
    topActions: @Composable RowScope.() -> Unit = {},
    centerActions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val density = LocalDensity.current
    val motion = OndaraTheme.motion
    val materials = OndaraTheme.materials
    val scope = rememberCoroutineScope()
    val status = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val screenHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    val heroHeight = (screenHeight * HERO_FRACTION).coerceAtLeast(320.dp)
    val heroPx = with(density) { heroHeight.toPx() }
    val barPx = with(density) { (status + TopBarHeight).toPx() }
    val heroHaze = remember { HazeState() }
    val blur = materials.blur > 0.dp
    val scroll by remember(listState) {
        derivedStateOf { if (listState.firstVisibleItemIndex > 0) heroPx * 2 else listState.firstVisibleItemScrollOffset.toFloat() }
    }
    // Sobre-desplazamiento propio (px): alimenta el stretch y vuelve a 0 con un spring al soltar.
    val pull = remember { Animatable(0f) }
    val stretch = remember(listState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (pull.value <= 0f || available.y >= 0f) return Offset.Zero
                val consumed = available.y.coerceAtLeast(-pull.value / PULL_RESISTANCE)
                scope.launch { pull.snapTo((pull.value + consumed * PULL_RESISTANCE).coerceAtLeast(0f)) }
                return Offset(0f, consumed)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y <= 0f || source != NestedScrollSource.UserInput) return Offset.Zero
                scope.launch { pull.snapTo((pull.value + available.y * PULL_RESISTANCE).coerceAtMost(heroPx * 0.6f)) }
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pull.value > 0f) pull.animateTo(0f, motion.spring())
                return Velocity.Zero
            }
        }
    }
    val chrome = LocalChromeState.current
    val collapsed: () -> Float = { ((scroll - (heroPx - barPx * 1.6f)) / (barPx * 0.6f)).coerceIn(0f, 1f) }
    val compact by remember(heroPx, barPx, listState) { derivedStateOf { collapsed() >= 1f } }

    CompositionLocalProvider(LocalContentColor provides contentColor, LocalGlassBackdrop provides pageColor) {
        Box(modifier.fillMaxSize().background(pageColor).padding(start = LocalStartInset.current)) {
            // Capa 1: imagen héroe con parallax y stretch; es la fuente del vidrio de la cabecera.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(heroHeight)
                    .graphicsLayer {
                        translationY = -scroll.coerceAtMost(heroPx) * 0.5f
                        val s = 1f + pull.value / heroPx
                        scaleX = s
                        scaleY = s
                        transformOrigin = TransformOrigin(0.5f, 0f)
                        alpha = (1f - (scroll - heroPx * 0.55f) / (heroPx * 0.45f)).coerceIn(0f, 1f)
                    }
                    .then(if (blur) Modifier.hazeSource(heroHaze) else Modifier),
            ) { hero(Modifier.fillMaxSize()) }
            // Capa 2: mitad inferior con desenfoque progresivo que se funde con el color de la página.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(heroHeight)
                    .graphicsLayer {
                        translationY = -scroll.coerceAtMost(heroPx) * 0.5f + pull.value
                        alpha = (1f - (scroll - heroPx * 0.55f) / (heroPx * 0.45f)).coerceIn(0f, 1f)
                    },
            ) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(heroHeight * 0.62f)
                        .then(
                            if (blur) {
                                Modifier.hazeEffect(
                                    heroHaze,
                                    HazeStyle(
                                        backgroundColor = pageColor,
                                        tints = listOf(HazeTint(pageColor.copy(alpha = 0.18f))),
                                        blurRadius = 44.dp,
                                        noiseFactor = 0f,
                                        fallbackTint = HazeTint(pageColor.copy(alpha = 0.5f)),
                                    ),
                                ) { progressive = HazeProgressive.verticalGradient(startIntensity = 0f, endIntensity = 1f) }
                            } else {
                                Modifier
                            },
                        )
                        .background(Brush.verticalGradient(0f to Color.Transparent, 0.55f to pageColor.copy(alpha = 0.62f), 1f to pageColor)),
                )
                // Velo superior: protege la barra de estado y los botones flotantes.
                Box(Modifier.fillMaxWidth().height(status + 72.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.34f), Color.Transparent))))
            }

            CompositionLocalProvider(LocalOverscrollFactory provides null, LocalHazeState provides if (blur) heroHaze else null) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(stretch)
                        .then(if (chrome != null) Modifier.nestedScroll(chrome.connection) else Modifier)
                        .graphicsLayer { translationY = pull.value },
                    contentPadding = PaddingValues(bottom = LocalBottomInset.current + OndaraTheme.spacing.xxl),
                ) {
                    item(key = "__hero", contentType = "hero") {
                        Column(
                            Modifier.fillMaxWidth().height(heroHeight)
                                .graphicsLayer {
                                    translationY = if (motion.reduced) 0f else scroll * HERO_PARALLAX
                                    alpha = (1f - scroll / (heroPx - barPx).coerceAtLeast(1f)).coerceIn(0f, 1f)
                                }
                                .drawWithContent {
                                    val parallax = if (motion.reduced) 0f else scroll * HERO_PARALLAX
                                    val top = (barPx + scroll - parallax - pull.value).coerceAtLeast(0f)
                                    if (top < size.height) clipRect(top = top) { this@drawWithContent.drawContent() }
                                }
                                .padding(horizontal = OndaraTheme.spacing.screen).padding(bottom = OndaraTheme.spacing.lg),
                            verticalArrangement = Arrangement.Bottom,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (eyebrow != null) {
                                GlassSurface(shape = CircleShape, tint = contentColor.copy(alpha = 0.16f).compositeOverSafe(pageColor), contentColor = contentColor) {
                                    Label(eyebrow, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = contentColor)
                                }
                                Spacer(Modifier.height(OndaraTheme.spacing.sm))
                            }
                            Text(
                                title,
                                Modifier.semantics { heading() },
                                style = OndaraTheme.type.display,
                                color = contentColor,
                                maxLines = 2,
                                textAlign = TextAlign.Center,
                            )
                            if (meta != null) {
                                Spacer(Modifier.height(OndaraTheme.spacing.xs))
                                Text(meta, style = OndaraTheme.type.caption, color = secondaryOn(contentColor, pageColor), maxLines = 2, textAlign = TextAlign.Center)
                            }
                            Spacer(Modifier.height(OndaraTheme.spacing.lg))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(OndaraTheme.spacing.xl),
                                verticalAlignment = Alignment.CenterVertically,
                                content = centerActions,
                            )
                        }
                    }
                    content()
                }

                // Barra superior: vidrio con el nombre pequeño, aparece al pasar la imagen.
                Box(Modifier.fillMaxWidth().height(status + TopBarHeight)) {
                    Box(
                        Modifier.fillMaxSize().graphicsLayer { alpha = collapsed() }.background(pageColor.copy(alpha = .9f)),
                    )
                    Text(
                        title,
                        Modifier.align(Alignment.BottomCenter).padding(bottom = 15.dp, start = OndaraTheme.spacing.touch * 3, end = OndaraTheme.spacing.touch * 3).graphicsLayer {
                            val c = collapsed()
                            alpha = c
                            translationY = (1f - c) * 10.dp.toPx()
                        },
                        style = OndaraTheme.type.headline,
                        color = contentColor,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                    )
                    Row(
                        Modifier.fillMaxWidth().statusBarsPadding().height(TopBarHeight).padding(horizontal = OndaraTheme.spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(OndaraTheme.spacing.sm),
                    ) {
                        CompositionLocalProvider(
                            LocalGlassBackdrop provides if (compact) pageColor else artworkTop,
                            LocalCompactGlass provides compact,
                        ) {
                            GlassIconButton(OndaraIcons.Back, "Atrás", onBack, size = OndaraTheme.spacing.touch)
                            Spacer(Modifier.weight(1f))
                            topActions()
                        }
                    }
                }
            }
        }
    }
}

private const val PULL_RESISTANCE = 0.5f
private const val HERO_PARALLAX = 0.25f

/** Compone un tinte translúcido sobre un fondo opaco (para vidrios sin fuente de blur). */
private fun Color.compositeOverSafe(background: Color): Color {
    val a = alpha
    return Color(
        red = red * a + background.red * (1 - a),
        green = green * a + background.green * (1 - a),
        blue = blue * a + background.blue * (1 - a),
        alpha = 1f,
    ).copy(alpha = 0.55f)
}

/**
 * Botón circular de vidrio (cabeceras inmersivas). Con [filled] es el botón principal: blanco opaco
 * (o el color de contenido) con el icono en negativo.
 */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    filled: Boolean = false,
) {
    val (glass, foreground) = immersiveGlass(LocalGlassBackdrop.current ?: OndaraTheme.colors.surface)
    val base = modifier
        .pressable(haptic = true, onClick = onClick)
        .semantics { this.contentDescription = contentDescription }
        .size(maxOf(size, OndaraTheme.spacing.touch))
    Box(
        base.clip(CircleShape).background(if (filled) foreground else if (LocalCompactGlass.current) Color.Transparent else glass),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (filled) glass.copy(alpha = 1f) else foreground, size = size * .46f)
    }
}

private val LocalGlassBackdrop = compositionLocalOf<Color?> { null }
private val LocalCompactGlass = compositionLocalOf { false }

/** Un tinte del 60 % mantiene AA incluso sobre un píxel de luminancia opuesta. O(1). */
internal fun immersiveGlass(background: Color): Pair<Color, Color> {
    val argb = background.toArgb()
    val light = ColorMath.luminance(argb) >= .179
    return if (light) Color.White.copy(alpha = .6f) to Color.Black else Color.Black.copy(alpha = .6f) to Color.White
}

private fun secondaryOn(foreground: Color, background: Color): Color {
    val muted = foreground.copy(alpha = .7f).compositeOver(background)
    return Color(ColorMath.ensureContrast(muted.toArgb(), background.toArgb()))
}

/** Tono secundario sobre el color real de GlassCard. */
@Composable
fun ImmersiveSecondaryColor(): Color {
    val foreground = LocalContentColor.current
    val background = LocalGlassBackdrop.current ?: OndaraTheme.colors.background
    return secondaryOn(foreground, foreground.copy(alpha = .09f).compositeOver(background))
}

/** Píldora de vidrio con texto (Reproducir / Aleatorio en álbum y playlist). */
@Composable
fun GlassPillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, filled: Boolean = false) {
    val (glass, foreground) = immersiveGlass(LocalGlassBackdrop.current ?: OndaraTheme.colors.surface)
    val inner: @Composable BoxScope.() -> Unit = {
        val fg = if (filled) glass.copy(alpha = 1f) else foreground
        Row(Modifier.padding(horizontal = OndaraTheme.spacing.xl, vertical = OndaraTheme.spacing.md), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = fg, size = 18.dp)
                Spacer(Modifier.width(OndaraTheme.spacing.sm))
            }
            Text(text, style = OndaraTheme.type.bodyStrong, color = fg, maxLines = 1)
        }
    }
    val base = modifier.pressable(haptic = true, onClick = onClick)
    Box(base.clip(CircleShape).background(if (filled) foreground else glass), contentAlignment = Alignment.Center, content = inner)
}

/**
 * Tarjeta de vidrio translúcido sobre el color de la página (secciones de las páginas inmersivas):
 * un tinte del color de contenido al 9 % con borde fino. No desenfoca: debajo solo hay color plano.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = OndaraTheme.shapes.large,
    contentPadding: PaddingValues = PaddingValues(vertical = OndaraTheme.spacing.sm),
    content: @Composable ColumnScope.() -> Unit,
) {
    val content0 = LocalContentColor.current
    Column(
        modifier
            .padding(horizontal = OndaraTheme.spacing.md)
            .fillMaxWidth()
            .clip(shape)
            .background(content0.copy(alpha = 0.09f))
            .then(if (OndaraTheme.materials.hairline) Modifier.border(1.dp, content0.copy(alpha = 0.1f), shape) else Modifier)
            .padding(contentPadding),
        content = content,
    )
}

/** Fondo del velo inferior de la app: funde el contenido con el fondo detrás de la barra y el mini-reproductor. */
@Composable
fun BottomFade(height: Dp, modifier: Modifier = Modifier, color: Color = OndaraTheme.colors.background) {
    Box(modifier.fillMaxWidth().height(height).background(Brush.verticalGradient(0f to Color.Transparent, 0.75f to color.copy(alpha = 0.9f), 1f to color)))
}
