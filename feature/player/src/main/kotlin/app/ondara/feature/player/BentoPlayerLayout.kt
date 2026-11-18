package app.ondara.feature.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import app.ondara.core.designsystem.component.*
import app.ondara.core.designsystem.icon.OndaraIcons
import app.ondara.core.designsystem.theme.OndaraTheme
import app.ondara.core.ui.*
import kotlinx.coroutines.flow.Flow

@OptIn(ExperimentalSharedTransitionApi::class)
@Suppress("ModifierParameter")
@Composable
internal fun BentoPlayerLayout(
    state: PlayerUiState, onEvent: (PlayerEvent) -> Unit, positionFlow: Flow<Long>,
    onCollapse: () -> Unit, onNavigate: (Nav) -> Unit, lyrics: LyricsSlots,
    sharedScope: SharedTransitionScope, visibilityScope: AnimatedVisibilityScope, dragModifier: Modifier,
) {
    val song = state.player.current ?: return
    val colors = OndaraTheme.colors
    val spacing = OndaraTheme.spacing
    var panel by remember { mutableStateOf("") }
    BackHandler(panel.isNotEmpty()) { panel = "" }
    Box(Modifier.fillMaxSize().background(colors.background)) {
        PlayerDetails(song, lyrics, { panel = "Letra" }, onNavigate) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(spacing.screen),
                verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                Row(Modifier.fillMaxWidth().then(dragModifier), verticalAlignment = Alignment.CenterVertically) {
                    OndaraIconButton(OndaraIcons.ChevronDown, "Minimizar reproductor", onCollapse)
                    Text(state.player.context?.title ?: "Reproduciendo", style = OndaraTheme.type.caption)
                }
                with(sharedScope) {
                    Artwork(song.thumbnailUrl, Modifier.fillMaxWidth().weight(1f).bentoEntry(0, true)
                        .sharedElement(rememberSharedContentState(KEY_ARTWORK), visibilityScope),
                        shape = OndaraTheme.shapes.large, playing = true, sizePx = 720)
                }
                Surface(Modifier.fillMaxWidth().bentoEntry(1, true),
                    shape = OndaraTheme.shapes.large, color = colors.blockLight, contentColor = colors.onBlock, hairline = false) {
                    Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
                        with(sharedScope) {
                            PlayerMetadata(song, Modifier.weight(1f)
                                .sharedBounds(rememberSharedContentState(KEY_TITLE), visibilityScope), colors.onBlock)
                        }
                        OndaraIconButton(if (state.favorite) OndaraIcons.HeartFilled else OndaraIcons.Heart,
                            "Favorito", { onEvent(PlayerEvent.ToggleFavorite) }, tint = colors.onBlock, haptic = true)
                    }
                }
                Surface(Modifier.fillMaxWidth().bentoEntry(2, true),
                    shape = OndaraTheme.shapes.large, color = colors.blockPrimary, contentColor = colors.onBlock, hairline = false) {
                    Progress(state.player, positionFlow, { onEvent(PlayerEvent.SeekFraction(it)) }, Modifier.padding(spacing.lg), foreground = colors.onBlock)
                }
                Surface(Modifier.fillMaxWidth().bentoEntry(3, true),
                    shape = OndaraTheme.shapes.large, color = colors.blockMuted, contentColor = colors.onBlockMuted, hairline = false) {
                    with(sharedScope) {
                        PlayerTransport(state.player, onEvent,
                            Modifier.sharedBounds(rememberSharedContentState(KEY_PLAY), visibilityScope), colors.onBlockMuted)
                    }
                }
                PlayerScrollHint(colors.onMuted)
                PlayerBottomActions(song, { panel = it })
            }
        }
        if (panel == "Cola") QueuePanel(state.player, onEvent) { panel = "" }
        if (panel == "Letra") PlayerLyricsOverlay(lyrics) { panel = "" }
    }
    if (panel == "Temporizador") PlayerTimer(state.player, onEvent) { panel = "" }
    if (panel == "Menú") SongMenuSheet(song, { panel = "" }, { onNavigate(Nav.Artist(it)) }, { onNavigate(Nav.Album(it)) })
}

/** Entrada escalonada de recorrido corto y amortiguación alta; coste O(1) por bloque. */
@Composable
private fun Modifier.bentoEntry(index: Int, enabled: Boolean): Modifier {
    val motion = OndaraTheme.motion
    if (!enabled || motion.reduced) return this
    val entry = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index * motion.staggerMs.toLong())
        entry.animateTo(1f, spring(dampingRatio = .92f, stiffness = motion.stiffness))
    }
    return graphicsLayer {
        alpha = entry.value.coerceIn(0f, 1f)
        translationY = (1f - entry.value) * 16.dp.toPx()
    }
}
