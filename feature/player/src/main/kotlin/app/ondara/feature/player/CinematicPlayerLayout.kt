package app.ondara.feature.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.SurfaceTexture
import android.os.Build
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.ondara.core.designsystem.component.*
import app.ondara.core.designsystem.icon.Icon
import app.ondara.core.designsystem.icon.OndaraIcons
import app.ondara.core.designsystem.theme.OndaraTheme
import app.ondara.core.model.VideoStatus
import app.ondara.core.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

@OptIn(ExperimentalSharedTransitionApi::class)
@Suppress("ModifierParameter")
@Composable
internal fun CinematicPlayerLayout(
    state: PlayerUiState, onEvent: (PlayerEvent) -> Unit, positionFlow: Flow<Long>,
    onCollapse: () -> Unit, onNavigate: (Nav) -> Unit, lyrics: LyricsSlots,
    sharedScope: SharedTransitionScope, visibilityScope: AnimatedVisibilityScope, dragModifier: Modifier, active: Boolean,
) {
    val song = state.player.current ?: return
    val colors = OndaraTheme.colors
    val spacing = OndaraTheme.spacing
    val context = LocalContext.current
    val scroll = rememberScrollState()
    val enabled = active && state.settings.videoBackground && !OndaraTheme.motion.reduced
    var artworkExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(active, song.id) {
        artworkExpanded = false
        if (active) { delay(400); artworkExpanded = true }
    }
    val ready = artworkExpanded && enabled && state.video.status == VideoStatus.READY && state.video.songId == song.id
    var controls by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf("") }
    var added by remember(song.id) { mutableStateOf(false) }
    var wave by remember { mutableStateOf(0L to 0L) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val alpha by animateFloatAsState(if (controls || panel.isNotEmpty()) 1f else 0f, tween(250), label = "controles")
    val haptics = rememberHaptics()
    DisposableEffect(enabled) {
        onEvent(PlayerEvent.VideoActive(enabled))
        onDispose { onEvent(PlayerEvent.SetVideoMode(false)); onEvent(PlayerEvent.VideoActive(false)) }
    }
    DisposableEffect(state.video.videoMode) {
        val activity = context.activity()
        val previous = activity?.requestedOrientation
        if (state.video.videoMode) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        onDispose { if (previous != null) activity.requestedOrientation = previous }
    }
    LaunchedEffect(controls, landscape, state.video.videoMode) {
        if (controls && landscape && state.video.videoMode) { delay(3000); controls = false }
    }
    BackHandler(panel.isNotEmpty()) { panel = "" }
    BoxWithConstraints(Modifier.fillMaxSize().background(colors.background).pointerInput(Unit) {
        detectTapGestures(onTap = { controls = !controls }, onDoubleTap = {
            val delta = doubleTapDelta(it.x, size.width.toFloat())
            onEvent(PlayerEvent.SeekBy(delta)); wave = System.nanoTime() to delta
        })
    }) {
        val viewportHeight = maxHeight
        Box(Modifier.fillMaxSize()) {
            CinematicBackdrop(state, ready, sharedScope, visibilityScope, onEvent, Modifier.fillMaxSize().then(if (panel == "Letra") Modifier.blur(24.dp) else Modifier))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .35f), Color.Transparent, Color.Black.copy(alpha = .88f)))))
            SeekWaves(wave)
        }
        if (controls || alpha > 0f) {
            // El scroll tiene prioridad; el cierre por arrastre solo actúa en el extremo superior.
            Column(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }.verticalScroll(scroll).statusBarsPadding().navigationBarsPadding()) {
                Column(Modifier.heightIn(min = viewportHeight - 64.dp).fillMaxWidth().padding(horizontal = spacing.screen)) {
                    Row(Modifier.fillMaxWidth().then(if (scroll.value == 0) dragModifier else Modifier), verticalAlignment = Alignment.CenterVertically) {
                        OndaraIconButton(OndaraIcons.ChevronDown, "Minimizar reproductor", onCollapse, tint = Color.White)
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(state.player.context?.let { "Reproduciendo desde ${it.type}" } ?: "Reproduciendo", style = OndaraTheme.type.caption, color = Color.White)
                            Text(state.player.context?.title ?: song.album?.title.orEmpty(), style = OndaraTheme.type.bodyStrong, color = Color.White)
                        }
                        OndaraIconButton(OndaraIcons.More, "Más opciones", { panel = "Menú" }, tint = Color.White)
                    }
                    Spacer(Modifier.height((viewportHeight * .32f).coerceAtLeast(48.dp)).fillMaxWidth().then(if (scroll.value == 0) dragModifier else Modifier)
                        )
                    lyrics.currentLine(Modifier.fillMaxWidth().padding(vertical = spacing.lg))
                    if (ready) GlassSurface(
                        Modifier.pressable { onEvent(PlayerEvent.SetVideoMode(!state.video.videoMode)) },
                        shape = CircleShape, tint = Color.Black, contentColor = Color.White,
                    ) {
                        Row(Modifier.heightIn(min = spacing.touch).padding(horizontal = spacing.lg), verticalAlignment = Alignment.CenterVertically) {
                            Icon(OndaraIcons.Video, null, tint = Color.White, size = 24.dp)
                            Spacer(Modifier.width(spacing.sm))
                            Text(if (state.video.videoMode) "Cambiar a audio" else "Cambiar a vídeo", style = OndaraTheme.type.bodyStrong)
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = spacing.md), verticalAlignment = Alignment.CenterVertically) {
                        Artwork(song.thumbnailUrl, Modifier.size(52.dp), playing = true)
                        with(sharedScope) {
                            Column(Modifier.weight(1f).padding(horizontal = spacing.md).sharedBounds(rememberSharedContentState(KEY_TITLE), visibilityScope)) {
                                Text(song.title, style = OndaraTheme.type.title, color = Color.White, maxLines = 2)
                                Text(song.artistLine, style = OndaraTheme.type.body, color = Color.White.copy(alpha = .8f), maxLines = 1)
                            }
                        }
                        OndaraIconButton(OndaraIcons.Close, "No me interesa", { onEvent(PlayerEvent.NotInterested) }, tint = Color.White)
                        Crossfade(added, animationSpec = tween(250), label = "playlist") { confirmed ->
                            OndaraIconButton(if (confirmed) OndaraIcons.Check else OndaraIcons.Add, "Agregar a playlist", { panel = "Playlist" }, tint = Color.White)
                        }
                    }
                    Progress(state.player, positionFlow, { onEvent(PlayerEvent.SeekFraction(it)) }, light = true)
                    Row(Modifier.fillMaxWidth().padding(vertical = spacing.md), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            OndaraIconButton(OndaraIcons.Shuffle, "Aleatorio", { onEvent(PlayerEvent.ToggleShuffle) }, tint = if (state.player.shuffle) colors.accent else Color.White)
                            if (state.player.shuffle) Box(Modifier.size(4.dp).background(colors.accent, CircleShape))
                        }
                        OndaraIconButton(OndaraIcons.Previous, "Anterior", { onEvent(PlayerEvent.Previous) }, tint = Color.White, size = 32.dp)
                        with(sharedScope) {
                            PlayPauseButton(state.player.isPlaying, { onEvent(PlayerEvent.TogglePlay) },
                                Modifier.sharedBounds(rememberSharedContentState(KEY_PLAY), visibilityScope), size = 80.dp, container = Color.White, content = Color(0xFF141414))
                        }
                        OndaraIconButton(OndaraIcons.Next, "Siguiente", { onEvent(PlayerEvent.Next) }, tint = Color.White, size = 32.dp, enabled = state.player.hasNext)
                        OndaraIconButton(OndaraIcons.Timer, "Temporizador", { panel = "Temporizador" }, tint = Color.White)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OndaraIconButton(OndaraIcons.Cast, "Dispositivos de audio", { openAudioOutput(context) }, tint = Color.White)
                        Spacer(Modifier.weight(1f))
                        OndaraIconButton(OndaraIcons.Share, "Compartir canción", {
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "https://music.youtube.com/watch?v=${song.id}") }, "Compartir canción"))
                        }, tint = Color.White)
                        OndaraIconButton(OndaraIcons.Queue, "Cola", { panel = "Cola" }, tint = Color.White)
                    }
                }
                Surface(Modifier.fillMaxWidth().padding(spacing.screen), shape = OndaraTheme.shapes.large, color = colors.blockPrimary, contentColor = colors.onBlock, hairline = false) {
                    Column(Modifier.padding(spacing.lg)) {
                        Text("Vista previa de la letra", style = OndaraTheme.type.title)
                        lyrics.preview(Modifier.fillMaxWidth().padding(vertical = spacing.lg))
                        OndaraButton("Ver letra completa", { panel = "Letra" })
                    }
                }
                Column(Modifier.padding(spacing.screen), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                    Text("Créditos", style = OndaraTheme.type.title, color = Color.White)
                    Text("${song.title}\n${song.artistLine}\n${song.album?.title.orEmpty()}\n${formatPosition(state.player.durationMs)}\nYouTube Music", color = Color.White)
                    Surface(shape = OndaraTheme.shapes.large, hairline = false) {
                        Column(Modifier.fillMaxWidth().padding(spacing.lg)) {
                            Text("Acerca del artista", style = OndaraTheme.type.title)
                            Text(song.artists.firstOrNull()?.name ?: song.artistLine)
                            song.artists.firstOrNull { it.id != null }?.let { artist ->
                                OndaraButton("Ver artista", { onNavigate(Nav.Artist(requireNotNull(artist.id))) })
                            }
                        }
                    }
                }
            }
        }
        if (panel == "Cola") QueuePanel(state.player, onEvent) { panel = "" }
        if (panel == "Letra") {
            Column(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .8f)).statusBarsPadding().navigationBarsPadding()) {
                OndaraIconButton(OndaraIcons.Close, "Cerrar letra", { panel = "" }, tint = Color.White)
                lyrics.full(Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
    if (panel == "Temporizador") PlayerTimer(state.player, onEvent) { panel = "" }
    if (panel == "Menú") SongMenuSheet(song, { panel = "" }, { onNavigate(Nav.Artist(it)) }, { onNavigate(Nav.Album(it)) })
    if (panel == "Playlist") PlayerPlaylistPicker(song, { panel = "" }, onAdded = { added = true; haptics.confirm(); panel = "" })
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun CinematicBackdrop(
    state: PlayerUiState, ready: Boolean, sharedScope: SharedTransitionScope, visibilityScope: AnimatedVisibilityScope,
    onEvent: (PlayerEvent) -> Unit, modifier: Modifier = Modifier,
) {
    val colors = OndaraTheme.colors
    val reduced = OndaraTheme.motion.reduced
    val movement = remember { Animatable(0f) }
    LaunchedEffect(reduced) {
        if (reduced) movement.snapTo(0f) else while (true) {
            movement.animateTo(1f, tween(24_000, easing = LinearEasing))
            movement.animateTo(0f, tween(24_000, easing = LinearEasing))
        }
    }
    val videoAlpha by animateFloatAsState(if (ready) 1f else 0f, tween(400), label = "vídeo")
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val surface = remember { arrayOfNulls<Surface>(1) }
    DisposableEffect(Unit) { onDispose { onEvent(PlayerEvent.VideoSurface(null)); surface[0]?.release(); surface[0] = null } }
    Box(modifier.then(if (OndaraTheme.spec.ditherArtwork && state.settings.ditherVideo) Modifier.twoToneDither(colors.onBlock, colors.blockLight, cellDp = 2.5f) else Modifier)) {
        MeshGradientBackground(OndaraTheme.artwork, Modifier.fillMaxSize())
        with(sharedScope) {
            Box(Modifier.fillMaxSize().sharedElement(rememberSharedContentState(KEY_ARTWORK), visibilityScope)) {
                Crossfade(state.player.current, animationSpec = tween(400), label = "portada") { song ->
                    Artwork(song?.thumbnailUrl, Modifier.fillMaxSize().graphicsLayer {
                        scaleX = 1f + movement.value * .12f; scaleY = scaleX
                        translationX = movement.value * 12.dp.toPx(); translationY = -movement.value * 12.dp.toPx()
                    }, shape = RectangleShape, playing = true, sizePx = 1080)
                }
            }
        }
        AndroidView(factory = { context ->
            TextureView(context).apply {
                isOpaque = false
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                        surface[0]?.release(); surface[0] = Surface(texture); onEvent(PlayerEvent.VideoSurface(surface[0]))
                    }
                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        onEvent(PlayerEvent.VideoSurface(null)); surface[0]?.release(); surface[0] = null
                        return true
                    }
                }
            }
        }, modifier = Modifier.fillMaxSize().onSizeChanged { viewSize = it }.graphicsLayer {
            alpha = videoAlpha
            val scale = centerCropScale(state.video.width, state.video.height, viewSize.width, viewSize.height)
            scaleX = scale.first; scaleY = scale.second
        })
    }
}

@Composable
private fun SeekWaves(wave: Pair<Long, Long>) {
    val progress = remember { Animatable(1f) }
    val reduced = OndaraTheme.motion.reduced
    LaunchedEffect(wave) { if (wave.first != 0L) { progress.snapTo(0f); progress.animateTo(1f, tween(if (reduced) 150 else 650)) } }
    Box(Modifier.fillMaxSize(), contentAlignment = if (wave.second < 0) Alignment.CenterStart else Alignment.CenterEnd) {
        Canvas(Modifier.fillMaxSize()) {
            if (progress.value < 1f) {
                val origin = center.copy(x = if (wave.second < 0) 0f else size.width)
                repeat(3) { drawCircle(Color.White.copy(alpha = (1f - progress.value) * .45f), (progress.value + it * .15f) * size.width * .6f, origin, style = Stroke(3.dp.toPx())) }
            }
        }
        if (progress.value < 1f) Text("10 s", Modifier.padding(48.dp), color = Color.White, style = OndaraTheme.type.title)
    }
}

/** Busca la actividad entre envoltorios de contexto. O(n) en envoltorios. */
private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

private fun openAudioOutput(context: Context) {
    val actions = buildList {
        if (Build.VERSION.SDK_INT >= 34) add("android.settings.MEDIA_ROUTE")
        add("android.settings.panel.action.MEDIA_OUTPUT")
        add("com.android.settings.panel.action.MEDIA_OUTPUT")
    }
    for (action in actions) {
        try { context.startActivity(Intent(action).putExtra("package_name", context.packageName)); return } catch (_: Exception) { /* Prueba el siguiente panel del sistema. */ }
    }
    Toast.makeText(context, "No hay selector de salida de audio disponible", Toast.LENGTH_SHORT).show()
}
