package app.ondara.feature.player

import app.ondara.core.designsystem.theme.PlayerLayout

/** Resuelve la preferencia persistida; valores futuros o THEME usan el tema. O(n) sobre los layouts. */
fun resolvePlayerLayout(setting: String, theme: PlayerLayout): PlayerLayout =
    PlayerLayout.entries.firstOrNull { it.name == setting } ?: theme

/** Escalas del TextureView para cubrir el contenedor sin deformar. O(1). */
internal fun centerCropScale(videoWidth: Int, videoHeight: Int, width: Int, height: Int): Pair<Float, Float> {
    if (videoWidth <= 0 || videoHeight <= 0 || width <= 0 || height <= 0) return 1f to 1f
    val ratio = videoWidth.toFloat() / videoHeight / (width.toFloat() / height)
    return if (ratio > 1f) ratio to 1f else 1f to 1f / ratio
}

/** Divide la pantalla en dos zonas de salto. O(1). */
internal fun doubleTapDelta(x: Float, width: Float): Long = if (x < width / 2f) -10_000L else 10_000L
