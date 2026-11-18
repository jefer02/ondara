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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import app.ondara.core.designsystem.component.*
import app.ondara.core.designsystem.icon.HandDrawnIcons
import app.ondara.core.designsystem.icon.Icon
import app.ondara.core.designsystem.icon.OndaraIcons
import app.ondara.core.designsystem.theme.OndaraTheme
import app.ondara.core.model.Mood
import app.ondara.core.ui.*
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
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { event(ProfileEvent.Avatar(it)) } }
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
            else ProfileBento(state, { event(ProfileEvent.Play) }, onChooseAvatar, { event(ProfileEvent.Edit) }, bentoModifier)
        }
        item(key = "error", contentType = "error") { ErrorMessage(state.error) }
    }
    if (state.editing && state.photo == null) OndaraDialog("Editar perfil", { event(ProfileEvent.CancelEdit) }, "Guardar", { event(ProfileEvent.SaveName) }) {
        OndaraTextField(state.name, { event(ProfileEvent.Name(it)) }, placeholder = "Nombre")
        Avatar(ProfileBadge(state.settings), Modifier.pressable(onClickLabel = "Cambiar foto", onClick = onChooseAvatar), size = 64.dp)
        OndaraButton("Cambiar foto", onChooseAvatar)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Efecto píxel")
            OndaraSwitch(state.settings.avatarPixelEffect, { event(ProfileEvent.PixelEffect(it)) },
                Modifier.semantics { contentDescription = "Efecto píxel" })
        }
        if (state.settings.avatarPixelEffect) {
            Text("Tamaño del píxel · ${state.settings.avatarPixelBlocks}")
            OndaraValueSlider(state.settings.avatarPixelBlocks.toFloat(), { event(ProfileEvent.PixelBlocks(it.roundToInt())) },
                32f..64f, steps = 2, description = "Tamaño del píxel")
        }
        OndaraButton("Usar iniciales", { event(ProfileEvent.Initials) }, style = ButtonStyle.GHOST)
        ErrorMessage(state.error)
    }
    state.photo?.let { CropProfilePhoto(it, { event(ProfileEvent.CancelCrop) }, { crop -> event(ProfileEvent.SavePhoto(crop)) }) }
}

@Composable
private fun ProfileBento(
    state: ProfileUiState, onPlay: () -> Unit, onChooseAvatar: () -> Unit, onAddName: () -> Unit, modifier: Modifier = Modifier,
) {
    val colors = OndaraTheme.colors
    val spacing = OndaraTheme.spacing
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
            Avatar(ProfileBadge(state.settings), Modifier.pressable(onClickLabel = "Cambiar foto", onClick = onChooseAvatar)
                .semantics { contentDescription = "Cambiar foto" }, size = spacing.touch * 2)
            Column(Modifier.weight(1f)) {
                Text(state.settings.profileName.ifBlank { "Tu perfil" }, style = OndaraTheme.type.display, maxLines = 2)
                if (state.settings.profileName.isBlank()) {
                    OndaraButton("Agregar nombre", onAddName, style = ButtonStyle.GHOST)
                }
            }
            Icon(face, "Ánimo: ${moodLabel(state.stats.mood)}", size = spacing.touch)
        }
        Column(Modifier.fillMaxWidth().bentoMotion(1).pressable(enabled = state.stats.songOfTheDay != null, onClick = onPlay).heightIn(min = spacing.touch)) {
                Text("Canción del día", style = OndaraTheme.type.caption)
                Text(state.stats.songOfTheDay?.title ?: "Tu próxima favorita", style = OndaraTheme.type.title, maxLines = 3)
        }
        Row(
            Modifier.fillMaxWidth().bentoMotion(2).clip(OndaraTheme.shapes.large).background(colors.blockPrimary).padding(spacing.xl),
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
        Row(Modifier.fillMaxWidth().bentoMotion(3), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
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
