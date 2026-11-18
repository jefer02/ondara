package app.ondara.feature.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import app.ondara.core.model.RepeatMode
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
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(spacing.screen), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            Row(Modifier.fillMaxWidth().then(dragModifier), verticalAlignment = Alignment.CenterVertically) {
                OndaraIconButton(OndaraIcons.ChevronDown, "Minimizar reproductor", onCollapse)
                Text(state.player.context?.title ?: "Reproduciendo", style = OndaraTheme.type.caption)
            }
            with(sharedScope) {
                Artwork(song.thumbnailUrl, Modifier.fillMaxWidth().aspectRatio(1f).bentoEntry(0, true)
                    .sharedElement(rememberSharedContentState(KEY_ARTWORK), visibilityScope).then(dragModifier),
                    shape = OndaraTheme.shapes.large, playing = true, sizePx = 720)
            }
            Surface(Modifier.fillMaxWidth().bentoEntry(1, true), shape = OndaraTheme.shapes.large, color = colors.blockLight, contentColor = colors.onBlock, hairline = false) {
                Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
                    with(sharedScope) {
                        Column(Modifier.weight(1f).sharedBounds(rememberSharedContentState(KEY_TITLE), visibilityScope)) {
                            Text(song.title, style = OndaraTheme.type.title)
                            Text(song.artistLine, style = OndaraTheme.type.body)
                        }
                    }
                    OndaraIconButton(if (state.favorite) OndaraIcons.HeartFilled else OndaraIcons.Heart, "Favorito", { onEvent(PlayerEvent.ToggleFavorite) }, tint = colors.onBlock, haptic = true)
                }
            }
            Surface(Modifier.fillMaxWidth().bentoEntry(2, true), shape = OndaraTheme.shapes.large, color = colors.blockPrimary, contentColor = colors.onBlock, hairline = false) {
                Progress(state.player, positionFlow, { onEvent(PlayerEvent.SeekFraction(it)) }, Modifier.padding(spacing.lg), foreground = colors.onBlock)
            }
            Surface(Modifier.fillMaxWidth().bentoEntry(3, true), shape = OndaraTheme.shapes.large, color = colors.blockMuted, contentColor = colors.onBlockMuted, hairline = false) {
                Row(Modifier.fillMaxWidth().padding(vertical = spacing.md), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    OndaraIconButton(OndaraIcons.Shuffle, "Aleatorio", { onEvent(PlayerEvent.ToggleShuffle) }, tint = if (state.player.shuffle) colors.accent else colors.onBlockMuted)
                    OndaraIconButton(OndaraIcons.Previous, "Anterior", { onEvent(PlayerEvent.Previous) }, tint = colors.onBlockMuted)
                    with(sharedScope) {
                        PlayPauseButton(state.player.isPlaying, { onEvent(PlayerEvent.TogglePlay) }, Modifier.sharedBounds(rememberSharedContentState(KEY_PLAY), visibilityScope))
                    }
                    OndaraIconButton(OndaraIcons.Next, "Siguiente", { onEvent(PlayerEvent.Next) }, tint = colors.onBlockMuted, enabled = state.player.hasNext)
                    OndaraIconButton(OndaraIcons.Repeat, "Repetir", { onEvent(PlayerEvent.CycleRepeat) }, tint = if (state.player.repeatMode == RepeatMode.OFF) colors.onBlockMuted else colors.accent)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf("Letra" to OndaraIcons.Lyrics, "Cola" to OndaraIcons.Queue, "Temporizador" to OndaraIcons.Timer, "Menú" to OndaraIcons.More).forEach { (name, icon) ->
                    OndaraIconButton(icon, name, { panel = name })
                }
            }
        }
        if (panel == "Cola") QueuePanel(state.player, onEvent) { panel = "" }
        if (panel == "Letra") {
            Column(Modifier.fillMaxSize().background(colors.background).statusBarsPadding().navigationBarsPadding()) {
                OndaraIconButton(OndaraIcons.Close, "Cerrar letra", { panel = "" })
                lyrics.full(Modifier.weight(1f).fillMaxWidth())
            }
        }
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
