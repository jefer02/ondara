package app.ondara.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.ondara.core.designsystem.component.ImmersiveHeroScaffold
import app.ondara.core.designsystem.component.Label
import app.ondara.core.designsystem.component.Text
import app.ondara.core.designsystem.component.pressable
import app.ondara.core.designsystem.icon.Icon
import app.ondara.core.designsystem.icon.OndaraIcons
import app.ondara.core.designsystem.theme.ColorMath
import app.ondara.core.designsystem.theme.OndaraTheme
import app.ondara.core.model.MixIds
import app.ondara.core.model.MusicItem
import app.ondara.core.model.Playlist
import app.ondara.core.model.PlaylistSource
import app.ondara.core.model.TopPick

/** Lo que el encabezado necesita saber del perfil para pintar el avatar. */
@Immutable
data class ProfileBadge(val name: String = "", val avatarPath: String = "", val hasAccounts: Boolean = false)

val LocalProfileBadge = staticCompositionLocalOf { ProfileBadge() }

/** Iniciales para el avatar generado: primeras letras de hasta dos palabras. O(n) sobre el nombre. */
fun initialsOf(name: String): String =
    name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercase() }

/** Avatar circular: foto elegida por el usuario o iniciales sobre el acento del tema. */
@Composable
fun Avatar(name: String, avatarPath: String, modifier: Modifier = Modifier, size: Dp = 36.dp) {
    val colors = OndaraTheme.colors
    Box(modifier.size(size).clip(CircleShape).background(colors.blockMuted), contentAlignment = Alignment.Center) {
        if (avatarPath.isNotEmpty()) {
            Artwork("file://$avatarPath", Modifier.fillMaxSize(), shape = CircleShape, sizePx = 256)
        } else {
            val initials = initialsOf(name)
            if (initials.isEmpty()) {
                Icon(OndaraIcons.Person, null, tint = colors.onBlockMuted, size = size * 0.5f)
            } else {
                Text(initials, style = if (size > 60.dp) OndaraTheme.type.display else OndaraTheme.type.label, color = colors.onBlockMuted, maxLines = 1)
            }
        }
    }
}

/**
 * Avatar del encabezado (única acción de las pestañas): abre la hoja de Cuenta.
 * Muestra un punto de acento mientras no haya ninguna cuenta conectada.
 */
@Composable
fun AvatarButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val badge = LocalProfileBadge.current
    val colors = OndaraTheme.colors
    Box(
        modifier
            .pressable(haptic = true, onClick = onClick)
            .semantics { contentDescription = if (badge.hasAccounts) "Cuenta" else "Cuenta, sin cuentas conectadas" }
            .size(OndaraTheme.spacing.touch),
        contentAlignment = Alignment.Center,
    ) {
        Avatar(badge.name, badge.avatarPath)
        if (!badge.hasAccounts) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 5.dp, end = 5.dp).size(11.dp)
                    .clip(CircleShape).background(colors.accent).border(2.dp, colors.background, CircleShape),
            )
        }
    }
}

/** Insignia pequeña del origen de una playlist (YouTube / Spotify), monocromática. */
@Composable
fun SourceBadge(source: PlaylistSource, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    if (source == PlaylistSource.ONDARA) return
    val colors = OndaraTheme.colors
    Box(
        modifier.size(size).clip(CircleShape).background(colors.background.copy(alpha = 0.82f))
            .semantics { contentDescription = if (source == PlaylistSource.YOUTUBE) "De YouTube Music" else "De Spotify" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (source == PlaylistSource.YOUTUBE) OndaraIcons.YouTube else OndaraIcons.Spotify, null, tint = colors.onBackground, size = size * 0.64f)
    }
}

/** Colores estables de un mix a partir de su id (hash → tono), para que cada portada sea distinta y determinista. */
private fun mixColors(id: String, accent: Color): List<Color> {
    val hue = ((id.hashCode() and 0x7FFFFFFF) % 360).toFloat()
    return listOf(Color.hsv(hue, 0.62f, 0.86f), Color.hsv((hue + 48f) % 360f, 0.7f, 0.62f), accent, Color.hsv((hue + 300f) % 360f, 0.55f, 0.4f))
}

/**
 * Portada generada por Ondara para un mix: gradiente tipo *mesh* (cuatro focos radiales) con el
 * nombre en la tipografía de la marca. Se dibuja en `drawBehind`, sin bitmaps.
 */
@Composable
fun MixCover(id: String, title: String, modifier: Modifier = Modifier, shape: Shape = OndaraTheme.shapes.artwork) {
    val colors = OndaraTheme.colors
    val palette = remember(id, colors.accent) { mixColors(id, colors.accent) }
    Box(
        modifier
            .clip(shape)
            .background(palette[3])
            .drawBehind {
                val r = size.maxDimension * 0.85f
                drawRect(Brush.radialGradient(listOf(palette[0], Color.Transparent), Offset(size.width * 0.12f, size.height * 0.1f), r))
                drawRect(Brush.radialGradient(listOf(palette[1], Color.Transparent), Offset(size.width * 0.95f, size.height * 0.35f), r * 0.8f))
                drawRect(Brush.radialGradient(listOf(palette[2].copy(alpha = 0.85f), Color.Transparent), Offset(size.width * 0.3f, size.height * 1.05f), r * 0.75f))
            },
    ) {
        Column(Modifier.align(Alignment.BottomStart).padding(OndaraTheme.spacing.md)) {
            Icon(OndaraIcons.Wave, null, tint = Color.White, size = 20.dp)
            Text(title, style = OndaraTheme.type.title, color = Color.White, maxLines = 2)
        }
    }
}

/** Portada de cualquier playlist: generada si es un mix, la suya en otro caso, con insignia de origen. */
@Composable
fun PlaylistCover(playlist: Playlist, modifier: Modifier = Modifier, shape: Shape = OndaraTheme.shapes.artwork, sizePx: Int = 360) {
    Box(modifier) {
        if (playlist.thumbnailUrl == null && MixIds.isVirtual(playlist.id)) {
            MixCover(playlist.id, playlist.title, Modifier.fillMaxSize(), shape)
        } else {
            Artwork(playlist.thumbnailUrl, Modifier.fillMaxSize(), shape = shape, sizePx = sizePx)
        }
        SourceBadge(playlist.source, Modifier.align(Alignment.BottomEnd).padding(8.dp))
    }
}

/**
 * Tarjeta grande vertical de "Top Picks" (≈ 70 % del ancho): portada arriba y una base con el color
 * dominante de la portada, con la etiqueta ("Porque escuchaste X") y el título.
 */
@Composable
fun TopPickCard(pick: TopPick, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = OndaraTheme.colors
    val item = pick.item
    val playlist = (item as? MusicItem.PlaylistItem)?.playlist
    val image = item.imageUrl()
    val generated = playlist != null && image == null && MixIds.isVirtual(playlist.id)
    val palette = rememberArtworkPalette(image)
    val accent = colors.accent
    val base = when {
        OndaraTheme.spec.ditherArtwork -> colors.blockPrimary
        palette != null -> palette.dominant
        generated -> remember(playlist!!.id, accent) { mixColors(playlist.id, accent)[1] }
        else -> colors.surfaceHigh
    }
    val onBase = remember(base) { Color(ColorMath.onColor(base.toArgb())) }
    Column(
        modifier
            .clip(OndaraTheme.shapes.large)
            .background(base)
            .pressable(onClick = onClick)
            .semantics(mergeDescendants = true) { },
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            if (playlist != null) {
                PlaylistCover(playlist, Modifier.fillMaxSize(), shape = RectangleShape, sizePx = 720)
            } else {
                Artwork(image, Modifier.fillMaxSize(), shape = RectangleShape, sizePx = 720)
            }
            // Funde la portada con la base de color.
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(56.dp).background(Brush.verticalGradient(listOf(Color.Transparent, base))))
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = OndaraTheme.spacing.lg).padding(top = OndaraTheme.spacing.xs, bottom = OndaraTheme.spacing.lg)) {
            Label(pick.label, color = onBase.copy(alpha = 0.78f))
            Spacer(Modifier.height(OndaraTheme.spacing.xxs))
            Text(item.titleText(), style = OndaraTheme.type.title, color = onBase, maxLines = 2)
        }
    }
}

fun MusicItem.imageUrl(): String? = when (this) {
    is MusicItem.SongItem -> song.thumbnailUrl
    is MusicItem.AlbumItem -> album.thumbnailUrl
    is MusicItem.ArtistItem -> artist.thumbnailUrl
    is MusicItem.PlaylistItem -> playlist.thumbnailUrl
}

fun MusicItem.titleText(): String = when (this) {
    is MusicItem.SongItem -> song.title
    is MusicItem.AlbumItem -> album.title
    is MusicItem.ArtistItem -> artist.name
    is MusicItem.PlaylistItem -> playlist.title
}

/** Carrusel horizontal genérico con título y tamaño de tarjeta configurable. */
@Composable
fun <T : Any> Carousel(
    title: String,
    items: List<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    spacing: Dp = OndaraTheme.spacing.md,
    card: @Composable (T) -> Unit,
) {
    Column(modifier.padding(bottom = OndaraTheme.spacing.xl)) {
        app.ondara.core.designsystem.component.SectionTitle(title, action = action, onAction = onAction)
        LazyRow(contentPadding = PaddingValues(horizontal = OndaraTheme.spacing.screen), horizontalArrangement = Arrangement.spacedBy(spacing)) {
            items(items, key = key) { card(it) }
        }
    }
}

/** Color de página de una pantalla inmersiva y su color de texto con contraste AA garantizado. */
@Immutable
data class ImmersiveColors(val page: Color, val content: Color)

/**
 * Deriva el color de fondo de una página inmersiva del color dominante de su imagen: se mezcla con
 * el fondo del tema para que no deslumbre, y el texto se fuerza a AA (búsqueda binaria, O(log 1/ε)).
 * En los temas mate (sin blur) la página conserva el fondo del tema.
 */
@Composable
fun rememberImmersiveColors(imageUrl: String?): ImmersiveColors {
    val colors = OndaraTheme.colors
    val matte = OndaraTheme.materials.blur.value <= 0f
    val palette = rememberArtworkPalette(imageUrl)
    val dominant = palette?.dominant
    return remember(dominant, colors.background, colors.onBackground, matte) {
        val page = if (dominant == null || matte) colors.background else lerp(dominant, colors.background, if (colors.isDark) 0.42f else 0.6f)
        ImmersiveColors(page, Color(ColorMath.ensureContrast(colors.onBackground.toArgb(), page.toArgb())))
    }
}

/**
 * Detalle inmersivo (Artista, Álbum, Playlist): envuelve [ImmersiveHeroScaffold] con la portada y el
 * color dominante. [cover] permite sustituir la imagen (portadas generadas de los mixes).
 */
@Composable
fun ImmersiveDetail(
    imageUrl: String?,
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    meta: String? = null,
    cover: (@Composable (Modifier) -> Unit)? = null,
    topActions: @Composable RowScope.() -> Unit = {},
    centerActions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val immersive = rememberImmersiveColors(imageUrl)
    ImmersiveHeroScaffold(
        title = title,
        pageColor = immersive.page,
        contentColor = immersive.content,
        onBack = onBack,
        hero = { m -> if (cover != null) cover(m) else Artwork(imageUrl, m, shape = RectangleShape, sizePx = 1080) },
        modifier = modifier,
        eyebrow = eyebrow,
        meta = meta,
        topActions = topActions,
        centerActions = centerActions,
        content = content,
    )
}
