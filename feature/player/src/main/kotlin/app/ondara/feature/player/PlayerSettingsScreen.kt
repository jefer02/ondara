package app.ondara.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.ondara.core.designsystem.component.*
import app.ondara.core.designsystem.theme.OndaraTheme
import app.ondara.core.domain.PlayerController
import app.ondara.core.domain.SettingsRepository
import app.ondara.core.model.PlayerState
import app.ondara.core.model.UserSettings
import app.ondara.core.ui.MviViewModel
import app.ondara.core.ui.Nav
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlayerSettingsUiState(val settings: UserSettings = UserSettings(), val player: PlayerState = PlayerState())
sealed interface PlayerSettingsEvent {
    data class Layout(val value: String) : PlayerSettingsEvent
    data class Video(val enabled: Boolean) : PlayerSettingsEvent
    data class Wifi(val enabled: Boolean) : PlayerSettingsEvent
    data class Dither(val enabled: Boolean) : PlayerSettingsEvent
}

@HiltViewModel
class PlayerSettingsViewModel @Inject constructor(
    private val settings: SettingsRepository, private val player: PlayerController,
) : MviViewModel<PlayerSettingsUiState, PlayerSettingsEvent, Nothing>(PlayerSettingsUiState()) {
    init {
        viewModelScope.launch { settings.settings.collect { setState { copy(settings = it) } } }
        viewModelScope.launch { player.state.collect { setState { copy(player = it) } } }
    }
    fun positionFlow(): Flow<Long> = player.positionFlow(500)

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

/** Selector observable: persiste cada elección sin una acción adicional de aplicar. O(1) por evento. */
@Composable
fun PlayerSettingsScreen(
    onNavigate: (Nav) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val positionFlow = remember(viewModel) { viewModel.positionFlow() }
    PlayerSettingsContent(state, viewModel::onEvent, onNavigate, positionFlow, modifier)
}

/** Contenido comprobable sin Hilt; O(l) para los l layouts disponibles. */
@Composable
internal fun PlayerSettingsContent(
    state: PlayerSettingsUiState,
    onEvent: (PlayerSettingsEvent) -> Unit,
    onNavigate: (Nav) -> Unit,
    positionFlow: Flow<Long>,
    modifier: Modifier = Modifier,
) {
    val spacing = OndaraTheme.spacing
    val dither = OndaraTheme.spec.ditherArtwork
    val options = listOf(
        UserSettings.PLAYER_LAYOUT_THEME to "Según el tema", "CINEMATIC" to "Cine", "ARTWORK_HERO" to "Portada",
        "IMMERSIVE" to "Inmersivo", "MINIMAL" to "Minimal", "VINYL" to "Vinilo", "BENTO" to "Bento",
    )
    ScreenScaffold("Reproductor", modifier, onBack = { onNavigate(Nav.Back) }) {
        item(key = "layouts", contentType = "selector") {
            LazyRow(Modifier.testTag("selector-layout"), contentPadding = PaddingValues(spacing.screen),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                items(options, key = { it.first }, contentType = { "layout" }) { (value, label) ->
                    OndaraChip(label, state.settings.playerLayout == value, { onEvent(PlayerSettingsEvent.Layout(value)) },
                        Modifier.heightIn(min = spacing.touch))
                }
            }
        }
        item(key = "preview", contentType = "preview") {
            if (state.player.current == null) {
                Text("Reproduce una canción para ver este diseño", Modifier.padding(spacing.screen), style = OndaraTheme.type.body)
            } else {
                LayoutPreview(state, positionFlow, Modifier.padding(spacing.screen))
            }
        }
        item(key = "video", contentType = "switch") {
            SettingRow("Vídeo de fondo en Cine", trailing = {
                OndaraSwitch(state.settings.videoBackground, { onEvent(PlayerSettingsEvent.Video(it)) })
            })
        }
        item(key = "wifi", contentType = "switch") {
            SettingRow("Vídeo solo con Wi-Fi", trailing = {
                OndaraSwitch(state.settings.videoWifiOnly, { onEvent(PlayerSettingsEvent.Wifi(it)) })
            })
        }
        if (dither) item(key = "dither", contentType = "switch") {
            SettingRow("Tramado de dos tonos en el vídeo", trailing = {
                OndaraSwitch(state.settings.ditherVideo, { onEvent(PlayerSettingsEvent.Dither(it)) })
            })
        }
    }
}

/** Reutiliza el reproductor real a escala de una ventana de referencia; O(1) en espacio y disposición. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun LayoutPreview(state: PlayerSettingsUiState, position: Flow<Long>, modifier: Modifier = Modifier) {
    val lyrics = remember { LyricsSlots(full = {}, currentLine = {}, preview = {}) }
    val previewState = PlayerUiState(player = state.player, settings = state.settings)
    Box(modifier.fillMaxWidth().clip(OndaraTheme.shapes.large).background(OndaraTheme.colors.background)
        .clearAndSetSemantics { contentDescription = "Vista previa del diseño seleccionado" }) {
        Layout(
            modifier = Modifier.fillMaxWidth(),
            content = {
                key(state.settings.playerLayout, OndaraTheme.spec.playerLayout) {
                    SharedTransitionLayout {
                        val shared = this
                        AnimatedVisibility(visible = true) {
                            FullPlayer(previewState, {}, position, {}, {}, lyrics, shared, this, Modifier, active = false)
                        }
                    }
                }
            },
        ) { measurables, constraints ->
            val viewportWidth = 393.dp.roundToPx()
            val viewportHeight = 851.dp.roundToPx()
            val scale = constraints.maxWidth.toFloat() / viewportWidth
            val placeable = measurables.single().measure(Constraints.fixed(viewportWidth, viewportHeight))
            layout(constraints.maxWidth, (viewportHeight * scale).toInt()) {
                placeable.placeWithLayer(0, 0) {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                }
            }
        }
        // La miniatura muestra el layout real; los controles se utilizan en el reproductor abierto.
        Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures {} })
    }
}
