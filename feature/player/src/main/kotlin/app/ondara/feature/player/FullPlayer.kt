package app.ondara.feature.player

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ondara.core.designsystem.component.Label
import app.ondara.core.designsystem.component.MeshGradientBackground
import app.ondara.core.designsystem.component.OndaraIconButton
import app.ondara.core.designsystem.component.OndaraSheet
import app.ondara.core.designsystem.component.OndaraSlider
import app.ondara.core.designsystem.component.Text
import app.ondara.core.designsystem.component.grain
import app.ondara.core.designsystem.icon.OndaraIcons
import app.ondara.core.designsystem.theme.LocalAudioAmplitude
import app.ondara.core.designsystem.theme.OndaraTheme
import app.ondara.core.designsystem.theme.PlayerLayout
import app.ondara.core.model.PlayerState
import app.ondara.core.model.Song
import app.ondara.core.ui.Artwork
import app.ondara.core.ui.MenuAction
import app.ondara.core.ui.Nav
import app.ondara.core.ui.SongMenuSheet
import app.ondara.core.ui.formatPosition
import kotlinx.coroutines.flow.Flow

@Suppress("ModifierParameter") // dragModifier no es el modificador raíz: marca las zonas que aceptan el gesto de colapsar
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun FullPlayer(
    state: PlayerUiState,
    onEvent: (PlayerEvent) -> Unit,
    positionFlow: Flow<Long>,
    onCollapse: () -> Unit,
    onNavigate: (Nav) -> Unit,
    lyrics: LyricsSlots,
    sharedScope: SharedTransitionScope,
    visibilityScope: AnimatedVisibilityScope,
    dragModifier: Modifier,
    active: Boolean,
) {
    val player = state.player
    val song = player.current ?: return
    val layout = resolvePlayerLayout(state.settings.playerLayout, OndaraTheme.spec.playerLayout)
    val canvasEnabled = active && layout == PlayerLayout.CINEMATIC &&
        state.settings.videoBackground && !OndaraTheme.motion.reduced
    DisposableEffect(canvasEnabled) {
        onEvent(PlayerEvent.CanvasActive(canvasEnabled))
        onDispose { onEvent(PlayerEvent.CanvasActive(false)) }
    }
    if (layout == PlayerLayout.CINEMATIC) {
        CinematicPlayerLayout(state, onEvent, positionFlow, onCollapse, onNavigate, lyrics, sharedScope, visibilityScope, dragModifier)
        return
    }
    if (layout == PlayerLayout.BENTO) {
        BentoPlayerLayout(state, onEvent, positionFlow, onCollapse, onNavigate, lyrics, sharedScope, visibilityScope, dragModifier)
        return
    }
    val colors = OndaraTheme.colors
    val spacing = OndaraTheme.spacing
    val motion = OndaraTheme.motion
    var showLyrics by rememberSaveable(layout) { mutableStateOf(false) }
    var showQueue by rememberSaveable(layout) { mutableStateOf(false) }
    var showTimer by remember(layout) { mutableStateOf(false) }
    var showMenu by remember(layout) { mutableStateOf(false) }

    BackHandler(showLyrics || showQueue) {
        showLyrics = false
        showQueue = false
    }
    Box(Modifier.fillMaxSize().background(colors.background).grain(OndaraTheme.spec.grain)) {
        PlayerBackdrop(layout, song)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // Barra superior (también es la zona de arrastre para colapsar).
            Row(Modifier.fillMaxWidth().then(dragModifier).padding(horizontal = spacing.sm, vertical = spacing.xs),
                verticalAlignment = Alignment.CenterVertically) {
                OndaraIconButton(OndaraIcons.ChevronDown, "Minimizar reproductor", onCollapse)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Label("Reproduciendo")
                    song.album?.let { Text(it.title, style = OndaraTheme.type.caption, maxLines = 1) }
                }
                OndaraIconButton(OndaraIcons.More, "Más opciones", { showMenu = true })
            }

            // Zona central: portada según el layout del tema, o la letra.
            AnimatedContent(
                showLyrics,
                Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = { fadeIn(motion.fade()) togetherWith fadeOut(motion.fade()) },
                label = "playerCenter",
            ) { lyricsMode ->
                if (lyricsMode) {
                    lyrics.full(Modifier.fillMaxSize())
                } else {
                    Box(Modifier.fillMaxSize().then(dragModifier), contentAlignment = Alignment.Center) {
                        with(sharedScope) {
                            val shared = Modifier.sharedElement(rememberSharedContentState(KEY_ARTWORK), visibilityScope)
                            when (layout) {
                                PlayerLayout.ARTWORK_HERO -> HeroArtwork(song, player.isPlaying, shared, edgePadding = 28)
                                PlayerLayout.IMMERSIVE -> HeroArtwork(song, player.isPlaying, shared, edgePadding = 20)
                                PlayerLayout.VINYL -> VinylArtwork(song, player.isPlaying, shared)
                                PlayerLayout.CINEMATIC, PlayerLayout.BENTO -> Unit
                                PlayerLayout.MINIMAL -> MinimalArtwork(song, positionFlow, player, shared)
                            }
                        }
                    }
                }
            }

            // Título + favorito.
            Row(Modifier.fillMaxWidth().padding(horizontal = spacing.xxl), verticalAlignment = Alignment.CenterVertically) {
                with(sharedScope) {
                    PlayerMetadata(song, Modifier.weight(1f)
                        .sharedBounds(rememberSharedContentState(KEY_TITLE), visibilityScope))
                }
                OndaraIconButton(
                    if (state.favorite) OndaraIcons.HeartFilled else OndaraIcons.Heart,
                    if (state.favorite) "Quitar de favoritos" else "Añadir a favoritos",
                    { onEvent(PlayerEvent.ToggleFavorite) },
                    tint = if (state.favorite) colors.accent else colors.onBackground,
                    haptic = true,
                )
            }

            Progress(player, positionFlow, { onEvent(PlayerEvent.SeekFraction(it)) }, Modifier.padding(horizontal = spacing.xxl))

            with(sharedScope) {
                PlayerTransport(player, onEvent, Modifier.sharedBounds(rememberSharedContentState(KEY_PLAY), visibilityScope))
            }
            PlayerBottomActions(song, { panel ->
                when (panel) {
                    "Cola" -> showQueue = true
                    "Temporizador" -> showTimer = true
                }
            })
            LyricsHandle({ showLyrics = !showLyrics }, Modifier.padding(horizontal = spacing.screen), song.thumbnailUrl)
            Spacer(Modifier.height(spacing.lg))
        }

        AnimatedVisibility(
            showQueue,
            enter = slideInVertically(OndaraTheme.motion.spring()) { it } + fadeIn(),
            exit = slideOutVertically(OndaraTheme.motion.spring()) { it } + fadeOut(),
        ) {
            QueuePanel(player, onEvent) { showQueue = false }
        }
    }

    if (showTimer) {
        OndaraSheet({ showTimer = false }, title = "Temporizador de sueño") {
            player.sleepTimerEndsAtMs?.let { end ->
                val left = ((end - System.currentTimeMillis()) / 60_000).coerceAtLeast(0) + 1
                MenuAction(OndaraIcons.Close, "Cancelar (quedan $left min)", { onEvent(PlayerEvent.SleepTimer(null)); showTimer = false }, active = true)
            }
            listOf(5, 15, 30, 45, 60, 90).forEach { minutes ->
                MenuAction(OndaraIcons.Timer, "$minutes minutos", { onEvent(PlayerEvent.SleepTimer(minutes)); showTimer = false })
            }
        }
    }
    if (showMenu) SongMenuSheet(song, { showMenu = false }, { onNavigate(Nav.Artist(it)) }, { onNavigate(Nav.Album(it)) })
}

/** Fondo del reproductor: mesh gradient de la portada, portada desenfocada al máximo, o liso. */
@Composable
private fun PlayerBackdrop(layout: PlayerLayout, song: Song) {
    val colors = OndaraTheme.colors
    when (layout) {
        PlayerLayout.ARTWORK_HERO -> if (!OndaraTheme.spec.accentPulse) MeshGradientBackground(OndaraTheme.artwork, Modifier.fillMaxSize()) else NeonGlow()
        PlayerLayout.IMMERSIVE -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && song.thumbnailUrl != null) {
            Artwork(song.thumbnailUrl, Modifier.fillMaxSize().graphicsLayer { scaleX = 1.4f; scaleY = 1.4f }.blur(90.dp), shape = RectangleShape, sizePx = 240)
            Box(Modifier.fillMaxSize().background(colors.background.copy(alpha = if (colors.isDark) 0.55f else 0.7f)))
        } else {
            MeshGradientBackground(OndaraTheme.artwork, Modifier.fillMaxSize(), dim = 0.35f)
        }
        PlayerLayout.VINYL -> Box(
            Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(colors.surfaceHigh, colors.background))),
        )
        PlayerLayout.MINIMAL, PlayerLayout.CINEMATIC, PlayerLayout.BENTO -> Unit
    }
}

/** Halo que late con la amplitud del audio (tema Neón). Solo fase de dibujo. */
@Composable
private fun NeonGlow() {
    val amplitude = LocalAudioAmplitude.current
    val accent = OndaraTheme.colors.accent
    Box(
        Modifier.fillMaxSize().drawBehind {
            val a = amplitude().coerceIn(0f, 1f)
            val radius = size.minDimension * (0.55f + a * 0.35f)
            drawCircle(
                Brush.radialGradient(listOf(accent.copy(alpha = 0.10f + a * 0.3f), Color.Transparent), center.copy(y = size.height * 0.36f), radius),
                radius, center.copy(y = size.height * 0.36f),
            )
        },
    )
}

/** Portada "que respira": escala 1.0 al reproducir y 0.82 al pausar, con spring (dampingRatio 0.7). */
@Composable
private fun HeroArtwork(song: Song, playing: Boolean, modifier: Modifier, edgePadding: Int) {
    val motion = OndaraTheme.motion
    val scale by animateFloatAsState(if (playing) 1f else 0.82f, motion.bouncy(0.7f, 260f), label = "breathe")
    val pulse = OndaraTheme.spec.accentPulse
    val amplitude = LocalAudioAmplitude.current
    val accent = OndaraTheme.colors.accent
    val shape = OndaraTheme.shapes.artwork
    Artwork(
        song.thumbnailUrl,
        Modifier
            .padding(horizontal = edgePadding.dp, vertical = 12.dp)
            .graphicsLayer {
                val s = scale * if (pulse) 1f + amplitude() * 0.03f else 1f
                scaleX = s
                scaleY = s
            }
            .then(modifier)
            .aspectRatio(1f)
            .then(if (pulse) Modifier.border(2.dp, accent, shape) else Modifier),
        shape = shape,
        sizePx = 544,
        contentDescription = "Portada de ${song.album?.title ?: song.title}",
    )
}

/** Disco de vinilo que gira mientras suena; al pausar frena con inercia. */
@Composable
private fun VinylArtwork(song: Song, playing: Boolean, modifier: Modifier) {
    val reduced = OndaraTheme.motion.reduced
    var rotation by remember { mutableFloatStateOf(0f) }
    val speed by animateFloatAsState(if (playing && !reduced) 1f else 0f, OndaraTheme.motion.gentle(), label = "vinylSpeed")
    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                // 33⅓ rpm ≈ 200°/s.
                rotation = (rotation + (now - last) / 1e9f * 200f * speed) % 360f
                last = now
            }
        }
    }
    val colors = OndaraTheme.colors
    BoxWithConstraints(Modifier.padding(28.dp).aspectRatio(1f), contentAlignment = Alignment.Center) {
        val label = maxWidth * 0.42f
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = rotation }
                .clip(CircleShape)
                .background(Color(0xFF0B0B0B))
                .drawBehind {
                    // Surcos y brillo del vinilo.
                    val r = size.minDimension / 2
                    for (i in 0..14) drawCircle(Color.White.copy(alpha = 0.045f), r * (0.46f + i * 0.036f), style = Stroke(1.2f))
                    drawCircle(Brush.sweepGradient(listOf(
                        Color.Transparent, Color.White.copy(alpha = 0.10f), Color.Transparent,
                        Color.White.copy(alpha = 0.07f), Color.Transparent,
                    )), r)
                },
            contentAlignment = Alignment.Center,
        ) {
            Artwork(song.thumbnailUrl, modifier.size(label), shape = CircleShape, sizePx = 544, contentDescription = "Portada de ${song.title}")
            Box(Modifier.size(10.dp).clip(CircleShape).background(colors.background))
        }
    }
}

/** Layout minimalista (Mono): portada pequeña y el tiempo en tipografía de matriz de puntos gigante. */
@Composable
private fun MinimalArtwork(song: Song, positionFlow: Flow<Long>, player: PlayerState, modifier: Modifier) {
    val position by positionFlow.collectAsStateWithLifecycle(0L)
    val colors = OndaraTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = OndaraTheme.spacing.xxl), verticalArrangement = Arrangement.Center) {
        Artwork(song.thumbnailUrl, modifier.size(112.dp), shape = OndaraTheme.shapes.artwork, sizePx = 544)
        Spacer(Modifier.height(OndaraTheme.spacing.xxl))
        Text(formatPosition(position), style = OndaraTheme.type.monoLarge.copy(
            fontSize = OndaraTheme.type.monoLarge.fontSize * 2.4f,
            lineHeight = OndaraTheme.type.monoLarge.lineHeight * 2.4f,
        ), maxLines = 1)
        Text("/ " + formatPosition(player.durationMs), style = OndaraTheme.type.monoLarge, color = colors.onMuted, maxLines = 1)
        Spacer(Modifier.height(OndaraTheme.spacing.lg))
        Box(Modifier.width(40.dp).height(3.dp).background(colors.accent))
        Spacer(Modifier.height(OndaraTheme.spacing.sm))
        Label("${player.currentIndex + 1} / ${player.queue.size} EN COLA")
    }
}

@Composable
internal fun Progress(
    player: PlayerState, positionFlow: Flow<Long>, onSeek: (Float) -> Unit, modifier: Modifier = Modifier,
    light: Boolean = false, foreground: Color = OndaraTheme.colors.onMuted,
) {
    val position by positionFlow.collectAsStateWithLifecycle(0L)
    var scrub by remember { mutableStateOf<Float?>(null) }
    val duration = player.durationMs
    val fraction = if (duration > 0) position.toFloat() / duration else 0f
    Column(modifier.fillMaxWidth()) {
        OndaraSlider(fraction, onSeek, activeColor = if (light) Color.White else foreground,
            trackColor = if (light) Color.White.copy(alpha = .3f) else foreground.copy(alpha = .18f), onScrub = { scrub = it },
            description = "Posición de reproducción")
        Row(Modifier.fillMaxWidth()) {
            Text(formatPosition(scrub?.let { (it * duration).toLong() } ?: position),
                style = OndaraTheme.type.mono, color = if (light) Color.White else foreground)
            Spacer(Modifier.weight(1f))
            if (player.isBuffering) Label("Cargando…", color = OndaraTheme.colors.accent) else player.error?.let { Label(it, color = OndaraTheme.colors.error) }
            Spacer(Modifier.weight(1f))
            Text(formatPosition(duration), style = OndaraTheme.type.mono, color = if (light) Color.White else foreground)
        }
    }
}
