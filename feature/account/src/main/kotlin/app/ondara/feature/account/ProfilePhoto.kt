package app.ondara.feature.account

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.util.AtomicFile
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.ondara.core.designsystem.component.OndaraDialog
import java.io.File
import kotlin.math.roundToInt

data class PhotoCrop(val x: Float = .5f, val y: Float = .5f, val zoom: Float = 1f) {
    fun rect(width: Int, height: Int): Rect {
        val side = (minOf(width, height) / zoom).roundToInt().coerceAtLeast(1)
        val left = (x * width - side / 2f).roundToInt().coerceIn(0, width - side)
        val top = (y * height - side / 2f).roundToInt().coerceIn(0, height - side)
        return Rect(left, top, left + side, top + side)
    }

    fun transform(width: Int, height: Int, dx: Float, dy: Float, scale: Float): PhotoCrop {
        val nextZoom = (zoom * scale).coerceIn(1f, 8f)
        val halfSide = minOf(width, height) / (2f * nextZoom)
        return PhotoCrop(
            (x - dx * halfSide * 2 / width).coerceIn(halfSide / width, 1f - halfSide / width),
            (y - dy * halfSide * 2 / height).coerceIn(halfSide / height, 1f - halfSide / height),
            nextZoom,
        )
    }
}

@Composable
internal fun CropProfilePhoto(bitmap: Bitmap, onDismiss: () -> Unit, onSave: (PhotoCrop) -> Unit) {
    var crop by remember(bitmap) { mutableStateOf(PhotoCrop()) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    OndaraDialog("Recortar foto", onDismiss, "Usar foto", { onSave(crop) },
        text = "Pellizca para ampliar y arrastra para ajustar el recorte.",
    ) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f).pointerInput(bitmap) {
            detectTransformGestures { _, pan, scale, _ ->
                crop = crop.transform(bitmap.width, bitmap.height, pan.x / size.width, pan.y / size.height, scale)
            }
        }) {
            val source = crop.rect(bitmap.width, bitmap.height)
            drawImage(
                image, srcOffset = IntOffset(source.left, source.top), srcSize = IntSize(source.width(), source.height()),
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            )
        }
    }
}

private const val PHOTO_DECODE_LIMIT = 4096
private const val AVATAR_LIMIT = 1024

/** Decodificación acotada a O(w·h) píxeles, manteniendo la orientación de ImageDecoder. */
internal fun decodeProfilePhoto(context: Context, uri: Uri): Bitmap {
    val resolver = context.contentResolver
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val scale = (PHOTO_DECODE_LIMIT.toFloat() / maxOf(info.size.width, info.size.height)).coerceAtMost(1f)
            decoder.setTargetSize(
                (info.size.width * scale).roundToInt().coerceAtLeast(1),
                (info.size.height * scale).roundToInt().coerceAtLeast(1),
            )
        }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
    val options = BitmapFactory.Options().apply {
        inSampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > PHOTO_DECODE_LIMIT) inSampleSize *= 2
    }
    return requireNotNull(resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }) {
        "No se pudo leer la foto"
    }
}

/** Rasteriza directamente el recorte, con un máximo de 1024² píxeles. O(w·h) sobre la salida. */
internal fun saveProfilePhoto(filesDir: File, source: Bitmap, crop: PhotoCrop): String {
    val rect = crop.rect(source.width, source.height)
    val edge = rect.width().coerceAtMost(AVATAR_LIMIT)
    val avatar = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
    try {
        android.graphics.Canvas(avatar).drawBitmap(source, rect, Rect(0, 0, edge, edge), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        val directory = File(filesDir, "profile").apply { mkdirs() }
        val target = File(directory, "avatar.png")
        val file = AtomicFile(target)
        val stream = file.startWrite()
        try {
            check(avatar.compress(Bitmap.CompressFormat.PNG, 100, stream))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
        return target.absolutePath
    } finally {
        avatar.recycle()
    }
}
