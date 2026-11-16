package app.ondara.feature.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ondara.core.designsystem.component.GlassSurface
import app.ondara.core.designsystem.component.LocalStartInset
import app.ondara.core.designsystem.component.OndaraIconButton
import app.ondara.core.designsystem.component.Text
import app.ondara.core.designsystem.component.pressable
import app.ondara.core.designsystem.component.rememberHaptics
import app.ondara.core.designsystem.icon.OndaraIcons
import app.ondara.core.designsystem.theme.OndaraTheme
import app.ondara.core.model.PlayerState
import app.ondara.core.ui.Artwork
import app.ondara.core.ui.Nav
import kotlinx.coroutines.launch

internal const val KEY_ARTWORK = "player-artwork"
internal const val KEY_TITLE = "player-title"
internal const val KEY_PLAY = "player-play"

/** Alto del mini-reproductor, para que `:app` calcule el inset inferior de las listas. */
val MiniPlayerHeight = 64.dp

/**
 * Mini-reproductor ↔ reproductor completo con `SharedTransitionLayout`.
 *
 * La portada, el título y el botón de play viajan como elementos compartidos. La transición la
 * conduce un `SeekableTransitionState`: el arrastre vertical fija su progreso (`seekTo`), por lo que
 * es interrumpible y reversible a mitad de camino; al soltar, un spring la lleva al extremo.
 *
 * @param lyrics contenido de la vista de letra (lo inyecta `:app` desde `:feature:lyrics`).
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PlayerHost(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    miniBottomPadding: Dp,
    onNavigate: (Nav) -> Unit,
    lyrics: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val player = state.player
    if (player.current == null) return

    val motion = OndaraTheme.motion
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val seekable = remember { SeekableTransitionState(expanded) }
    val transition = rememberTransition(seekable, label = "player")
    val screenHeight = LocalWindowInfo.current.containerSize.height.toFloat().coerceAtLeast(1f)
    val currentExpanded by rememberUpdatedState(expanded)
    var dragFraction by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(expanded) {
        if (seekable.currentState != expanded || seekable.targetState != expanded) {
            seekable.animateTo(expanded, if (motion.reduced) tween(150) else spring(motion.dampingRatio.coerceAtLeast(0.8f), motion.stiffness * 0.7f))
        }
    }
    BackHandler(enabled = expanded) { onExpandedChange(false) }

    // Gesto compartido por ambos estados: arrastrar hacia arriba expande, hacia abajo colapsa.
    val dragModifier = Modifier.pointerInput(Unit) {
        detectVerticalDragGestures(
            onDragStart = { dragFraction = 0f },
            onDragEnd = {
                val target = if (dragFraction > 0.22f) !currentExpanded else currentExpanded
                if (target != currentExpanded) haptics.edge()
                scope.launch {
                    seekable.animateTo(target, if (motion.reduced) tween(150) else spring(0.85f, motion.stiffness * 0.7f))
                }
                onExpandedChange(target)
            },
            onDragCancel = { scope.launch { seekable.animateTo(currentExpanded) } },
        ) { change, amount ->
            change.consume()
            val delta = if (currentExpanded) amount else -amount
            dragFraction = (dragFraction + delta / (screenHeight * 0.7f)).coerceIn(0f, 1f)
            scope.launch { seekable.seekTo(dragFraction, targetState = !currentExpanded) }
        }
    }

    SharedTransitionLayout(modifier.fillMaxSize()) {
        transition.AnimatedContent(
            modifier = Modifier.fillMaxSize(),
            transitionSpec = { fadeIn(tween(motion.fastMs)) togetherWith fadeOut(tween(motion.fastMs)) },
            contentAlignment = Alignment.BottomCenter,
        ) { isExpanded ->
            if (isExpanded) {
                FullPlayer(
                    state = state,
                    onEvent = viewModel::onEvent,
                    positionFlow = viewModel.positionFlow(),
                    onCollapse = { onExpandedChange(false) },
                    onNavigate = { onExpandedChange(false); onNavigate(it) },
                    lyrics = lyrics,
                    sharedScope = this@SharedTransitionLayout,
                    visibilityScope = this,
                    dragModifier = dragModifier,
                )
            } else {
                Box(Modifier.fillMaxSize().padding(start = LocalStartInset.current), contentAlignment = Alignment.BottomCenter) {
                    MiniPlayer(
                        player = player,
                        positionMs = viewModel::positionMs,
                        onToggle = { viewModel.onEvent(PlayerEvent.TogglePlay) },
                        onNext = { viewModel.onEvent(PlayerEvent.Next) },
                        onExpand = { onExpandedChange(true) },
                        sharedScope = this@SharedTransitionLayout,
                        visibilityScope = this@AnimatedContent,
                        modifier = Modifier.padding(bottom = miniBottomPadding).then(dragModifier),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun MiniPlayer(
    player: PlayerState,
    positionMs: () -> Long,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onExpand: () -> Unit,
    sharedScope: SharedTransitionScope,
    visibilityScope: AnimatedVisibilityScope,
    modifier: Modifier = Modifier,
) {
    val song = player.current ?: return
    val colors = OndaraTheme.colors
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(player.durationMs) {
        while (true) withFrameMillis { progress = if (player.durationMs > 0) positionMs().toFloat() / player.durationMs else 0f }
    }
    with(sharedScope) {
        GlassSurface(
            modifier
                .padding(horizontal = OndaraTheme.spacing.md)
                .fillMaxWidth()
                .height(MiniPlayerHeight)
                .pressable(onClickLabel = "Abrir reproductor", onClick = onExpand)
                .semantics { contentDescription = "Reproduciendo ${song.title} de ${song.artistLine}" }
                .drawWithContent {
                    drawContent()
                    // Progreso fino en el borde inferior, pintado sin recomponer.
                    drawRect(colors.accent, Offset(0f, size.height - 2.dp.toPx()), Size(size.width * progress.coerceIn(0f, 1f), 2.dp.toPx()))
                },
            shape = OndaraTheme.shapes.medium,
        ) {
            Row(Modifier.fillMaxSize().padding(start = OndaraTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                Artwork(
                    song.thumbnailUrl,
                    Modifier.size(46.dp).sharedElement(rememberSharedContentState(KEY_ARTWORK), visibilityScope),
                    shape = OndaraTheme.shapes.small,
                    sizePx = 544,
                )
                Spacer(Modifier.width(OndaraTheme.spacing.md))
                Column(Modifier.weight(1f).sharedBounds(rememberSharedContentState(KEY_TITLE), visibilityScope)) {
                    Text(song.title, style = OndaraTheme.type.bodyStrong, maxLines = 1)
                    Text(song.artistLine, style = OndaraTheme.type.caption, color = colors.onMuted, maxLines = 1)
                }
                OndaraIconButton(
                    if (player.isPlaying) OndaraIcons.Pause else OndaraIcons.Play,
                    if (player.isPlaying) "Pausar" else "Reproducir",
                    onToggle,
                    Modifier.sharedBounds(rememberSharedContentState(KEY_PLAY), visibilityScope),
                    haptic = true,
                )
                OndaraIconButton(OndaraIcons.Next, "Siguiente", onNext, enabled = player.hasNext, haptic = true)
            }
        }
    }
}
