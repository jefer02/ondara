package app.ondara.core.designsystem.component

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.ondara.core.designsystem.theme.ArtworkPalette
import app.ondara.core.designsystem.theme.LocalAudioAmplitude
import app.ondara.core.designsystem.theme.OndaraTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Botón play/pausa con *morphing*: las dos barras de pausa se deforman hasta formar el triángulo
 * de play (interpolación de vértices guiada por un spring). En temas con `accentPulse` el botón
 * late con la amplitud del audio.
 */
@Composable
fun PlayPauseButton(
    playing: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    container: Color = OndaraTheme.colors.accent,
    content: Color = OndaraTheme.colors.onAccent,
    playLabel: String = "Reproducir",
    pauseLabel: String = "Pausar",
) {
    val motion = OndaraTheme.motion
    val progress by animateFloatAsState(if (playing) 1f else 0f, motion.bouncy(0.75f, 500f), label = "playPause")
    val pulse = OndaraTheme.spec.accentPulse
    val amplitude = LocalAudioAmplitude.current
    val shape = if (OndaraTheme.spec.shapes.pillControls) CircleShape else OndaraTheme.shapes.medium
    Box(
        modifier
            .pressable(haptic = true, role = Role.Button, onClick = onToggle)
            .semantics { contentDescription = if (playing) pauseLabel else playLabel }
            .size(size)
            .graphicsLayer {
                if (pulse) {
                    val s = 1f + amplitude() * 0.12f
                    scaleX = s
                    scaleY = s
                }
            }
            .clip(shape)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size * 0.4f)) { drawPlayPause(progress.coerceIn(0f, 1.15f), content) }
    }
}

/** Dibuja el glifo: `progress = 0` → play, `1` → pausa. */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPlayPause(progress: Float, color: Color) {
    val w = size.width
    val h = size.height
    fun lerp(a: Float, b: Float) = a + (b - a) * progress
    val barW = w * 0.3f
    // Mitad izquierda: trapecio del triángulo → barra izquierda.
    val left = Path().apply {
        moveTo(lerp(w * 0.12f, 0.08f * w), 0f)
        lineTo(lerp(w * 0.56f, 0.08f * w + barW), lerp(h * 0.25f, 0f))
        lineTo(lerp(w * 0.56f, 0.08f * w + barW), lerp(h * 0.75f, h))
        lineTo(lerp(w * 0.12f, 0.08f * w), h)
        close()
    }
    // Mitad derecha: punta del triángulo → barra derecha.
    val right = Path().apply {
        moveTo(lerp(w * 0.55f, w * 0.92f - barW), lerp(h * 0.245f, 0f))
        lineTo(lerp(w, w * 0.92f), lerp(h * 0.5f, 0f))
        lineTo(lerp(w, w * 0.92f), lerp(h * 0.5f, h))
        lineTo(lerp(w * 0.55f, w * 0.92f - barW), lerp(h * 0.755f, h))
        close()
    }
    drawPath(left, color)
    drawPath(right, color)
}

private const val MESH_SHADER = """
uniform float2 iResolution;
uniform float iTime;
layout(color) uniform half4 c0;
layout(color) uniform half4 c1;
layout(color) uniform half4 c2;
layout(color) uniform half4 c3;

float hash(float2 p) { return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453); }

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / iResolution;
    float t = iTime * 0.31415926; // 2π / 20 s
    float2 p0 = float2(0.20 + 0.18 * sin(t),        0.18 + 0.14 * cos(t * 1.0));
    float2 p1 = float2(0.82 + 0.14 * cos(t * 2.0),  0.30 + 0.20 * sin(t));
    float2 p2 = float2(0.30 + 0.22 * cos(t),        0.84 + 0.12 * sin(t * 2.0));
    float2 p3 = float2(0.78 + 0.16 * sin(t * 2.0),  0.78 + 0.16 * cos(t));
    float w0 = 1.0 / (pow(distance(uv, p0), 2.6) + 0.015);
    float w1 = 1.0 / (pow(distance(uv, p1), 2.6) + 0.015);
    float w2 = 1.0 / (pow(distance(uv, p2), 2.6) + 0.015);
    float w3 = 1.0 / (pow(distance(uv, p3), 2.6) + 0.015);
    half4 color = (c0 * w0 + c1 * w1 + c2 * w2 + c3 * w3) / (w0 + w1 + w2 + w3);
    // Dither para evitar bandas en gradientes oscuros.
    float n = (hash(fragCoord) - 0.5) / 96.0;
    return half4(color.rgb + n, 1.0);
}
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class MeshShader {
    val shader = RuntimeShader(MESH_SHADER)
    val brush = ShaderBrush(shader)
}

/**
 * Fondo "mesh gradient" animado, firma visual de Ondara.
 *
 * - API 33+: shader AGSL con cuatro focos de color que orbitan en un ciclo de ~20 s.
 * - API < 33: cuatro gradientes radiales en movimiento (con `blur` en API 31–32).
 *
 * Los colores salen de la portada y transicionan en 800 ms al cambiar de canción. Todo se anima
 * en la fase de dibujo (uniforms), sin recomponer. Con "reducir animaciones" el fondo queda estático.
 */
@Composable
fun MeshGradientBackground(palette: ArtworkPalette, modifier: Modifier = Modifier, dim: Float = 0.45f) {
    val base = OndaraTheme.colors.background
    val reduced = OndaraTheme.motion.reduced
    fun shade(c: Color) = androidx.compose.ui.graphics.lerp(c, base, dim)
    val spec = tween<Color>(800)
    val c0 by animateColorAsState(shade(palette.dominant), spec, label = "mesh0")
    val c1 by animateColorAsState(shade(palette.vibrant), spec, label = "mesh1")
    val c2 by animateColorAsState(shade(palette.muted), spec, label = "mesh2")
    val c3 by animateColorAsState(androidx.compose.ui.graphics.lerp(palette.dominant, base, 0.8f), spec, label = "mesh3")
    var time by remember { mutableFloatStateOf(0f) }
    if (!reduced) {
        LaunchedEffect(Unit) {
            val start = withFrameNanos { it }
            while (true) withFrameNanos { time = (it - start) / 1_000_000_000f }
        }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val mesh = remember { MeshShader() }
        Box(
            modifier.drawBehind {
                mesh.shader.setFloatUniform("iResolution", size.width, size.height)
                mesh.shader.setFloatUniform("iTime", time)
                mesh.shader.setColorUniform("c0", c0.toArgb())
                mesh.shader.setColorUniform("c1", c1.toArgb())
                mesh.shader.setColorUniform("c2", c2.toArgb())
                mesh.shader.setColorUniform("c3", c3.toArgb())
                drawRect(mesh.brush)
            },
        )
    } else {
        Box(
            modifier
                .background(base)
                .then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(48.dp) else Modifier)
                .drawBehind {
                    val t = time * 0.31415927f
                    val r = max(size.width, size.height) * 0.75f
                    fun blob(color: Color, x: Float, y: Float) {
                        val center = Offset(size.width * x, size.height * y)
                        drawRect(Brush.radialGradient(listOf(color, Color.Transparent), center, r))
                    }
                    blob(c0, 0.20f + 0.18f * sin(t), 0.18f + 0.14f * cos(t))
                    blob(c1, 0.82f + 0.14f * cos(t * 2), 0.30f + 0.20f * sin(t))
                    blob(c2, 0.30f + 0.22f * cos(t), 0.84f + 0.12f * sin(t * 2))
                    blob(c3, 0.78f + 0.16f * sin(t * 2), 0.78f + 0.16f * cos(t))
                },
        )
    }
}

/** Textura de grano (tema Vinilo): ruido en mosaico de 128 px, generado una vez. O(1) por frame. */
fun Modifier.grain(intensity: Float): Modifier = if (intensity <= 0f) this else composedGrain(intensity)

private fun Modifier.composedGrain(intensity: Float): Modifier = composed {
    val brush = remember {
        val size = 128
        val random = Random(42)
        val pixels = IntArray(size * size) {
            val v = random.nextInt(256)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val bitmap = android.graphics.Bitmap.createBitmap(pixels, size, size, android.graphics.Bitmap.Config.ARGB_8888)
        ShaderBrush(ImageShader(bitmap.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
    }
    drawWithContent {
        drawContent()
        drawRect(brush, alpha = intensity * 0.16f, blendMode = BlendMode.Overlay)
    }
}

/** Controla la transición animada entre temas. */
@Stable
class ThemeTransitionState internal constructor() {
    internal var request by mutableStateOf<Request?>(null)

    internal class Request(val origin: Offset, val apply: () -> Unit)

    /**
     * Cambia de tema con un *circular reveal* que nace en [origin] (coordenadas de ventana del toque).
     * [apply] debe hacer efectivo el cambio (p. ej. guardar el id del tema).
     */
    fun change(origin: Offset, apply: () -> Unit) {
        request = Request(origin, apply)
    }
}

@Composable
fun rememberThemeTransitionState() = remember { ThemeTransitionState() }

val LocalThemeTransition = androidx.compose.runtime.staticCompositionLocalOf<ThemeTransitionState?> { null }

/**
 * Anfitrión de la transición de tema: captura la pantalla actual en un `GraphicsLayer`, aplica el
 * tema nuevo debajo y lo descubre con un círculo que crece desde el punto del toque (spring suave,
 * ~400 ms). Con "reducir animaciones" se sustituye por un fundido de 150 ms.
 */
@Composable
fun ThemeTransitionHost(
    state: ThemeTransitionState,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
    themeKey: () -> Any? = { null },
    content: @Composable () -> Unit,
) {
    val layer = rememberGraphicsLayer()
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<ImageBitmap?>(null) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val progress = remember { Animatable(0f) }

    LaunchedEffect(state.request) {
        val request = state.request ?: return@LaunchedEffect
        state.request = null
        scope.launch {
            snapshot = runCatching { layer.toImageBitmap() }.getOrNull()
            origin = request.origin
            progress.snapTo(0f)
            val before = themeKey()
            request.apply()
            // El tema nuevo llega de forma asíncrona (DataStore): se espera a que esté compuesto para no revelar el viejo.
            withTimeoutOrNull(400) { snapshotFlow { themeKey() }.first { it != before } }
            withFrameNanos { }
            progress.animateTo(1f, if (reduceMotion) tween(150) else spring(dampingRatio = 1f, stiffness = 110f))
            snapshot = null
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .drawWithContent {
                layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
                val old = snapshot ?: return@drawWithContent
                if (reduceMotion) {
                    drawImage(old, alpha = 1f - progress.value)
                } else {
                    val maxRadius = listOf(
                        Offset.Zero, Offset(size.width, 0f), Offset(0f, size.height), Offset(size.width, size.height),
                    ).maxOf { hypot(it.x - origin.x, it.y - origin.y) }
                    val reveal = Path().apply { addOval(Rect(origin, maxRadius * progress.value)) }
                    clipPath(reveal, ClipOp.Difference) { drawImage(old) }
                }
            },
    ) {
        content()
    }
}

private const val DITHER_SHADER = """
uniform shader content;
uniform float cell;
uniform float amount;
layout(color) uniform half4 ink;
layout(color) uniform half4 paper;

// Matriz de Bayer 4x4 generada aritméticamente (AGSL no permite indexar arrays con valores no constantes).
float bayer2(float2 a) { a = floor(a); return fract(a.x / 2.0 + a.y * a.y * 0.75); }
float bayer4(float2 a) { return bayer2(0.5 * a) * 0.25 + bayer2(a); }

half4 main(float2 p) {
    float2 block = floor(p / cell);
    half4 c = content.eval((block + 0.5) * cell);
    half4 original = content.eval(p);
    float l = dot(c.rgb, half3(0.299, 0.587, 0.114));
    // Algo de contraste antes de umbralizar: las portadas oscuras no quedan en una mancha.
    l = clamp((l - 0.5) * 1.25 + 0.54, 0.0, 1.0);
    float threshold = bayer4(block) + 0.03125;
    half3 tone = l > threshold ? paper.rgb : ink.rgb;
    return half4(mix(original.rgb, tone * original.a, amount), original.a);
}
"""

/**
 * Tramado ordenado de 1 bit (**Bayer 4×4**) a dos tonos, como una ilustración impresa: cada celda
 * de [cellDp] toma [ink] o [paper] comparando su luminancia con el umbral de la matriz.
 *
 * - API 33+: `RuntimeShader` AGSL aplicado como `RenderEffect` de la capa. O(1) por píxel, en GPU.
 * - API < 33: matriz de color (escala de grises de contraste alto teñida entre los dos tonos).
 *
 * [amount] 0..1 mezcla con la imagen original (1 = tramado completo); se lee en la fase de dibujo,
 * así el fundido a color de la portada en reproducción no recompone.
 */
fun Modifier.twoToneDither(ink: Color, paper: Color, cellDp: Float = 2f, amount: () -> Float = { 1f }): Modifier = composed {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val shader = remember { RuntimeShader(DITHER_SHADER) }
        graphicsLayer {
            val a = amount().coerceIn(0f, 1f)
            if (a <= 0.001f) {
                renderEffect = null
            } else {
                shader.setFloatUniform("cell", cellDp * density)
                shader.setFloatUniform("amount", a)
                shader.setColorUniform("ink", ink.toArgb())
                shader.setColorUniform("paper", paper.toArgb())
                renderEffect = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
            }
            clip = true
        }
    } else {
        drawWithContent {
            val a = amount().coerceIn(0f, 1f)
            drawContent()
            if (a > 0.001f) {
                val paint = androidx.compose.ui.graphics.Paint().apply {
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.colorMatrix(twoToneMatrix(ink, paper))
                    alpha = a
                }
                drawIntoCanvas { canvas ->
                    canvas.saveLayer(Rect(Offset.Zero, size), paint)
                    drawContent()
                    canvas.restore()
                }
            }
        }
    }
}

/**
 * Matriz 4×5 que lleva la luminancia (con contraste ×1,8) a la recta entre [ink] y [paper].
 * Es la versión sin shader del tramado, para API < 33.
 */
internal fun twoToneMatrix(ink: Color, paper: Color): androidx.compose.ui.graphics.ColorMatrix {
    val contrast = 1.8f
    val lr = 0.299f * contrast
    val lg = 0.587f * contrast
    val lb = 0.114f * contrast
    val bias = (0.5f - 0.5f * contrast) * 255f
    fun row(i: Float, p: Float) = floatArrayOf((p - i) * lr, (p - i) * lg, (p - i) * lb, 0f, i * 255f + (p - i) * bias)
    return androidx.compose.ui.graphics.ColorMatrix(
        row(ink.red, paper.red) + row(ink.green, paper.green) + row(ink.blue, paper.blue) + floatArrayOf(0f, 0f, 0f, 1f, 0f),
    )
}
