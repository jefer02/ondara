package app.ondara.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.ondara.core.designsystem.component.*
import app.ondara.core.designsystem.icon.Icon
import app.ondara.core.designsystem.icon.OndaraIcons
import app.ondara.core.designsystem.theme.OndaraTheme
import app.ondara.core.designsystem.theme.PlayerLayout
import app.ondara.core.domain.PlayerController
import app.ondara.core.domain.SettingsRepository
import app.ondara.core.model.Song
import app.ondara.core.model.UserSettings
import app.ondara.core.ui.Artwork
import app.ondara.core.ui.MviViewModel
import app.ondara.core.ui.Nav
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlayerSettingsUiState(val settings: UserSettings = UserSettings(), val song: Song? = null)
sealed interface PlayerSettingsEvent {
    data class Layout(val value: String) : PlayerSettingsEvent
    data class Video(val enabled: Boolean) : PlayerSettingsEvent
    data class Wifi(val enabled: Boolean) : PlayerSettingsEvent
    data class Dither(val enabled: Boolean) : PlayerSettingsEvent
}

@HiltViewModel
class PlayerSettingsViewModel @Inject constructor(
    private val settings: SettingsRepository, player: PlayerController,
) : MviViewModel<PlayerSettingsUiState, PlayerSettingsEvent, Nothing>(PlayerSettingsUiState()) {
    init {
        viewModelScope.launch { settings.settings.collect { setState { copy(settings = it) } } }
        viewModelScope.launch { player.state.collect { setState { copy(song = it.current) } } }
    }
    override fun onEvent(event: PlayerSettingsEvent) {
        viewModelScope.launch {
            settings.update {
                when (event) {
                    is PlayerSettingsEvent.Layout -> it.copy(playerLayout = event.value)
                    is PlayerSettingsEvent.Video -> it.copy(videoBackground = event.enabled)
                    is PlayerSettingsEvent.Wifi -> it.copy(videoWifiOnly = event.enabled)
                    is PlayerSettingsEvent.Dither -> it.copy(ditherVideo = event.enabled)
                }
            }
        }
    }
}

@Composable
fun PlayerSettingsScreen(onNavigate: (Nav) -> Unit, modifier: Modifier = Modifier, viewModel: PlayerSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val theme = OndaraTheme.spec
    val options = listOf(
        UserSettings.PLAYER_LAYOUT_THEME to "Según el tema", "CINEMATIC" to "Cine", "ARTWORK_HERO" to "Portada",
        "IMMERSIVE" to "Inmersivo", "MINIMAL" to "Minimal", "VINYL" to "Vinilo", "BENTO" to "Bento",
    )
    ScreenScaffold("Reproductor", modifier, onBack = { onNavigate(Nav.Back) }) {
        item(key = "layouts", contentType = "selector") {
            LazyRow(contentPadding = PaddingValues(OndaraTheme.spacing.screen), horizontalArrangement = Arrangement.spacedBy(OndaraTheme.spacing.md)) {
                items(options, key = { it.first }, contentType = { "layout" }) { (value, label) ->
                    Column(Modifier.width(164.dp).pressable { viewModel.onEvent(PlayerSettingsEvent.Layout(value)) }, horizontalAlignment = Alignment.CenterHorizontally) {
                        LayoutThumbnail(resolvePlayerLayout(value, theme.playerLayout), state.song, state.settings.playerLayout == value)
                        Text(label, Modifier.padding(vertical = OndaraTheme.spacing.md), style = OndaraTheme.type.bodyStrong)
                    }
                }
            }
        }
        item(key = "video", contentType = "switch") {
            SettingRow("Vídeo de fondo en Cine", trailing = { OndaraSwitch(state.settings.videoBackground, { viewModel.onEvent(PlayerSettingsEvent.Video(it)) }) })
        }
        item(key = "wifi", contentType = "switch") {
            SettingRow("Vídeo solo con Wi-Fi", trailing = { OndaraSwitch(state.settings.videoWifiOnly, { viewModel.onEvent(PlayerSettingsEvent.Wifi(it)) }) })
        }
        if (theme.ditherArtwork) item(key = "dither", contentType = "switch") {
            SettingRow("Tramado de dos tonos en el vídeo", trailing = { OndaraSwitch(state.settings.ditherVideo, { viewModel.onEvent(PlayerSettingsEvent.Dither(it)) }) })
        }
    }
}

/** Miniaturas de composición, con la canción real y los tokens activos. O(1). */
@Composable
private fun LayoutThumbnail(layout: PlayerLayout, song: Song?, selected: Boolean) {
    val colors = OndaraTheme.colors
    val spacing = OndaraTheme.spacing
    val shape = OndaraTheme.shapes.large
    Box(Modifier.fillMaxWidth().height(250.dp).clip(shape).background(colors.background).border(if (selected) 3.dp else 1.dp, if (selected) colors.accent else colors.outline, shape)) {
        if (layout == PlayerLayout.CINEMATIC || layout == PlayerLayout.IMMERSIVE) {
            Artwork(song?.thumbnailUrl, Modifier.fillMaxSize(), playing = true)
            Box(Modifier.fillMaxSize().background(colors.background.copy(alpha = .65f)))
        }
        Column(Modifier.fillMaxSize().padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text("Reproduciendo", style = OndaraTheme.type.label, maxLines = 1)
            if (layout == PlayerLayout.CINEMATIC) Spacer(Modifier.weight(1f))
            Artwork(song?.thumbnailUrl, Modifier
                .size(if (layout == PlayerLayout.MINIMAL || layout == PlayerLayout.CINEMATIC) 40.dp else 116.dp)
                .align(Alignment.CenterHorizontally),
                shape = if (layout == PlayerLayout.VINYL) CircleShape else shape, playing = true)
            if (layout == PlayerLayout.MINIMAL) Text("0:00", style = OndaraTheme.type.monoLarge)
            Column(Modifier.fillMaxWidth().clip(OndaraTheme.shapes.medium).background(if (layout == PlayerLayout.BENTO) colors.blockLight else colors.surface.copy(alpha = .75f)).padding(spacing.sm)) {
                Text(song?.title ?: "Tu canción", style = OndaraTheme.type.caption, color = if (layout == PlayerLayout.BENTO) colors.onBlock else colors.onBackground, maxLines = 1)
                Text(song?.artistLine ?: "Artista", style = OndaraTheme.type.label, color = if (layout == PlayerLayout.BENTO) colors.onBlock else colors.onMuted, maxLines = 1)
            }
            Box(Modifier.fillMaxWidth().height(3.dp).background(if (layout == PlayerLayout.BENTO) colors.blockPrimary else colors.accent))
            Row(Modifier.fillMaxWidth().clip(shape).background(if (layout == PlayerLayout.BENTO) colors.blockMuted else colors.surface), horizontalArrangement = Arrangement.SpaceEvenly) {
                Icon(OndaraIcons.Previous, null, size = 20.dp)
                Icon(OndaraIcons.Play, null, size = 24.dp)
                Icon(OndaraIcons.Next, null, size = 20.dp)
            }
        }
        if (selected) Icon(OndaraIcons.Check, "Seleccionado", Modifier.align(Alignment.TopEnd).padding(spacing.sm), tint = colors.accent)
    }
}
