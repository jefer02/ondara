package app.ondara.core.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.ondara.core.designsystem.component.Hairline
import app.ondara.core.designsystem.component.Label
import app.ondara.core.designsystem.component.OndaraIconButton
import app.ondara.core.designsystem.component.SectionTitle
import app.ondara.core.designsystem.component.Skeleton
import app.ondara.core.designsystem.component.Text
import app.ondara.core.designsystem.component.pressable
import app.ondara.core.designsystem.component.staggeredEntry
import app.ondara.core.designsystem.icon.Icon
import app.ondara.core.designsystem.icon.OndaraIcons
import app.ondara.core.designsystem.theme.ListStyle
import app.ondara.core.designsystem.theme.OndaraTheme
import app.ondara.core.model.HomeShelf
import app.ondara.core.model.MusicItem
import app.ondara.core.model.Song

/** Canción en curso, para que cualquier fila sepa resaltarse. */
@Immutable
data class NowPlaying(val songId: String? = null, val isPlaying: Boolean = false)

val LocalNowPlaying = staticCompositionLocalOf { NowPlaying() }

/** "3:45" / "1:02:03". */
fun formatDuration(seconds: Int): String {
    if (seconds <= 0) return ""
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatPosition(ms: Long): String = formatDuration((ms / 1000).toInt()).ifEmpty { "0:00" }

/** Barras animadas que marcan la fila que está sonando. */
@Composable
fun PlayingBars(modifier: Modifier = Modifier, animate: Boolean = true, color: Color = OndaraTheme.colors.accent) {
    val transition = rememberInfiniteTransition(label = "bars")
    val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "phase")
    val moving = animate && !OndaraTheme.motion.reduced
    Canvas(modifier.size(18.dp)) {
        val barW = size.width / 5f
        listOf(0.9f, 0.4f, 0.7f).forEachIndexed { i, base ->
            val level = if (moving) (base + (if (i % 2 == 0) phase else 1f - phase) * 0.6f).rem(1f).coerceIn(0.2f, 1f) else base
            val h = size.height * level
            drawRoundRect(color, Offset(i * barW * 2f, size.height - h), Size(barW, h), CornerRadius(barW / 2))
        }
    }
}

/**
 * Fila de canción. Su contenedor depende de `ThemeSpec.list`: plana, con separador fino (Cupertino,
 * Mono) o en tarjeta (Vinilo).
 *
 * @param entryIndex si no es null, la fila entra escalonada (30 ms por elemento) la primera vez.
 */
@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trackNumber: Int? = null,
    entryIndex: Int? = null,
    onMore: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = OndaraTheme.colors
    val spacing = OndaraTheme.spacing
    val style = OndaraTheme.spec.list
    val now = LocalNowPlaying.current
    val current = now.songId == song.id
    val card = style == ListStyle.CARDS
    Column(
        modifier
            .staggeredEntry(entryIndex ?: 0, enabled = entryIndex != null)
            .fillMaxWidth()
            .padding(horizontal = if (card) spacing.md else 0.dp, vertical = if (card) 3.dp else 0.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (card) Modifier.clip(OndaraTheme.shapes.medium).background(colors.surface) else Modifier)
                .pressable(onLongClick = onMore, onClick = onClick)
                .semantics { if (current) stateDescription = "Reproduciendo" }
                .padding(start = if (card) spacing.md else spacing.screen, end = spacing.xs, top = spacing.sm, bottom = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (trackNumber != null) {
                Box(Modifier.width(32.dp), contentAlignment = Alignment.CenterStart) {
                    if (current) PlayingBars(animate = now.isPlaying) else Text("$trackNumber", style = OndaraTheme.type.mono, color = colors.onMuted)
                }
            } else {
                Box {
                    Artwork(song.thumbnailUrl, Modifier.size(52.dp), sizePx = 160, playing = current)
                    if (current) {
                        Box(Modifier.matchParentSize().clip(OndaraTheme.shapes.small).background(colors.background.copy(alpha = 0.6f)), Alignment.Center) {
                            PlayingBars(animate = now.isPlaying)
                        }
                    }
                }
                Spacer(Modifier.width(spacing.md))
            }
            Column(Modifier.weight(1f)) {
                Text(song.title, style = OndaraTheme.type.headline, color = if (current) colors.accent else colors.onBackground, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (song.explicit) {
                        Icon(OndaraIcons.Explicit, "Explícita", tint = colors.onMuted, size = 14.dp)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(song.artistLine, style = OndaraTheme.type.caption, color = colors.onMuted, maxLines = 1)
                }
            }
            if (song.durationSec > 0) {
                Text(formatDuration(song.durationSec), Modifier.padding(horizontal = spacing.sm), style = OndaraTheme.type.mono, color = colors.onMuted)
            }
            trailing?.invoke()
            if (onMore != null) OndaraIconButton(OndaraIcons.More, "Más opciones de ${song.title}", onMore, tint = colors.onMuted, size = 20.dp)
        }
        if (style == ListStyle.SEPARATORS) Hairline(startIndent = if (trackNumber != null) spacing.screen else spacing.screen + 64.dp)
    }
}

/** Tarjeta cuadrada de carrusel (álbum, playlist, canción) o circular (artista). Los mixes llevan portada generada. */
@Composable
fun MusicCard(item: MusicItem, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 148.dp, onLongClick: (() -> Unit)? = null) {
    val (title, subtitle, image) = item.display()
    val artist = item is MusicItem.ArtistItem
    Column(
        modifier.width(size).pressable(onClick = onClick, onLongClick = onLongClick),
        horizontalAlignment = if (artist) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        if (item is MusicItem.PlaylistItem) {
            PlaylistCover(item.playlist, Modifier.size(size))
        } else {
            Artwork(image, Modifier.size(size), shape = if (artist) CircleShape else OndaraTheme.shapes.artwork, sizePx = 360)
        }
        Spacer(Modifier.height(OndaraTheme.spacing.sm))
        val generated = item is MusicItem.PlaylistItem && app.ondara.core.model.MixIds.isVirtual(item.playlist.id)
        if (!generated) Text(title, style = OndaraTheme.type.bodyStrong, maxLines = 1, textAlign = if (artist) TextAlign.Center else null)
        if (subtitle.isNotEmpty()) {
            Text(subtitle, style = OndaraTheme.type.caption, color = OndaraTheme.colors.onMuted, maxLines = 1)
        }
    }
}

/** Fila genérica para resultados de búsqueda de cualquier tipo. */
@Composable
fun MusicItemRow(item: MusicItem, onClick: () -> Unit, modifier: Modifier = Modifier, onMore: (() -> Unit)? = null) {
    if (item is MusicItem.SongItem) return SongRow(item.song, onClick, modifier, onMore = onMore)
    val (title, subtitle, image) = item.display()
    val spacing = OndaraTheme.spacing
    Row(
        modifier.fillMaxWidth().pressable(onClick = onClick).padding(horizontal = spacing.screen, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(image, Modifier.size(56.dp), shape = if (item is MusicItem.ArtistItem) CircleShape else OndaraTheme.shapes.small, sizePx = 160)
        Spacer(Modifier.width(spacing.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = OndaraTheme.type.headline, maxLines = 1)
            Label(
                when (item) {
                    is MusicItem.AlbumItem -> "Álbum"
                    is MusicItem.ArtistItem -> "Artista"
                    is MusicItem.PlaylistItem -> "Playlist"
                    is MusicItem.SongItem -> "Canción"
                } + if (subtitle.isNotEmpty()) " · $subtitle" else "",
            )
        }
        Icon(OndaraIcons.ChevronRight, null, tint = OndaraTheme.colors.onMuted, size = 18.dp)
    }
}

private fun MusicItem.display(): Triple<String, String, String?> = when (this) {
    is MusicItem.SongItem -> Triple(song.title, song.artistLine, song.thumbnailUrl)
    is MusicItem.AlbumItem -> Triple(album.title, listOfNotNull(album.artists.joinToString { it.name }.takeIf { it.isNotEmpty() }, album.year?.toString()).joinToString(" · "), album.thumbnailUrl)
    is MusicItem.ArtistItem -> Triple(artist.name, artist.subscribers.orEmpty(), artist.thumbnailUrl)
    is MusicItem.PlaylistItem -> Triple(playlist.title, playlist.author.orEmpty(), playlist.thumbnailUrl)
}

/** Estantería horizontal con título. */
@Composable
fun Shelf(shelf: HomeShelf, onItem: (MusicItem) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(bottom = OndaraTheme.spacing.xl)) {
        if (shelf.title.isNotEmpty()) SectionTitle(shelf.title)
        LazyRow(
            contentPadding = PaddingValues(horizontal = OndaraTheme.spacing.screen),
            horizontalArrangement = Arrangement.spacedBy(OndaraTheme.spacing.md),
        ) {
            items(shelf.items, key = { it.key }, contentType = { it::class }) { item -> MusicCard(item, { onItem(item) }) }
        }
    }
}

@Composable
fun ShelfSkeleton(modifier: Modifier = Modifier) {
    Column(modifier.padding(bottom = OndaraTheme.spacing.xl)) {
        Skeleton(Modifier.padding(horizontal = OndaraTheme.spacing.screen, vertical = OndaraTheme.spacing.md).size(170.dp, 22.dp))
        Row(Modifier.padding(start = OndaraTheme.spacing.screen), horizontalArrangement = Arrangement.spacedBy(OndaraTheme.spacing.md)) {
            repeat(3) {
                Column {
                    Skeleton(Modifier.size(148.dp), OndaraTheme.shapes.artwork)
                    Skeleton(Modifier.padding(top = 10.dp).size(110.dp, 14.dp))
                }
            }
        }
    }
}

@Composable
fun SongRowSkeleton(modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = OndaraTheme.spacing.screen, vertical = OndaraTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Skeleton(Modifier.size(52.dp))
        Spacer(Modifier.width(OndaraTheme.spacing.md))
        Column {
            Skeleton(Modifier.size(190.dp, 15.dp))
            Skeleton(Modifier.padding(top = 8.dp).size(120.dp, 12.dp))
        }
    }
}

/** Cabecera con portada grande y *parallax*: la imagen se desplaza a la mitad de velocidad que la lista. */
@Composable
fun ParallaxHeader(
    imageUrl: String?,
    title: String,
    subtitle: String,
    scrollOffset: () -> Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    val colors = OndaraTheme.colors
    Box(modifier.fillMaxWidth()) {
        Artwork(
            imageUrl,
            Modifier
                .fillMaxWidth()
                .height(340.dp)
                .parallax(scrollOffset),
            shape = androidx.compose.ui.graphics.RectangleShape,
            sizePx = 720,
        )
        Box(
            Modifier.fillMaxWidth().height(340.dp).background(
                androidx.compose.ui.graphics.Brush.verticalGradient(0f to colors.background.copy(alpha = 0.25f), 0.55f to Color.Transparent, 1f to colors.background),
            ),
        )
        Column(Modifier.fillMaxWidth().padding(top = 250.dp).padding(horizontal = OndaraTheme.spacing.screen)) {
            Text(title, style = OndaraTheme.type.hero, maxLines = 3)
            if (subtitle.isNotEmpty()) Text(subtitle, style = OndaraTheme.type.body, color = colors.onMuted, maxLines = 2)
            Spacer(Modifier.height(OndaraTheme.spacing.lg))
            content()
        }
    }
}

private fun Modifier.parallax(scrollOffset: () -> Float): Modifier = graphicsLayer {
    val offset = scrollOffset()
    translationY = offset * 0.5f
    alpha = (1f - offset / 900f).coerceIn(0f, 1f)
    // Al tirar hacia abajo (sobre-scroll) la imagen crece un poco.
    val scale = 1f + (-offset).coerceAtLeast(0f) / 1200f
    scaleX = scale
    scaleY = scale
}
