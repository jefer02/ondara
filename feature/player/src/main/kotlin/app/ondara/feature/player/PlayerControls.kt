package app.ondara.feature.player

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import app.ondara.core.designsystem.component.*
import app.ondara.core.designsystem.icon.Icon
import app.ondara.core.designsystem.icon.OndaraIcons
import app.ondara.core.designsystem.theme.OndaraTheme
import app.ondara.core.model.PlayerState
import app.ondara.core.model.RepeatMode
import app.ondara.core.model.Song

/** Metadatos de altura constante; O(n) al medir los n caracteres. */
@Composable
internal fun PlayerMetadata(song: Song, modifier: Modifier = Modifier, foreground: Color = OndaraTheme.colors.onBackground) {
    Column(modifier) {
        PlayerSingleLine(song.title, OndaraTheme.type.title, foreground)
        PlayerSingleLine(song.artistLine, OndaraTheme.type.body, foreground)
    }
}

/** Una línea, desplazamiento lento o elipsis con movimiento reducido. O(n) al medir. */
@Composable
internal fun PlayerSingleLine(text: String, style: TextStyle, color: Color, centered: Boolean = false) {
    MarqueeText(text, style.copy(color = color),
        Modifier.fillMaxWidth().height(with(LocalDensity.current) { style.lineHeight.toDp() }),
        alignment = if (centered) Alignment.Center else Alignment.CenterStart)
}

/** Transporte único para todos los layouts; O(1), los eventos pertenecen al ViewModel. */
@Composable
internal fun PlayerTransport(
    player: PlayerState, onEvent: (PlayerEvent) -> Unit, playModifier: Modifier = Modifier,
    foreground: Color = OndaraTheme.colors.onBackground, light: Boolean = false,
) {
    val colors = OndaraTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = OndaraTheme.spacing.sm),
        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        OndaraIconButton(OndaraIcons.Shuffle, "Aleatorio", { onEvent(PlayerEvent.ToggleShuffle) },
            tint = if (player.shuffle) colors.accent else foreground)
        OndaraIconButton(OndaraIcons.Previous, "Anterior", { onEvent(PlayerEvent.Previous) }, tint = foreground)
        PlayPauseButton(player.isPlaying, { onEvent(PlayerEvent.TogglePlay) }, playModifier,
            size = OndaraTheme.spacing.touch + OndaraTheme.spacing.xxl,
            container = if (light) Color.White else colors.accent, content = if (light) Color.Black else colors.onAccent)
        OndaraIconButton(OndaraIcons.Next, "Siguiente", { onEvent(PlayerEvent.Next) }, tint = foreground, enabled = player.hasNext)
        OndaraIconButton(if (player.repeatMode == RepeatMode.ONE) OndaraIcons.RepeatOne else OndaraIcons.Repeat,
            "Repetir", { onEvent(PlayerEvent.CycleRepeat) },
            tint = if (player.repeatMode == RepeatMode.OFF) foreground else colors.accent)
    }
}

/** Acciones comunes sobre la canción real; O(1). */
@Composable
internal fun PlayerBottomActions(song: Song, onPanel: (String) -> Unit, foreground: Color = OndaraTheme.colors.onBackground) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        OndaraIconButton(OndaraIcons.Cast, "Dispositivos de audio", { openAudioOutput(context) }, tint = foreground)
        OndaraIconButton(OndaraIcons.Timer, "Temporizador", { onPanel("Temporizador") }, tint = foreground)
        OndaraIconButton(OndaraIcons.Share, "Compartir canción", {
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "https://music.youtube.com/watch?v=${song.id}")
            }, "Compartir canción"))
        }, tint = foreground)
        OndaraIconButton(OndaraIcons.Queue, "Cola", { onPanel("Cola") }, tint = foreground)
    }
}

/** Indica que hay contenido debajo del primer viewport. */
@Composable
internal fun PlayerScrollHint(foreground: Color) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Icon(OndaraIcons.ChevronDown, null, tint = foreground.copy(alpha = .55f), size = OndaraTheme.spacing.lg)
    }
}
