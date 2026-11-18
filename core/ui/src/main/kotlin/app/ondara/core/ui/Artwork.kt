package app.ondara.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import app.ondara.core.designsystem.component.twoToneDither
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.fillMaxSize
import app.ondara.core.designsystem.component.Skeleton
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.palette.graphics.Palette
import app.ondara.core.designsystem.icon.Icon
import app.ondara.core.designsystem.icon.OndaraIcons
import app.ondara.core.designsystem.theme.ArtworkPalette
import app.ondara.core.designsystem.theme.OndaraTheme
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val SIZE_PARAM = Regex("""=w\d+-h\d+""")

/** Pide a los servidores de Google la miniatura a [px] píxeles (evita descargar de más en listas). */
fun String.thumbnail(px: Int): String = if (SIZE_PARAM.containsMatchIn(this)) SIZE_PARAM.replace(this, "=w$px-h$px") else this

/** `false` si el usuario desactivó las portadas tramadas (ajuste); solo afecta a los temas que las usan. */
val LocalDitherArtwork = staticCompositionLocalOf { true }

/**
 * Portada con marcador de posición del tema. `contentDescription = null` cuando el texto contiguo ya la describe.
 *
 * En los temas con `ditherArtwork` (Tinta) se pinta con el tramado de dos tonos del tema; con
 * [playing] (la portada de lo que suena) vuelve a color con un fundido de 300 ms.
 */
@Composable
fun Artwork(
    url: String?,
    modifier: Modifier = Modifier,
    shape: Shape = OndaraTheme.shapes.small,
    sizePx: Int = 240,
    contentDescription: String? = null,
    playing: Boolean = false,
) {
    val colors = OndaraTheme.colors
    val dither = OndaraTheme.spec.ditherArtwork && LocalDitherArtwork.current
    Box(modifier.clip(shape).background(colors.surfaceHigh), contentAlignment = Alignment.Center) {
        if (url == null) {
            Icon(OndaraIcons.Wave, null, tint = colors.onMuted, size = 22.dp)
        } else {
            var loaded by remember(url) { mutableStateOf(false) }
            if (!loaded) Skeleton(Modifier.fillMaxSize(), shape)
            val context = LocalContext.current
            val amount by animateFloatAsState(if (playing) 0f else 1f, tween(300), label = "dither")
            AsyncImage(
                model = ImageRequest.Builder(context).data(url.thumbnail(sizePx)).crossfade(true).build(),
                contentDescription = contentDescription,
                modifier = Modifier.matchParentSize().then(
                    if (dither) Modifier.twoToneDither(colors.onBlock, colors.blockLight, amount = { amount }) else Modifier,
                ),
                contentScale = ContentScale.Crop,
                onSuccess = { loaded = true },
                // Si falla, se quita el shimmer: queda el fondo del tema en vez de una carga eterna.
                onError = { loaded = true },
            )
        }
    }
}

/**
 * Extrae la paleta de una portada con androidx.palette (sobre una versión de 96 px, fuera del hilo
 * principal). Devuelve `null` mientras carga o si no hay imagen.
 */
@Composable
fun rememberArtworkPalette(url: String?): ArtworkPalette? {
    val context = LocalContext.current.applicationContext
    if (url == null) return null
    return androidx.compose.runtime.key(url) {
        val palette by produceState<ArtworkPalette?>(null) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val request = ImageRequest.Builder(context).data(url.thumbnail(96)).size(96).allowHardware(false).build()
                    val bitmap = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
                        ?: return@runCatching null
                    val swatches = Palette.from(bitmap).clearFilters().maximumColorCount(12).generate()
                    val dominant = swatches.getDominantColor(0xFF202020.toInt())
                    val top = Palette.from(bitmap).clearFilters().maximumColorCount(8)
                        .setRegion(0, 0, bitmap.width, (bitmap.height / 4).coerceAtLeast(1)).generate()
                        .getDominantColor(dominant)
                    ArtworkPalette(
                        dominant = Color(dominant),
                        top = Color(top),
                        vibrant = Color(swatches.getVibrantColor(swatches.getLightVibrantColor(swatches.getMutedColor(dominant)))),
                        muted = Color(swatches.getDarkMutedColor(swatches.getMutedColor(dominant))),
                        light = Color(swatches.getLightMutedColor(swatches.getLightVibrantColor(dominant))),
                    )
                }.getOrNull()
            }
        }
        palette
    }
}
