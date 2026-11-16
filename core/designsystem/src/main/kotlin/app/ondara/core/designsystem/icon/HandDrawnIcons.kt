package app.ondara.core.designsystem.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Iconos **dibujados a mano** (tema Tinta): trazo grueso de 2,6 px, extremos redondos y curvas
 * deliberadamente imperfectas (los círculos no cierran del todo, las rectas se comban un poco).
 *
 * Cubren navegación y reproducción; [replacement] devuelve el equivalente de un icono de
 * [OndaraIcons] por nombre, o el propio icono si no hay versión a mano. Lookup O(1) (`HashMap`).
 */
object HandDrawnIcons {
    private const val STROKE = 2.6f

    private fun build(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

    private fun ImageVector.Builder.ink(width: Float = STROKE, block: PathBuilder.() -> Unit) = path(
        stroke = SolidColor(Color.Black), strokeLineWidth = width, strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round, pathBuilder = block,
    )

    private fun ImageVector.Builder.blot(block: PathBuilder.() -> Unit) = path(
        fill = SolidColor(Color.Black), stroke = SolidColor(Color.Black), strokeLineWidth = 1.4f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = block,
    )

    /** Círculo a pulso: cuatro cúbicas desiguales cuyo final se pasa un poco del inicio. */
    private fun PathBuilder.loop(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy + 0.3f)
        curveTo(cx - r * 1.03f, cy - r * 0.62f, cx - r * 0.5f, cy - r * 1.06f, cx + 0.2f, cy - r)
        curveTo(cx + r * 0.62f, cy - r * 0.97f, cx + r * 1.05f, cy - r * 0.5f, cx + r, cy + 0.1f)
        curveTo(cx + r * 0.97f, cy + r * 0.6f, cx + r * 0.52f, cy + r * 1.05f, cx - 0.1f, cy + r)
        curveTo(cx - r * 0.6f, cy + r * 1.0f, cx - r * 1.0f, cy + r * 0.55f, cx - r * 0.95f, cy - r * 0.12f)
    }

    /** Recta combada entre dos puntos. */
    private fun PathBuilder.stroke(x1: Float, y1: Float, x2: Float, y2: Float, bend: Float = 0.5f) {
        moveTo(x1, y1)
        val mx = (x1 + x2) / 2f
        val my = (y1 + y2) / 2f
        quadTo(mx + (y2 - y1) * 0.06f * bend, my - (x2 - x1) * 0.06f * bend, x2, y2)
    }

    val Home by lazy {
        build("Home") {
            ink {
                moveTo(3.4f, 11.6f); curveTo(6.4f, 8.6f, 9.2f, 6.1f, 12.2f, 3.7f); curveTo(15f, 6f, 17.9f, 8.7f, 20.7f, 11.5f)
                moveTo(5.9f, 10.2f); curveTo(5.6f, 13.2f, 5.7f, 16.6f, 6f, 19.9f); curveTo(10f, 20.4f, 14f, 20.3f, 18.2f, 19.9f)
                curveTo(18.5f, 16.6f, 18.4f, 13.3f, 18.2f, 10.3f)
                moveTo(10.2f, 19.8f); curveTo(10f, 17.5f, 10f, 16f, 10.3f, 14.4f); curveTo(11.6f, 14.1f, 12.8f, 14.2f, 13.9f, 14.5f); curveTo(14.1f, 16.2f, 14f, 17.8f, 13.9f, 19.8f)
            }
        }
    }
    val News by lazy {
        build("News") {
            ink { loop(7.3f, 7.3f, 3f); loop(16.8f, 7.2f, 3f); loop(7.2f, 16.8f, 3f); loop(16.8f, 16.9f, 3f) }
        }
    }
    val Radio by lazy {
        build("Radio") {
            blot { loop(12f, 12f, 1.6f) }
            ink {
                moveTo(8.3f, 8.6f); curveTo(6.6f, 10.6f, 6.6f, 13.6f, 8.4f, 15.6f)
                moveTo(15.7f, 8.5f); curveTo(17.5f, 10.5f, 17.4f, 13.5f, 15.6f, 15.5f)
                moveTo(5.4f, 5.8f); curveTo(2.2f, 9.4f, 2.3f, 14.8f, 5.6f, 18.3f)
                moveTo(18.6f, 5.7f); curveTo(21.8f, 9.3f, 21.7f, 14.7f, 18.4f, 18.2f)
            }
        }
    }
    val Library by lazy {
        build("Library") {
            ink { stroke(5.2f, 4.2f, 5f, 19.8f); stroke(10f, 4.4f, 10.2f, 19.9f, -0.5f); stroke(14.4f, 5.4f, 19.4f, 19.4f) }
        }
    }
    val Search by lazy { build("Search") { ink { loop(10.8f, 10.8f, 6.3f); stroke(15.8f, 15.9f, 20.4f, 20.6f) } } }
    val Play by lazy {
        build("Play") {
            blot { moveTo(7.6f, 5.4f); curveTo(11.6f, 7.2f, 15.4f, 9.4f, 19f, 12.1f); curveTo(15.4f, 14.6f, 11.6f, 16.9f, 7.5f, 18.7f); curveTo(7f, 14.3f, 7f, 9.8f, 7.6f, 5.4f); close() }
        }
    }
    val Pause by lazy { build("Pause") { ink(4.2f) { stroke(8.2f, 5.8f, 8f, 18.2f); stroke(15.8f, 5.8f, 16f, 18.2f, -0.5f) } } }
    val Next by lazy {
        build("Next") {
            blot { moveTo(5.2f, 6.2f); curveTo(8.4f, 7.8f, 11.4f, 9.8f, 14.4f, 12.1f); curveTo(11.4f, 14.3f, 8.4f, 16.2f, 5.1f, 17.9f); curveTo(4.8f, 14f, 4.8f, 10f, 5.2f, 6.2f); close() }
            ink(3f) { stroke(18.2f, 6.2f, 18.4f, 17.8f) }
        }
    }
    val Previous by lazy {
        build("Previous") {
            blot { moveTo(18.8f, 6.2f); curveTo(15.6f, 7.8f, 12.6f, 9.8f, 9.6f, 12.1f); curveTo(12.6f, 14.3f, 15.6f, 16.2f, 18.9f, 17.9f); curveTo(19.2f, 14f, 19.2f, 10f, 18.8f, 6.2f); close() }
            ink(3f) { stroke(5.8f, 6.2f, 5.6f, 17.8f) }
        }
    }
    private fun PathBuilder.heart() {
        moveTo(12.1f, 19.8f); curveTo(5.2f, 15.6f, 2.8f, 11.8f, 3.2f, 8.6f); curveTo(3.6f, 4.6f, 9f, 2.8f, 12f, 7.4f)
        curveTo(14.8f, 2.9f, 20.6f, 4.4f, 20.9f, 8.8f); curveTo(21.1f, 12f, 18.6f, 15.8f, 12.3f, 19.9f)
    }
    val Heart by lazy { build("Heart") { ink { heart() } } }
    val HeartFilled by lazy { build("HeartFilled") { blot { heart(); close() }; ink { heart() } } }
    val Person by lazy {
        build("Person") {
            ink {
                loop(12f, 7.8f, 3.6f)
                moveTo(4.4f, 19.6f); curveTo(4.8f, 15.6f, 8f, 14.2f, 12f, 14.3f); curveTo(16f, 14.2f, 19.3f, 15.7f, 19.7f, 19.5f); curveTo(14.6f, 20.5f, 9.4f, 20.5f, 4.4f, 19.6f)
            }
        }
    }
    val More by lazy { build("More") { blot { loop(5.5f, 12f, 1.3f); loop(12f, 12.1f, 1.3f); loop(18.5f, 12f, 1.3f) } } }
    val Back by lazy { build("Back") { ink { moveTo(14.6f, 5f); curveTo(12f, 7.2f, 9.8f, 9.6f, 7.8f, 12.1f); curveTo(9.9f, 14.6f, 12.2f, 16.9f, 14.8f, 19f) } } }
    val Close by lazy { build("Close") { ink { stroke(6f, 6.2f, 18f, 17.8f); stroke(18.1f, 6f, 5.9f, 18f, -0.5f) } } }
    val Add by lazy { build("Add") { ink { stroke(12.1f, 4.8f, 11.9f, 19.2f); stroke(4.8f, 12.1f, 19.2f, 11.9f, -0.5f) } } }
    val ChevronDown by lazy { build("ChevronDown") { ink { moveTo(5.2f, 9f); curveTo(7.4f, 11.2f, 9.6f, 13.2f, 12f, 15.2f); curveTo(14.4f, 13.3f, 16.6f, 11.3f, 18.8f, 9.1f) } } }
    val ChevronRight by lazy { build("ChevronRight") { ink { moveTo(9f, 5.2f); curveTo(11.2f, 7.4f, 13.2f, 9.6f, 15.2f, 12f); curveTo(13.3f, 14.4f, 11.3f, 16.6f, 9.1f, 18.8f) } } }
    val Star by lazy {
        build("Star") {
            ink {
                moveTo(12f, 3.6f); curveTo(12.9f, 5.4f, 13.7f, 7.2f, 14.4f, 9f); curveTo(16.4f, 9.1f, 18.4f, 9.4f, 20.4f, 9.8f); curveTo(19f, 11.2f, 17.5f, 12.5f, 16f, 13.8f)
                curveTo(16.5f, 15.7f, 16.9f, 17.6f, 17.2f, 19.6f); curveTo(15.4f, 18.7f, 13.7f, 17.7f, 12f, 16.6f); curveTo(10.3f, 17.7f, 8.6f, 18.7f, 6.8f, 19.6f)
                curveTo(7.1f, 17.6f, 7.5f, 15.7f, 8f, 13.8f); curveTo(6.5f, 12.5f, 5f, 11.2f, 3.6f, 9.8f); curveTo(5.6f, 9.4f, 7.6f, 9.1f, 9.6f, 9f); curveTo(10.3f, 7.2f, 11.1f, 5.4f, 12.1f, 3.7f)
            }
        }
    }

    // ---- Caritas del perfil (se usan en todos los temas) ----
    private fun ImageVector.Builder.face(mouth: PathBuilder.() -> Unit, eyes: (PathBuilder.() -> Unit)? = null) {
        ink(2.4f) { loop(12f, 12f, 9f) }
        if (eyes != null) ink(2.2f, eyes) else ink(2.6f) { stroke(9f, 8.6f, 9.1f, 10.6f); stroke(15f, 8.6f, 14.9f, 10.6f) }
        ink(2.2f, mouth)
    }

    /** Sonrisa amplia. */
    val FaceHappy by lazy { build("FaceHappy") { face(mouth = { moveTo(7.6f, 13.6f); curveTo(9.6f, 17.4f, 14.4f, 17.5f, 16.5f, 13.5f) }) } }

    /** Ojos cerrados y sonrisa leve. */
    val FaceCalm by lazy {
        build("FaceCalm") {
            face(
                mouth = { moveTo(9.2f, 15f); curveTo(10.8f, 16.4f, 13.2f, 16.4f, 14.8f, 15f) },
                eyes = { moveTo(7.6f, 10f); curveTo(8.4f, 11f, 9.8f, 11f, 10.6f, 10f); moveTo(13.4f, 10f); curveTo(14.2f, 11f, 15.6f, 11f, 16.4f, 10f) },
            )
        }
    }

    /** Boca abierta. */
    val FaceEnergetic by lazy {
        build("FaceEnergetic") { face(mouth = { moveTo(8f, 13.4f); curveTo(10.6f, 13f, 13.4f, 13f, 16f, 13.4f); curveTo(15.4f, 18.4f, 8.6f, 18.4f, 8f, 13.5f) }) }
    }

    /** Boca hacia abajo. */
    val FaceMelancholy by lazy { build("FaceMelancholy") { face(mouth = { moveTo(8.2f, 16.6f); curveTo(10f, 13.8f, 14f, 13.8f, 15.8f, 16.6f) }) } }

    /** Boca recta. */
    val FaceNeutral by lazy { build("FaceNeutral") { face(mouth = { stroke(8.6f, 15.2f, 15.4f, 15f) }) } }

    private val replacements: Map<String, ImageVector> by lazy {
        listOf(
            Home, News, Radio, Library, Search, Play, Pause, Next, Previous, Heart, HeartFilled, Person, More, Back, Close, Add,
            ChevronDown, ChevronRight, Star,
        ).associateBy { it.name }
    }

    /** Versión a mano de [icon] (por nombre), o el mismo [icon] si no existe. O(1). */
    fun replacement(icon: ImageVector): ImageVector = replacements[icon.name] ?: icon
}
