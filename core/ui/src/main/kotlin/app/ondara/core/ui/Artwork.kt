package app.ondara.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

/** Portada con marcador de posición del tema. `contentDescription = null` cuando el texto contiguo ya la describe. */
@Composable
fun Artwork(
    url: String?,
    modifier: Modifier = Modifier,
    shape: Shape = OndaraTheme.shapes.small,
    sizePx: Int = 240,
    contentDescription: String? = null,
) {
    Box(modifier.clip(shape).background(OndaraTheme.colors.surfaceHigh), contentAlignment = Alignment.Center) {
        if (url == null) {
            Icon(OndaraIcons.Wave, null, tint = OndaraTheme.colors.onMuted, size = 22.dp)
        } else {
            val context = LocalContext.current
            AsyncImage(
                model = ImageRequest.Builder(context).data(url.thumbnail(sizePx)).crossfade(true).build(),
                contentDescription = contentDescription,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
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
    val palette by produceState<ArtworkPalette?>(null, url) {
        if (url == null) {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            runCatching {
                val request = ImageRequest.Builder(context).data(url.thumbnail(96)).allowHardware(false).build()
                val bitmap = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap() ?: return@runCatching null
                val p = Palette.from(bitmap).maximumColorCount(12).generate()
                val dominant = p.getDominantColor(0xFF202020.toInt())
                ArtworkPalette(
                    dominant = Color(dominant),
                    vibrant = Color(p.getVibrantColor(p.getLightVibrantColor(p.getMutedColor(dominant)))),
                    muted = Color(p.getDarkMutedColor(p.getMutedColor(dominant))),
                    light = Color(p.getLightMutedColor(p.getLightVibrantColor(dominant))),
                )
            }.getOrNull()
        } ?: value
    }
    return palette
}
