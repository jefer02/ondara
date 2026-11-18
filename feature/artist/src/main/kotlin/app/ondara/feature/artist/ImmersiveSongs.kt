package app.ondara.feature.artist

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import app.ondara.core.designsystem.component.*
import app.ondara.core.designsystem.icon.OndaraIcons
import app.ondara.core.designsystem.theme.LocalContentColor
import app.ondara.core.designsystem.theme.OndaraTheme
import app.ondara.core.model.Song
import app.ondara.core.ui.Artwork
import app.ondara.core.ui.LocalNowPlaying
import app.ondara.core.ui.PlayingBars

/** Fila inmersiva que conserva el contraste de la página y el menú de canción. */
@Composable
internal fun ImmersiveSongRow(song: Song, number: Int, onPlay: () -> Unit, onMore: () -> Unit) {
    val foreground = LocalContentColor.current
    val now = LocalNowPlaying.current
    val current = now.songId == song.id
    Row(
        Modifier.fillMaxWidth().pressable(onLongClick = onMore, onClick = onPlay)
            .semantics { if (current) stateDescription = "Reproduciendo" }
            .padding(horizontal = OndaraTheme.spacing.md, vertical = OndaraTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OndaraTheme.spacing.sm),
    ) {
        Box(Modifier.width(OndaraTheme.spacing.xxl), contentAlignment = Alignment.Center) {
            if (current) PlayingBars(animate = now.isPlaying, color = foreground)
            else Text("$number", style = OndaraTheme.type.mono, color = foreground.copy(alpha = 0.7f))
        }
        Artwork(song.thumbnailUrl, Modifier.size(OndaraTheme.spacing.touch), sizePx = 160, playing = current)
        Column(Modifier.weight(1f)) {
            Text(song.title, style = OndaraTheme.type.bodyStrong, color = foreground, maxLines = 1)
            Text(song.artistLine, style = OndaraTheme.type.caption, color = foreground.copy(alpha = 0.7f), maxLines = 1)
        }
        GlassIconButton(OndaraIcons.More, "Más opciones de ${song.title}", onMore, size = OndaraTheme.spacing.touch)
    }
}

/** Texto desplazable para descripciones extensas. */
@Composable
internal fun AboutSheet(title: String, text: String, onDismiss: () -> Unit) {
    OndaraSheet(onDismiss, title = title) {
        Text(text, Modifier.verticalScroll(rememberScrollState()).padding(OndaraTheme.spacing.screen))
    }
}
