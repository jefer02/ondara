package app.ondara.feature.themes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.ondara.core.designsystem.component.Label
import app.ondara.core.designsystem.component.OndaraButton
import app.ondara.core.designsystem.component.OndaraChip
import app.ondara.core.designsystem.component.OndaraTextField
import app.ondara.core.designsystem.component.OndaraValueSlider
import app.ondara.core.designsystem.component.ScreenScaffold
import app.ondara.core.designsystem.component.Text
import app.ondara.core.designsystem.icon.OndaraIcons
import app.ondara.core.designsystem.theme.BackgroundStyle
import app.ondara.core.designsystem.theme.ColorMath
import app.ondara.core.designsystem.theme.NavStyle
import app.ondara.core.designsystem.theme.OndaraTheme
import app.ondara.core.designsystem.theme.Palette
import app.ondara.core.designsystem.theme.PlayerLayout
import app.ondara.core.designsystem.theme.ThemeSpec
import app.ondara.core.ui.CollectEffects
import app.ondara.core.ui.Nav

/**
 * Aplica las decisiones del editor a un tema base. El acento se corrige para cumplir AA contra
 * el fondo y `onAccent` se recalcula, de modo que un tema propio nunca queda ilegible.
 */
internal fun buildCustomTheme(
    base: ThemeSpec, name: String, hue: Float, radius: Int, blur: Int,
    layout: PlayerLayout, nav: NavStyle, background: BackgroundStyle,
): ThemeSpec {
    fun recolor(p: Palette): Palette {
        val bg = ColorMath.parseHex(p.background)
        val accent = ColorMath.ensureContrast(Color.hsv(hue.coerceIn(0f, 359.9f), 0.72f, 0.96f).toArgb(), bg)
        return p.copy(accent = ColorMath.toHex(accent), onAccent = ColorMath.toHex(ColorMath.ensureContrast(ColorMath.onColor(accent), accent)))
    }
    val id = "custom-" + name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "tema" }
    return base.copy(
        id = id,
        name = name.ifBlank { "Mi tema" },
        description = "Tema personalizado basado en ${base.name}.",
        colors = base.colors.copy(dynamic = false, dark = recolor(base.colors.dark), light = base.colors.light?.let(::recolor)),
        shapes = base.shapes.copy(small = (radius * 0.5f).toInt(), medium = (radius * 0.8f).toInt(), large = (radius * 1.3f).toInt(), artwork = radius),
        materials = base.materials.copy(blur = blur),
        playerLayout = layout,
        navigation = nav,
        background = background,
        custom = true,
    )
}

/** Editor de temas: color de acento, radio de esquinas, intensidad de blur, layout del reproductor y navegación. */
@Composable
fun ThemeEditorScreen(onNavigate: (Nav) -> Unit, modifier: Modifier = Modifier, viewModel: ThemesViewModel = hiltViewModel()) {
    val base = remember { viewModel.active() }
    var name by remember { mutableStateOf("Mi tema") }
    var hue by remember { mutableFloatStateOf(190f) }
    var radius by remember { mutableFloatStateOf(base.shapes.artwork.toFloat()) }
    var blur by remember { mutableFloatStateOf(base.materials.blur.toFloat()) }
    var layout by remember { mutableStateOf(base.playerLayout) }
    var nav by remember { mutableStateOf(base.navigation) }
    var background by remember { mutableStateOf(base.background) }
    val draft = remember(name, hue, radius, blur, layout, nav, background) {
        buildCustomTheme(base, name, hue, radius.toInt(), blur.toInt(), layout, nav, background)
    }
    CollectEffects(viewModel.effects) { if (it is ThemesEffect.Saved) onNavigate(Nav.Back) }
    val spacing = OndaraTheme.spacing

    ScreenScaffold("Editor de temas", modifier, onBack = { onNavigate(Nav.Back) }, subtitle = "Parte de «${base.name}» y hazlo tuyo.") {
        item(key = "preview") {
            Box(Modifier.fillMaxWidth().padding(horizontal = spacing.screen), contentAlignment = Alignment.Center) {
                ThemePreview(draft, Modifier.width(190.dp).aspectRatio(0.7f).clip(OndaraTheme.shapes.large))
            }
        }
        item(key = "name") {
            Label("Nombre", Modifier.padding(start = spacing.screen, top = spacing.xl, bottom = spacing.sm))
            OndaraTextField(name, { name = it.take(28) }, Modifier.fillMaxWidth().padding(horizontal = spacing.screen), placeholder = "Mi tema", leading = OndaraIcons.Edit)
        }
        item(key = "accent") {
            Label("Color de acento", Modifier.padding(start = spacing.screen, top = spacing.xl))
            Box(Modifier.padding(horizontal = spacing.screen)) {
                Canvas(Modifier.fillMaxWidth().height(10.dp).align(Alignment.Center).clip(CircleShape)) {
                    drawRect(Brush.horizontalGradient(List(13) { Color.hsv(it * 30f % 360f, 0.72f, 0.96f) }))
                }
                OndaraValueSlider(hue, { hue = it }, 0f..360f, description = "Tono del color de acento")
            }
        }
        item(key = "radius") {
            Label("Radio de esquinas · ${radius.toInt()} dp", Modifier.padding(start = spacing.screen, top = spacing.md))
            OndaraValueSlider(radius, { radius = it }, 0f..36f, Modifier.padding(horizontal = spacing.screen), steps = 36, description = "Radio de esquinas")
        }
        item(key = "blur") {
            Label("Intensidad de blur · ${blur.toInt()} dp", Modifier.padding(start = spacing.screen, top = spacing.md))
            OndaraValueSlider(blur, { blur = it }, 0f..48f, Modifier.padding(horizontal = spacing.screen), steps = 24, description = "Intensidad del desenfoque")
        }
        item(key = "layout") {
            Chooser("Layout del reproductor", PlayerLayout.entries, layout, { layout = it }) {
                when (it) {
                    PlayerLayout.ARTWORK_HERO -> "Portada"
                    PlayerLayout.IMMERSIVE -> "Inmersivo"
                    PlayerLayout.MINIMAL -> "Mínimo"
                    PlayerLayout.VINYL -> "Vinilo"
                    PlayerLayout.BENTO -> "Bento"
                    PlayerLayout.CINEMATIC -> "Cine"
                }
            }
        }
        item(key = "nav") {
            Chooser("Navegación", NavStyle.entries, nav, { nav = it }) {
                when (it) {
                    NavStyle.FLOATING_GLASS_PILL -> "Píldora de vidrio"
                    NavStyle.BOTTOM_BAR -> "Barra inferior"
                    NavStyle.SIDE_RAIL -> "Riel lateral"
                }
            }
        }
        item(key = "background") {
            Chooser("Fondo", BackgroundStyle.entries, background, { background = it }) {
                when (it) {
                    BackgroundStyle.SOLID -> "Sólido"
                    BackgroundStyle.MESH_GRADIENT -> "Mesh gradient"
                    BackgroundStyle.BLURRED_ART -> "Portada difuminada"
                }
            }
        }
        item(key = "save") {
            OndaraButton(
                "Guardar y aplicar", { viewModel.onEvent(ThemesEvent.Save(draft, applyPlayerLayout = layout != base.playerLayout)) },
                Modifier.fillMaxWidth().padding(spacing.screen), icon = OndaraIcons.Check,
            )
            Text("Se guarda como JSON; puedes exportarlo desde la galería.", Modifier.padding(horizontal = spacing.screen), style = OndaraTheme.type.caption, color = OndaraTheme.colors.onMuted)
        }
    }
}

@Composable
private fun <T> Chooser(title: String, options: List<T>, selected: T, onSelect: (T) -> Unit, label: (T) -> String) {
    Label(title, Modifier.padding(start = OndaraTheme.spacing.screen, top = OndaraTheme.spacing.xl, bottom = OndaraTheme.spacing.sm))
    LazyRow(contentPadding = PaddingValues(horizontal = OndaraTheme.spacing.screen), horizontalArrangement = Arrangement.spacedBy(OndaraTheme.spacing.sm)) {
        items(options) { option -> OndaraChip(label(option), option == selected, { onSelect(option) }) }
    }
}
