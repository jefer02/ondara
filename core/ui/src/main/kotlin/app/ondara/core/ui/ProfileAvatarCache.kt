package app.ondara.core.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import java.io.File

/** Una entrada en memoria y en disco, invalidada por foto, tamaño o tonos. Acceso en IO. */
internal object ProfileAvatarCache {
    private var lastKey: String? = null
    private var lastImage: Bitmap? = null

    @Synchronized
    fun load(directory: File, profile: ProfileBadge, dark: Int, light: Int): Bitmap? {
        val photo = File(profile.avatarPath)
        val blocks = profile.pixelBlocks.takeIf { it in listOf(32, 48, 64) } ?: 48
        val key = "${photo.absolutePath}:${photo.lastModified()}:${profile.revision}:${profile.effect}:$blocks:$dark:$light"
        if (lastKey == key) return lastImage
        val image = if (profile.effect) loadPixel(directory, photo, key, blocks, dark, light)
        else BitmapFactory.decodeFile(photo.path)
        lastKey = key
        lastImage = image
        return image
    }

    private fun loadPixel(directory: File, photo: File, key: String, blocks: Int, dark: Int, light: Int): Bitmap? {
        val png = File(directory, "avatar_pixel.png")
        val metadata = File(directory, "avatar_pixel.key")
        if (metadata.isFile && metadata.readText() == key) BitmapFactory.decodeFile(png.path)?.let { return it }
        val source = BitmapFactory.decodeFile(photo.path) ?: return null
        val result = try { pixelate(source, blocks, dark, light) } finally { source.recycle() }
        directory.mkdirs()
        metadata.delete()
        val file = AtomicFile(png)
        val stream = file.startWrite()
        try {
            check(result.compress(Bitmap.CompressFormat.PNG, 100, stream))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
        metadata.writeText(key)
        return result
    }

    /** Reduce al mosaico y aplica Bayer 4×4 con luminancia sRGB. O(n²) para n bloques de lado. */
    internal fun pixelate(source: Bitmap, blocks: Int, dark: Int, light: Int): Bitmap {
        val small = Bitmap.createScaledBitmap(source, blocks, blocks, true)
        val pixels = IntArray(blocks * blocks)
        small.getPixels(pixels, 0, blocks, 0, 0, blocks, blocks)
        if (small !== source) small.recycle()
        for (y in 0 until blocks) for (x in 0 until blocks) {
            val i = y * blocks + x
            val rgb = pixels[i]
            val luminance = (.2126 * (rgb shr 16 and 255) + .7152 * (rgb shr 8 and 255) + .0722 * (rgb and 255)) / 255
            pixels[i] = if (luminance >= (bayer[(y % 4) * 4 + x % 4] + .5) / 16) light else dark
        }
        return Bitmap.createBitmap(pixels, blocks, blocks, Bitmap.Config.ARGB_8888)
    }

    private val bayer = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
}
