// Adaptado de Echo Music (https://github.com/EchoMusicApp/Echo-Music), GPL-3.0.
package app.ondara.feature.player

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.viewinterop.AndroidView
import app.ondara.core.designsystem.component.MeshGradientBackground
import app.ondara.core.designsystem.component.twoToneDither
import app.ondara.core.designsystem.theme.OndaraTheme
import app.ondara.core.model.CanvasState
import app.ondara.core.model.CanvasSurfaces
import app.ondara.core.ui.Artwork

/** Fondo reutilizable; las animaciones solo se leen durante dibujo, sin recomposición por fotograma. */
@Composable
internal fun CanvasBackground(
    state: CanvasState, surfaces: CanvasSurfaces, artworkUrl: String?,
    onSurface: (slot: Int, surface: Any?) -> Unit, modifier: Modifier = Modifier, dither: Boolean = false,
) {
    val motion = OndaraTheme.motion
    val colors = OndaraTheme.colors
    val spacing = OndaraTheme.spacing
    val movement = remember { Animatable(0f) }
    val duration = motion.slowMs.coerceIn(300, 400)
    LaunchedEffect(motion.reduced) {
        if (motion.reduced) movement.snapTo(0f) else while (true) {
            movement.animateTo(1f, tween(motion.slowMs * 48, easing = LinearEasing))
            movement.animateTo(0f, tween(motion.slowMs * 48, easing = LinearEasing))
        }
    }
    Box(modifier.clipToBounds().then(
        if (dither) Modifier.twoToneDither(colors.onBlock, colors.blockLight) else Modifier,
    )) {
        MeshGradientBackground(OndaraTheme.artwork, Modifier.fillMaxSize())
        Artwork(artworkUrl, Modifier.fillMaxSize().graphicsLayer {
            val travel = if (motion.reduced) 0f else movement.value
            scaleX = 1f + travel * (1f - motion.pressScale) * 4
            scaleY = scaleX
            translationX = travel * spacing.sm.toPx()
            translationY = -translationX
        }, shape = RectangleShape, playing = true, sizePx = 1080)
        if (!motion.reduced) repeat(2) { slot ->
            val visible = state is CanvasState.Ready && slot == surfaces.active && slot in surfaces.firstFrame
            val opacity = animateFloatAsState(if (visible) 1f else 0f, motion.fade(duration), label = "canvas $slot")
            CanvasTexture(slot, surfaces, onSurface, Modifier.fillMaxSize().graphicsLayer { alpha = opacity.value })
        }
    }
}

@Composable
private fun CanvasTexture(slot: Int, surfaces: CanvasSurfaces, onSurface: (Int, Any?) -> Unit, modifier: Modifier) {
    val callback by rememberUpdatedState(onSurface)
    val target = remember { arrayOfNulls<Surface>(1) }
    // Conserva las dimensiones de cada ranura durante el cruce con un vídeo de otra proporción.
    var streamWidth by remember { mutableIntStateOf(0) }
    var streamHeight by remember { mutableIntStateOf(0) }
    LaunchedEffect(surfaces.active, surfaces.width, surfaces.height) {
        if (surfaces.active == slot) { streamWidth = surfaces.width; streamHeight = surfaces.height }
    }
    DisposableEffect(slot) {
        onDispose { callback(slot, null); target[0]?.release(); target[0] = null }
    }
    AndroidView(factory = { context ->
        TextureView(context).apply {
            isOpaque = false
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, viewWidth: Int, viewHeight: Int) {
                    target[0] = Surface(texture)
                    callback(slot, target[0])
                    crop(streamWidth, streamHeight)
                }
                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, viewWidth: Int, viewHeight: Int) { crop(streamWidth, streamHeight) }
                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    callback(slot, null)
                    target[0]?.release()
                    target[0] = null
                    return true
                }
            }
        }
    }, update = { it.crop(streamWidth, streamHeight) }, modifier = modifier)
}

/** Búfer a resolución del stream y transformación center crop, equivalente a RESIZE_MODE_ZOOM. O(1). */
private fun TextureView.crop(videoWidth: Int, videoHeight: Int) {
    if (videoWidth <= 0 || videoHeight <= 0 || width <= 0 || height <= 0) return
    surfaceTexture?.setDefaultBufferSize(videoWidth, videoHeight)
    val scale = centerCropScale(videoWidth, videoHeight, width, height)
    setTransform(Matrix().apply { setScale(scale.first, scale.second, width / 2f, height / 2f) })
}
