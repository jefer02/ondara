package app.ondara.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.ondara.core.designsystem.component.ButtonStyle
import app.ondara.core.designsystem.component.GlassSurface
import app.ondara.core.designsystem.component.Label
import app.ondara.core.designsystem.component.MeshGradientBackground
import app.ondara.core.designsystem.component.NavItem
import app.ondara.core.designsystem.component.OndaraBackground
import app.ondara.core.designsystem.component.OndaraButton
import app.ondara.core.designsystem.component.OndaraChip
import app.ondara.core.designsystem.component.OndaraIconButton
import app.ondara.core.designsystem.component.OndaraNavigation
import app.ondara.core.designsystem.component.OndaraSlider
import app.ondara.core.designsystem.component.OndaraSwitch
import app.ondara.core.designsystem.component.OndaraTextField
import app.ondara.core.designsystem.component.PlayPauseButton
import app.ondara.core.designsystem.component.ScreenScaffold
import app.ondara.core.designsystem.component.SectionTitle
import app.ondara.core.designsystem.component.Skeleton
import app.ondara.core.designsystem.component.Surface
import app.ondara.core.designsystem.component.Text
import app.ondara.core.designsystem.icon.OndaraIcons
import app.ondara.core.designsystem.theme.NavStyle
import app.ondara.core.designsystem.theme.OndaraTheme

/** Catálogo vivo de todos los componentes con el tema activo. Base de los screenshot tests. */
@Composable
fun ShowcaseScreen(modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
    val type = OndaraTheme.type
    val spacing = OndaraTheme.spacing
    var playing by remember { mutableStateOf(true) }
    var chip by remember { mutableIntStateOf(0) }
    var checked by remember { mutableStateOf(true) }
    var progress by remember { mutableFloatStateOf(0.38f) }
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    val navItems = remember {
        listOf(NavItem(OndaraIcons.Home, "Inicio"), NavItem(OndaraIcons.Search, "Buscar"), NavItem(OndaraIcons.Library, "Biblioteca"))
    }
    val navStyle = OndaraTheme.spec.navigation
    OndaraBackground(modifier) {
        ScreenScaffold(
            title = OndaraTheme.spec.name,
            subtitle = OndaraTheme.spec.description,
            onBack = onBack,
            actions = { OndaraIconButton(OndaraIcons.Palette, "Temas", {}) },
        ) {
            item {
                Column(Modifier.padding(horizontal = spacing.screen), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Text("Tu música,", style = type.hero)
                    Text("en otra frecuencia", style = type.display, color = OndaraTheme.colors.accent)
                    Text("Título de sección", style = type.title)
                    Text("Cuerpo de texto para descripciones y metadatos de una canción.", style = type.body)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("03:42", style = type.monoLarge)
                        Spacer(Modifier.width(spacing.md))
                        Text("OPUS · 160 KBPS", style = type.mono, color = OndaraTheme.colors.onMuted)
                    }
                }
            }
            item { SectionTitle("Controles", action = "Ver todo", onAction = {}) }
            item {
                Column(Modifier.padding(horizontal = spacing.screen), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        OndaraButton("Reproducir", {}, icon = OndaraIcons.Play)
                        OndaraButton("Aleatorio", {}, style = ButtonStyle.TONAL, icon = OndaraIcons.Shuffle)
                    }
                    OndaraButton("Descargar", {}, style = ButtonStyle.GHOST, icon = OndaraIcons.Download)
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        listOf("Canciones", "Álbumes", "Artistas").forEachIndexed { i, label ->
                            OndaraChip(label, chip == i, { chip = i })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.lg)) {
                        PlayPauseButton(playing, { playing = !playing })
                        OndaraIconButton(OndaraIcons.Previous, "Anterior", {})
                        OndaraIconButton(OndaraIcons.Next, "Siguiente", {})
                        OndaraIconButton(OndaraIcons.HeartFilled, "Favorito", {}, tint = OndaraTheme.colors.accent)
                        OndaraSwitch(checked, { checked = it })
                    }
                    OndaraSlider(progress, { progress = it }, description = "Progreso")
                    OndaraTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = "Canciones, artistas, álbumes", leading = OndaraIcons.Search)
                }
            }
            item { SectionTitle("Materiales") }
            item {
                Box(Modifier.padding(horizontal = spacing.screen).fillMaxWidth().height(150.dp).clip(OndaraTheme.shapes.large)) {
                    MeshGradientBackground(OndaraTheme.artwork, Modifier.matchParentSize(), dim = 0.2f)
                    GlassSurface(Modifier.align(Alignment.Center).padding(spacing.lg)) {
                        Column(Modifier.padding(spacing.lg)) {
                            Label("Vidrio")
                            Text("Capa translúcida", style = type.headline)
                        }
                    }
                }
            }
            item {
                Row(Modifier.padding(spacing.screen), horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                    Surface(Modifier.size(84.dp), shape = OndaraTheme.shapes.artwork) {
                        Text("Aa", Modifier.align(Alignment.Center), style = type.title)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        Skeleton(Modifier.size(180.dp, 18.dp))
                        Skeleton(Modifier.size(120.dp, 14.dp))
                        Skeleton(Modifier.size(150.dp, 14.dp))
                    }
                }
            }
            if (navStyle != NavStyle.SIDE_RAIL) {
                item { OndaraNavigation(navItems, tab, { tab = it }, Modifier.padding(top = spacing.lg)) }
            }
        }
        if (navStyle == NavStyle.SIDE_RAIL) {
            // En el showcase el riel se superpone a la izquierda solo como muestra.
            OndaraNavigation(navItems, tab, { tab = it }, Modifier.align(Alignment.CenterEnd).height(220.dp))
        }
    }
}
