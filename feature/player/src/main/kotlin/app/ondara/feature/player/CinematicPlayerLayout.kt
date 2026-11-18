package app.ondara.feature.player

import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import app.ondara.core.designsystem.component.*
import app.ondara.core.designsystem.icon.OndaraIcons
import app.ondara.core.designsystem.theme.LocalContentColor
import app.ondara.core.designsystem.theme.OndaraTheme
import app.ondara.core.ui.*
import kotlinx.coroutines.flow.Flow

@OptIn(ExperimentalSharedTransitionApi::class)
@Suppress("ModifierParameter")
@Composable
internal fun CinematicPlayerLayout(
    state: PlayerUiState, onEvent: (PlayerEvent) -> Unit, positionFlow: Flow<Long>,
    onCollapse: () -> Unit, onNavigate: (Nav) -> Unit, lyrics: LyricsSlots,
    sharedScope: SharedTransitionScope, visibilityScope: AnimatedVisibilityScope, dragModifier: Modifier,
) {
    val song = state.player.current ?: return
    val colors = OndaraTheme.colors
    val spacing = OndaraTheme.spacing
    var panel by remember { mutableStateOf("") }
    var added by remember(song.id) { mutableStateOf(false) }
    var wave by remember { mutableStateOf(0L to 0L) }
    val haptics = rememberHaptics()
    val gestures = rememberUpdatedState(onEvent)
    val offer = state.videoOffer?.takeIf { it.songId == song.id }
    val compact = LocalConfiguration.current.screenHeightDp.dp < spacing.touch * 10
    BackHandler(panel.isNotEmpty()) { panel = "" }
    Box(Modifier.fillMaxSize().background(colors.background).pointerInput(Unit) {
        detectTapGestures(onDoubleTap = {
            val delta = doubleTapDelta(it.x, size.width.toFloat())
            gestures.value(PlayerEvent.SeekBy(delta)); wave = System.nanoTime() to delta
        })
    }) {
        Box(Modifier.fillMaxSize()) {
            CanvasBackground(
                state.canvas, state.canvasSurfaces, song.thumbnailUrl,
                { slot, surface -> onEvent(PlayerEvent.CanvasSurface(slot, surface)) },
                Modifier.fillMaxSize(),
                dither = offer?.enabled != true && OndaraTheme.spec.ditherArtwork && state.settings.ditherVideo,
            )
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to Color.Black.copy(alpha = .55f),
                .25f to Color.Transparent,
                .58f to Color.Black.copy(alpha = .58f),
                1f to Color.Black.copy(alpha = .88f),
            )))
            SeekWaves(wave)
        }
        PlayerDetails(song, lyrics, { panel = "Letra" }, onNavigate) {
            Column(Modifier.fillMaxSize()
                .statusBarsPadding().navigationBarsPadding().padding(horizontal = spacing.screen)) {
                Row(Modifier.fillMaxWidth().then(dragModifier), verticalAlignment = Alignment.CenterVertically) {
                    OndaraIconButton(OndaraIcons.ChevronDown, "Minimizar reproductor", onCollapse, tint = Color.White)
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.player.context?.let { "Reproduciendo desde ${it.type}" } ?: "Reproduciendo",
                            style = OndaraTheme.type.caption, color = Color.White, maxLines = 1)
                        PlayerSingleLine(state.player.context?.title ?: song.album?.title.orEmpty(), OndaraTheme.type.bodyStrong, Color.White, centered = true)
                    }
                    OndaraIconButton(OndaraIcons.More, "Más opciones", { panel = "Menú" }, tint = Color.White)
                }
                Spacer(Modifier.weight(1f).fillMaxWidth())
                if (!compact) CinematicLyricBox(lyrics.currentLine)
                if (offer != null) VideoSwitch(offer.enabled, { onEvent(PlayerEvent.ToggleVideo) }, Modifier.align(Alignment.CenterHorizontally))
                Row(Modifier.fillMaxWidth().padding(top = spacing.md), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(song.thumbnailUrl, Modifier.size(spacing.touch), playing = true)
                    with(sharedScope) {
                        PlayerMetadata(song, Modifier.weight(1f).padding(horizontal = spacing.md)
                            .sharedBounds(rememberSharedContentState(KEY_TITLE), visibilityScope), Color.White)
                    }
                    OndaraIconButton(OndaraIcons.Close, "No me interesa", { onEvent(PlayerEvent.NotInterested) }, tint = Color.White)
                    OndaraIconButton(if (added) OndaraIcons.Check else OndaraIcons.Add,
                        "Agregar a playlist", { panel = "Playlist" }, tint = Color.White)
                }
                Progress(state.player, positionFlow, { onEvent(PlayerEvent.SeekFraction(it)) }, light = true)
                with(sharedScope) {
                    PlayerTransport(state.player, onEvent,
                        Modifier.sharedBounds(rememberSharedContentState(KEY_PLAY), visibilityScope), Color.White, light = true)
                }
                if (!compact) {
                    PlayerScrollHint(Color.White)
                    PlayerBottomActions(song, { panel = it }, Color.White)
                    Spacer(Modifier.height(spacing.lg))
                }
            }
        }
        if (panel == "Cola") QueuePanel(state.player, onEvent) { panel = "" }
        if (panel == "Letra") PlayerLyricsOverlay(lyrics) { panel = "" }
    }
    if (panel == "Temporizador") PlayerTimer(state.player, onEvent) { panel = "" }
    if (panel == "Menú") SongMenuSheet(song, { panel = "" }, { onNavigate(Nav.Artist(it)) }, { onNavigate(Nav.Album(it)) })
    if (panel == "Playlist") PlayerPlaylistPicker(song, { panel = "" }, onAdded = { added = true; haptics.confirm(); panel = "" })
}

@Composable
private fun VideoSwitch(video: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.heightIn(min = OndaraTheme.spacing.touch).pressable(haptic = true, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = OndaraTheme.shapes.control, color = Color.Black.copy(alpha = .68f), contentColor = Color.White, hairline = false) {
            Text(
                if (video) "Cambiar a audio" else "Cambiar a video",
                Modifier.padding(horizontal = OndaraTheme.spacing.lg, vertical = OndaraTheme.spacing.sm),
                style = OndaraTheme.type.bodyStrong,
            )
        }
    }
}

@Composable
private fun SeekWaves(wave: Pair<Long, Long>) {
    val progress = remember { Animatable(1f) }
    val motion = OndaraTheme.motion
    LaunchedEffect(wave) { if (wave.first != 0L) { progress.snapTo(0f); progress.animateTo(1f, motion.spring()) } }
    Box(Modifier.fillMaxSize(), contentAlignment = if (wave.second < 0) Alignment.CenterStart else Alignment.CenterEnd) {
        Canvas(Modifier.fillMaxSize()) {
            if (progress.value < 1f) {
                val origin = center.copy(x = if (wave.second < 0) 0f else size.width)
                repeat(3) {
                    drawCircle(Color.White.copy(alpha = (1f - progress.value) * .45f),
                        (progress.value + it * .15f) * size.width * .6f, origin, style = Stroke(3.dp.toPx()))
                }
            }
        }
        if (wave.first != 0L) Text("10 s", Modifier.padding(OndaraTheme.spacing.touch)
            .graphicsLayer { alpha = (1f - progress.value).coerceIn(0f, 1f) }, color = Color.White, style = OndaraTheme.type.title)
    }
}

internal fun openAudioOutput(context: Context) {
    val actions = buildList {
        if (Build.VERSION.SDK_INT >= 34) add("android.settings.MEDIA_ROUTE")
        add("android.settings.panel.action.MEDIA_OUTPUT")
        add("com.android.settings.panel.action.MEDIA_OUTPUT")
    }
    for (action in actions) {
        try {
            context.startActivity(Intent(action).putExtra("package_name", context.packageName))
            return
        } catch (_: Exception) { /* Prueba el siguiente panel del sistema. */ }
    }
    Toast.makeText(context, "No hay selector de salida de audio disponible", Toast.LENGTH_SHORT).show()
}

/** Reserva tres líneas transparentes aunque la canción esté entre versos. */
@Composable
internal fun CinematicLyricBox(currentLine: @Composable (Modifier) -> Unit) {
    val height = with(androidx.compose.ui.platform.LocalDensity.current) { OndaraTheme.type.lyric.lineHeight.toDp() * 3 }
    Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.BottomStart) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            currentLine(Modifier.fillMaxSize())
        }
    }
}
