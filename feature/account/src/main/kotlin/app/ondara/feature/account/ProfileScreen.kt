package app.ondara.feature.account

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.palette.graphics.Palette
import app.ondara.core.designsystem.component.*
import app.ondara.core.designsystem.icon.HandDrawnIcons
import app.ondara.core.designsystem.icon.Icon
import app.ondara.core.designsystem.icon.OndaraIcons
import app.ondara.core.designsystem.theme.ColorMath
import app.ondara.core.designsystem.theme.OndaraTheme
import app.ondara.core.model.Mood
import app.ondara.core.ui.*
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ProfileScreen(
    onNavigate: (Nav) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val event = viewModel::onEvent
    val layer = rememberGraphicsLayer()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { event(ProfileEvent.Avatar(it)) }
    CollectEffects(viewModel.effects) { effect ->
        when (effect) {
            ProfileEffect.Back -> onNavigate(Nav.Back)
            ProfileEffect.Share -> try {
                val image = layer.toImageBitmap()
                val file = withContext(Dispatchers.IO) {
                    File(context.cacheDir, "share").apply { mkdirs() }.let { directory ->
                        File(directory, "perfil.png").also { target ->
                            target.outputStream().use { check(image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)) }
                        }
                    }
                }
                val uri = FileProvider.getUriForFile(context, context.packageName + ".share", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = android.content.ClipData.newRawUri("Perfil", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "Compartir perfil"))
            } catch (error: Exception) { event(ProfileEvent.Error("No se pudo compartir: ${error.message}")) }
        }
    }
    ProfileContent(state, event, modifier,
        bentoModifier = Modifier.drawWithContent { layer.record { this@drawWithContent.drawContent() }; drawLayer(layer) },
        onChooseAvatar = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
    )
}

@Composable
internal fun ProfileContent(
    state: ProfileUiState, onEvent: (ProfileEvent) -> Unit, modifier: Modifier = Modifier,
    bentoModifier: Modifier = Modifier, onChooseAvatar: () -> Unit = {},
) {
    val event = onEvent
    ScreenScaffold("Perfil", modifier, onBack = { event(ProfileEvent.Back) }, actions = {
        OndaraIconButton(OndaraIcons.Edit, "Editar perfil", { event(ProfileEvent.Edit) })
        OndaraIconButton(OndaraIcons.Share, "Compartir perfil", { event(ProfileEvent.Share) }, enabled = !state.loading)
    }) {
        item(key = "bento", contentType = "perfil") {
            if (state.loading) Skeleton(Modifier.fillMaxWidth().height(OndaraTheme.spacing.touch * 8))
            else ProfileBento(state, { event(ProfileEvent.Play) }, bentoModifier)
        }
        item(key = "error", contentType = "error") { ErrorMessage(state.error) }
    }
    if (state.editing) OndaraDialog("Editar perfil", { event(ProfileEvent.CancelEdit) }, "Guardar", { event(ProfileEvent.SaveName) }) {
        OndaraTextField(state.name, { event(ProfileEvent.Name(it)) }, placeholder = "Nombre")
        Avatar(state.settings.profileName, state.settings.avatarPath, size = 64.dp)
        OndaraButton("Elegir foto", { onChooseAvatar() })
        OndaraButton("Usar iniciales", { event(ProfileEvent.Initials) }, style = ButtonStyle.GHOST)
        ErrorMessage(state.error)
    }
}

/** Extrae hasta tres colores de portadas a 96 px fuera del hilo principal. O(k·96²), k ≤ 3. */
@Composable
private fun profilePalette(artworks: List<String>): List<Color> {
    val context = LocalContext.current.applicationContext
    val palette by produceState<List<Color>>(emptyList(), artworks) {
        value = withContext(Dispatchers.IO) {
            artworks.take(3).flatMap { url ->
                runCatching {
                    val request = ImageRequest.Builder(context).data(url.thumbnail(96)).size(96).allowHardware(false).build()
                    val bitmap = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
                    bitmap?.let {
                        val swatches = Palette.from(it).maximumColorCount(8).generate()
                        listOfNotNull(swatches.dominantSwatch, swatches.lightMutedSwatch, swatches.darkMutedSwatch).map { swatch -> Color(swatch.rgb) }
                    }.orEmpty()
                }.getOrDefault(emptyList())
            }.distinct().take(3)
        }
    }
    return palette
}

@Composable
private fun ProfileBento(state: ProfileUiState, onPlay: () -> Unit, modifier: Modifier = Modifier) {
    val colors = OndaraTheme.colors
    val spacing = OndaraTheme.spacing
    val extracted = profilePalette(state.stats.topArtworks)
    val palette = extracted.takeIf { it.size >= 2 } ?: listOf(colors.blockLight, colors.blockMuted)
    val ordered = palette.sortedBy { ColorMath.luminance(it.toArgb()) }
    val face = when (state.stats.mood) {
        Mood.HAPPY -> HandDrawnIcons.FaceHappy
        Mood.CALM -> HandDrawnIcons.FaceCalm
        Mood.ENERGETIC -> HandDrawnIcons.FaceEnergetic
        Mood.MELANCHOLY -> HandDrawnIcons.FaceMelancholy
        Mood.NEUTRAL -> HandDrawnIcons.FaceNeutral
    }
    Column(
        modifier.fillMaxWidth().clip(OndaraTheme.shapes.large).background(colors.surface).padding(spacing.xl),
        verticalArrangement = Arrangement.spacedBy(spacing.lg),
    ) {
        Row(Modifier.fillMaxWidth().bentoMotion(0), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
            Text(state.settings.profileName.ifBlank { "Tu perfil" }, Modifier.weight(1f), style = OndaraTheme.type.display, maxLines = 2)
            Column(Modifier.weight(1.4f).pressable(enabled = state.stats.songOfTheDay != null, onClick = onPlay).heightIn(min = spacing.touch)) {
                Text("Canción del día", style = OndaraTheme.type.caption)
                Text(state.stats.songOfTheDay?.title ?: "Tu próxima favorita", style = OndaraTheme.type.title, maxLines = 3)
            }
            Icon(face, "Ánimo: ${moodLabel(state.stats.mood)}", size = 64.dp)
        }
        Row(
            Modifier.fillMaxWidth().bentoMotion(1).clip(OndaraTheme.shapes.large).background(colors.blockPrimary).padding(spacing.xl),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Minutos este mes", style = OndaraTheme.type.caption, color = colors.onBlock)
                Text(state.stats.minutesThisMonth.toString(), style = OndaraTheme.type.monoLarge, color = colors.onBlock)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text("Género top", style = OndaraTheme.type.caption, color = colors.onBlock)
                Text(state.stats.topGenre ?: "Por descubrir", style = OndaraTheme.type.title, color = colors.onBlock)
            }
        }
        Text("Tu paleta", style = OndaraTheme.type.caption)
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1.5f), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                palette.forEachIndexed { index, color ->
                    val foreground = Color(ColorMath.ensureContrast(ColorMath.onColor(color.toArgb()), color.toArgb()))
                    Box(
                        Modifier.fillMaxWidth().height(spacing.touch * 1.6f).bentoMotion(index + 2)
                            .clip(OndaraTheme.shapes.large).background(color).padding(spacing.lg), contentAlignment = Alignment.BottomStart,
                    ) {
                        Text(String.format(Locale.ROOT, "#%06X", color.toArgb() and 0xFFFFFF), style = OndaraTheme.type.code, color = foreground)
                    }
                }
            }
            Box(
                Modifier.weight(1f).aspectRatio(1f).bentoMotion(5).clip(OndaraTheme.shapes.small).background(colors.blockLight)
                    .semantics { contentDescription = state.stats.topArtist?.name ?: "Tu artista está por descubrir" },
                contentAlignment = Alignment.Center,
            ) {
                val image = state.stats.topArtist?.thumbnailUrl
                if (image != null) CompositionLocalProvider(LocalDitherArtwork provides false) {
                    Artwork(image, Modifier.fillMaxSize().twoToneDither(ordered.first(), ordered.last(), cellDp = 2.5f))
                }
                else Icon(OndaraIcons.Wave, null, tint = colors.onBlock, size = 64.dp)
            }
        }
        Row(Modifier.fillMaxWidth().bentoMotion(6), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
            Icon(HandDrawnIcons.Person, null, tint = colors.onMuted, size = spacing.touch)
            val joined = state.settings.joinedAtMs.takeIf { it > 0 } ?: state.firstPlay
            val date = remember(joined) { joined?.let { SimpleDateFormat("MMM yyyy", Locale.forLanguageTag("es")).format(Date(it)) } }
            Text(date?.let { "Escuchando desde $it" } ?: "Tu historia empieza aquí", Modifier.weight(1f), color = colors.onMuted, style = OndaraTheme.type.caption)
            if (state.accounts.youtube.isNotEmpty()) Icon(OndaraIcons.YouTube, "YouTube Music conectado")
            if (state.accounts.spotify != null) Icon(OndaraIcons.Spotify, "Spotify conectado")
        }
    }
}

private fun moodLabel(mood: Mood): String = when (mood) {
    Mood.HAPPY -> "alegre"
    Mood.CALM -> "tranquilo"
    Mood.ENERGETIC -> "enérgico"
    Mood.MELANCHOLY -> "melancólico"
    Mood.NEUTRAL -> "neutral"
}

/** Entrada de coste O(1) por bloque; inclinación según contacto, sin consumir el clic de reproducción. */
private fun Modifier.bentoMotion(index: Int): Modifier = composed {
    val motion = OndaraTheme.motion
    val entry = remember { Animatable(if (motion.reduced) 1f else 0f) }
    var targetX by remember { mutableFloatStateOf(0f) }
    var targetY by remember { mutableFloatStateOf(0f) }
    val x = animateFloatAsState(targetX, motion.spring(), label = "Inclinación X")
    val y = animateFloatAsState(targetY, motion.spring(), label = "Inclinación Y")
    LaunchedEffect(motion.reduced) {
        if (!motion.reduced) delay(index * 40L)
        entry.animateTo(1f, motion.spring())
    }
    this.pointerInput(motion.reduced) {
        if (!motion.reduced) awaitEachGesture {
            val first = awaitFirstDown(requireUnconsumed = false)
            val down = awaitLongPressOrCancellation(first.id) ?: return@awaitEachGesture
            targetX = ((down.position.y / size.height) - 0.5f) * -6f
            targetY = ((down.position.x / size.width) - 0.5f) * 6f
            try {
                do { val pressed = awaitPointerEvent().changes.any { it.pressed } } while (pressed)
            } finally { targetX = 0f; targetY = 0f }
        }
    }.graphicsLayer {
        alpha = entry.value
        translationY = (1f - entry.value) * 24.dp.toPx()
        rotationX = x.value
        rotationY = y.value
        cameraDistance = 12f * density
    }
}
