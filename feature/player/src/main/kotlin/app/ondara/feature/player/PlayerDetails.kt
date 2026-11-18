package app.ondara.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.ondara.core.designsystem.component.*
import app.ondara.core.designsystem.theme.ColorMath
import app.ondara.core.designsystem.theme.OndaraTheme
import app.ondara.core.model.Song
import app.ondara.core.ui.Nav
import app.ondara.core.ui.formatPosition
import app.ondara.core.ui.rememberArtworkPalette

/** La primera página mide exactamente el viewport, incluidos los insets que consume su contenido. */
@Composable
internal fun PlayerDetails(
    song: Song,
    lyrics: LyricsSlots,
    onLyrics: () -> Unit,
    onNavigate: (Nav) -> Unit,
    firstPage: @Composable () -> Unit,
) {
    val spacing = OndaraTheme.spacing
    val colors = OndaraTheme.colors
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val viewport = maxHeight
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().height(viewport).clipToBounds()) { firstPage() }
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(spacing.screen),
                verticalArrangement = Arrangement.spacedBy(spacing.xl),
            ) {
                val dominant = rememberArtworkPalette(song.thumbnailUrl)?.dominant ?: colors.surface
                // El fondo se ajusta también para el blanco compuesto al 55 %.
                val tint = Color(ColorMath.darkenedForWhite(dominant.toArgb(), .55f))
                Surface(color = tint, contentColor = Color.White, shape = OndaraTheme.shapes.large, hairline = false) {
                    Column(Modifier.fillMaxWidth().padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                        Text("Vista previa de la letra", style = OndaraTheme.type.title)
                        lyrics.preview(Modifier.fillMaxWidth())
                        Box(Modifier.heightIn(min = spacing.touch).pressable(onClick = onLyrics), contentAlignment = androidx.compose.ui.Alignment.CenterStart) {
                            Text("Ver letra completa", style = OndaraTheme.type.bodyStrong)
                        }
                    }
                }
                Surface(color = colors.surface, shape = OndaraTheme.shapes.large) {
                    Column(Modifier.fillMaxWidth().padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                        Text("Créditos", style = OndaraTheme.type.title)
                        Text(song.title)
                        Text(song.artistLine)
                        song.album?.let { Text(it.title) }
                        if (song.durationSec > 0) Text(formatPosition(song.durationSec * 1000L))
                        Text("YouTube Music", color = colors.onMuted)
                    }
                }
                song.artists.firstOrNull()?.let { artist ->
                    Surface(color = colors.surface, shape = OndaraTheme.shapes.large) {
                        Column(Modifier.fillMaxWidth().padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                            Text("Acerca del artista", style = OndaraTheme.type.title)
                            Text(artist.name)
                            artist.id?.let { id -> OndaraButton("Ver artista", { onNavigate(Nav.Artist(id)) }) }
                        }
                    }
                }
            }
        }
    }
}


@Composable
internal fun PlayerLyricsOverlay(lyrics: LyricsSlots, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().background(OndaraTheme.colors.background).statusBarsPadding().navigationBarsPadding()) {
        OndaraIconButton(app.ondara.core.designsystem.icon.OndaraIcons.ChevronDown, "Cerrar letra", onClose)
        lyrics.full(Modifier.weight(1f).fillMaxWidth())
    }
}
