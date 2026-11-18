package app.ondara.core.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import app.ondara.core.designsystem.component.Label
import app.ondara.core.designsystem.component.Skeleton
import app.ondara.core.designsystem.component.Text
import app.ondara.core.designsystem.component.grain
import app.ondara.core.designsystem.component.twoToneDither
import app.ondara.core.designsystem.theme.ColorMath
import app.ondara.core.designsystem.theme.OndaraTheme
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class CoverKey(val id: String, val urls: List<String>, val background: Int, val ink: Boolean, val theme: String)
private val covers = object : LruCache<CoverKey, Bitmap>(16 * 1024 * 1024) {
    override fun sizeOf(key: CoverKey, value: Bitmap): Int = value.byteCount
}

/**
 * Pila de carátulas reales, cacheada por id, imágenes y tema. O(p) al generar p píxeles,
 * O(1) en aciertos de caché (hasta cuatro URLs); memoria acotada a 16 MiB.
 * El texto permanece vectorial para respetar la tipografía y escala accesible del sistema.
 */
@Composable
fun MixCover(
    id: String,
    title: String,
    modifier: Modifier = Modifier,
    shape: Shape = OndaraTheme.shapes.artwork,
    artworkUrls: List<String> = emptyList(),
    showTitle: Boolean = true,
) {
    val colors = OndaraTheme.colors
    val ink = OndaraTheme.spec.ditherArtwork
    val context = LocalContext.current.applicationContext
    val palette = rememberArtworkPalette(artworkUrls.firstOrNull())
    val base = if (ink) colors.surface else lerp(palette?.muted ?: colors.surface, colors.background, 0.85f)
    val key = CoverKey(id, artworkUrls.take(4), base.toArgb(), ink, OndaraTheme.spec.id)
    val bitmap by produceState<Bitmap?>(null, key) {
        value = null
        value = withContext(Dispatchers.IO) {
            // LruCache es seguro entre hilos; sin candado global, cada portada descarga en paralelo
            // (una generación duplicada ocasional es inocua y más barata que serializar todas).
            covers.get(key) ?: run {
                val images = key.urls.mapNotNull { url ->
                    val request = ImageRequest.Builder(context).data(url.thumbnail(480)).allowHardware(false).build()
                    (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
                }
                if (images.isEmpty()) null else renderCover(images, key.background, key.ink).also { covers.put(key, it) }
            }
        }
    }
    Box(modifier.clip(shape).background(colors.surface)) {
        val ready = bitmap
        if (ready == null) Skeleton(Modifier.fillMaxSize(), shape)
        else {
            if (android.os.Build.VERSION.SDK_INT >= 31 && !ink) {
                Artwork(key.urls.firstOrNull(), Modifier.fillMaxSize().blur(OndaraTheme.spacing.xxl))
                Box(Modifier.fillMaxSize().background(base.copy(alpha = 0.85f)))
            }
            Image(
                ready.asImageBitmap(), null,
                Modifier.fillMaxSize().grain(OndaraTheme.spec.grain).drawWithContent {
                    drawContent()
                    drawRect(Brush.radialGradient(listOf(Color.Transparent, base.copy(alpha = 0.4f)), radius = size.maxDimension))
                }.then(
                    if (ink) Modifier.twoToneDither(colors.onBlock, colors.blockLight) else Modifier,
                ),
            )
        }
        if (showTitle) {
            Column(Modifier.align(Alignment.BottomStart).background(base).padding(OndaraTheme.spacing.sm)) {
                Label("Mix · Ondara", color = Color(ColorMath.onColor(base.toArgb())))
                Text(title, style = OndaraTheme.type.display, color = Color(ColorMath.onColor(base.toArgb())), maxLines = 2)
            }
        }
    }
}

/** Rasteriza hasta cuatro discos. O(p) tiempo y memoria; fondo reducido a 8 px y velo sin RenderEffect. */
private fun renderCover(images: List<Bitmap>, background: Int, ink: Boolean): Bitmap {
    val edge = 480
    val output = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    if (android.os.Build.VERSION.SDK_INT < 31 || ink) {
        val tiny = Bitmap.createScaledBitmap(images.first(), 8, 8, true)
        canvas.drawBitmap(tiny, null, Rect(0, 0, edge, edge), paint)
        tiny.recycle()
        paint.color = background
        paint.alpha = 210
        canvas.drawRect(0f, 0f, edge.toFloat(), edge.toFloat(), paint)
    }
    paint.alpha = 255
    images.asReversed().forEachIndexed { index, bitmap ->
        val main = index == images.lastIndex
        val side = if (main) edge * 0.65f else edge * 0.44f
        val left = if (main) edge * 0.08f else edge * (0.46f - index * 0.08f)
        val top = if (main) edge * 0.12f else edge * (0.12f + index * 0.12f)
        val sourceSide = minOf(bitmap.width, bitmap.height)
        val x = (bitmap.width - sourceSide) / 2
        val y = (bitmap.height - sourceSide) / 2
        canvas.save()
        canvas.rotate(if (main) -5f else 8f - index * 5f, left + side / 2, top + side / 2)
        canvas.drawBitmap(bitmap, Rect(x, y, x + sourceSide, y + sourceSide), RectF(left, top, left + side, top + side), paint)
        canvas.restore()
    }
    return output
}
