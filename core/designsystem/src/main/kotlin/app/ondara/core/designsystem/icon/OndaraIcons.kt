package app.ondara.core.designsystem.icon

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.ondara.core.designsystem.theme.LocalContentColor

/** Dibuja un icono de [OndaraIcons] con el color de contenido actual. */
@Composable
fun Icon(icon: ImageVector, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = Color.Unspecified, size: Dp = 24.dp) {
    Image(
        imageVector = icon,
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(tint.takeOrElse { LocalContentColor.current }),
    )
}

/**
 * Set de iconos propio de Ondara: trazo de 2 px con extremos redondos sobre lienzo de 24,
 * y versiones rellenas para los controles de transporte.
 */
object OndaraIcons {
    private fun build(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

    private fun ImageVector.Builder.stroke(width: Float = 2f, block: PathBuilder.() -> Unit) = path(
        stroke = SolidColor(Color.Black), strokeLineWidth = width, strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round, pathBuilder = block,
    )

    private fun ImageVector.Builder.fill(block: PathBuilder.() -> Unit) =
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd, pathBuilder = block)

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcTo(r, r, 0f, true, true, cx + r, cy)
        arcTo(r, r, 0f, true, true, cx - r, cy)
        close()
    }

    private fun PathBuilder.line(x1: Float, y1: Float, x2: Float, y2: Float) {
        moveTo(x1, y1)
        lineTo(x2, y2)
    }

    private fun PathBuilder.roundRect(x: Float, y: Float, w: Float, h: Float, r: Float) {
        moveTo(x + r, y)
        lineTo(x + w - r, y)
        arcTo(r, r, 0f, false, true, x + w, y + r)
        lineTo(x + w, y + h - r)
        arcTo(r, r, 0f, false, true, x + w - r, y + h)
        lineTo(x + r, y + h)
        arcTo(r, r, 0f, false, true, x, y + h - r)
        lineTo(x, y + r)
        arcTo(r, r, 0f, false, true, x + r, y)
        close()
    }

    val Home by lazy {
        build("Home") {
            stroke {
                moveTo(4f, 11f); lineTo(12f, 4f); lineTo(20f, 11f); lineTo(20f, 19f)
                arcTo(1f, 1f, 0f, false, true, 19f, 20f); lineTo(5f, 20f); arcTo(1f, 1f, 0f, false, true, 4f, 19f); close()
            }
            stroke { line(10f, 20f, 10f, 15f); lineTo(14f, 15f); lineTo(14f, 20f) }
        }
    }
    val Search by lazy { build("Search") { stroke { circle(11f, 11f, 6.5f); line(16f, 16f, 20.5f, 20.5f) } } }
    val Library by lazy {
        build("Library") { stroke { line(5f, 4f, 5f, 20f); line(10f, 4f, 10f, 20f); moveTo(14.5f, 5f); lineTo(19.5f, 19.5f) } }
    }
    val Play by lazy {
        build("Play") {
            fill {
                moveTo(8f, 5.2f); lineTo(19f, 11.1f); arcTo(1f, 1f, 0f, false, true, 19f, 12.9f); lineTo(8f, 18.8f)
                arcTo(1f, 1f, 0f, false, true, 6.5f, 17.9f); lineTo(6.5f, 6.1f); arcTo(1f, 1f, 0f, false, true, 8f, 5.2f); close()
            }
        }
    }
    val Pause by lazy { build("Pause") { fill { roundRect(6f, 5f, 4.2f, 14f, 1.2f); roundRect(13.8f, 5f, 4.2f, 14f, 1.2f) } } }
    val Next by lazy {
        build("Next") {
            fill { moveTo(5f, 6f); lineTo(14.5f, 12f); lineTo(5f, 18f); close(); roundRect(16.5f, 6f, 2.6f, 12f, 1f) }
        }
    }
    val Previous by lazy {
        build("Previous") {
            fill { moveTo(19f, 6f); lineTo(9.5f, 12f); lineTo(19f, 18f); close(); roundRect(4.9f, 6f, 2.6f, 12f, 1f) }
        }
    }
    val Shuffle by lazy {
        build("Shuffle") {
            stroke {
                moveTo(3f, 7f); lineTo(6f, 7f); curveTo(11f, 7f, 12f, 17f, 17f, 17f); lineTo(21f, 17f)
                moveTo(3f, 17f); lineTo(6f, 17f); curveTo(8f, 17f, 9.2f, 15.6f, 10.2f, 14f)
                moveTo(13.8f, 10f); curveTo(14.8f, 8.4f, 16f, 7f, 17f, 7f); lineTo(21f, 7f)
                moveTo(18.5f, 4.5f); lineTo(21f, 7f); lineTo(18.5f, 9.5f)
                moveTo(18.5f, 14.5f); lineTo(21f, 17f); lineTo(18.5f, 19.5f)
            }
        }
    }
    val Repeat by lazy {
        build("Repeat") {
            stroke {
                moveTo(4f, 11f); lineTo(4f, 10f); arcTo(3f, 3f, 0f, false, true, 7f, 7f); lineTo(20f, 7f)
                moveTo(17f, 4f); lineTo(20f, 7f); lineTo(17f, 10f)
                moveTo(20f, 13f); lineTo(20f, 14f); arcTo(3f, 3f, 0f, false, true, 17f, 17f); lineTo(4f, 17f)
                moveTo(7f, 14f); lineTo(4f, 17f); lineTo(7f, 20f)
            }
        }
    }
    val RepeatOne by lazy {
        build("RepeatOne") {
            stroke {
                moveTo(4f, 11f); lineTo(4f, 10f); arcTo(3f, 3f, 0f, false, true, 7f, 7f); lineTo(20f, 7f)
                moveTo(17f, 4f); lineTo(20f, 7f); lineTo(17f, 10f)
                moveTo(20f, 13f); lineTo(20f, 14f); arcTo(3f, 3f, 0f, false, true, 17f, 17f); lineTo(4f, 17f)
                moveTo(7f, 14f); lineTo(4f, 17f); lineTo(7f, 20f)
            }
            stroke(1.6f) { moveTo(11f, 10.5f); lineTo(12.3f, 9.8f); lineTo(12.3f, 14.2f) }
        }
    }
    private fun PathBuilder.heart() {
        moveTo(12f, 20f)
        curveTo(5f, 15.5f, 3f, 12f, 3f, 8.8f)
        curveTo(3f, 6f, 5.2f, 4f, 7.6f, 4f)
        curveTo(9.4f, 4f, 11f, 5f, 12f, 6.6f)
        curveTo(13f, 5f, 14.6f, 4f, 16.4f, 4f)
        curveTo(18.8f, 4f, 21f, 6f, 21f, 8.8f)
        curveTo(21f, 12f, 19f, 15.5f, 12f, 20f)
        close()
    }
    val Heart by lazy { build("Heart") { stroke { heart() } } }
    val HeartFilled by lazy { build("HeartFilled") { fill { heart() }; stroke { heart() } } }
    val More by lazy { build("More") { fill { circle(5.5f, 12f, 1.7f); circle(12f, 12f, 1.7f); circle(18.5f, 12f, 1.7f) } } }
    val Back by lazy { build("Back") { stroke(2.2f) { moveTo(14.5f, 5f); lineTo(7.5f, 12f); lineTo(14.5f, 19f) } } }
    val ChevronRight by lazy { build("ChevronRight") { stroke { moveTo(9.5f, 6f); lineTo(15.5f, 12f); lineTo(9.5f, 18f) } } }
    val ChevronDown by lazy { build("ChevronDown") { stroke(2.2f) { moveTo(5.5f, 9f); lineTo(12f, 15.5f); lineTo(18.5f, 9f) } } }
    val Close by lazy { build("Close") { stroke { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) } } }
    val Add by lazy { build("Add") { stroke { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) } } }
    val Check by lazy { build("Check") { stroke(2.2f) { moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 7f) } } }
    val Download by lazy {
        build("Download") { stroke { line(12f, 4f, 12f, 15f); moveTo(7.5f, 11f); lineTo(12f, 15.5f); lineTo(16.5f, 11f); line(5f, 20f, 19f, 20f) } }
    }
    val Downloaded by lazy {
        build("Downloaded") { stroke { circle(12f, 12f, 8.5f); moveTo(8f, 12.3f); lineTo(11f, 15.2f); lineTo(16f, 9.2f) } }
    }
    val Queue by lazy {
        build("Queue") {
            stroke { line(4f, 7f, 15f, 7f); line(4f, 12f, 15f, 12f); line(4f, 17f, 11f, 17f) }
            fill { moveTo(15.5f, 14.5f); lineTo(20.5f, 17.5f); lineTo(15.5f, 20.5f); close() }
        }
    }
    val Lyrics by lazy {
        build("Lyrics") {
            stroke {
                moveTo(5f, 5f); lineTo(19f, 5f); arcTo(1.5f, 1.5f, 0f, false, true, 20.5f, 6.5f); lineTo(20.5f, 15.5f)
                arcTo(1.5f, 1.5f, 0f, false, true, 19f, 17f); lineTo(11f, 17f); lineTo(7f, 20.5f); lineTo(7f, 17f); lineTo(5f, 17f)
                arcTo(1.5f, 1.5f, 0f, false, true, 3.5f, 15.5f); lineTo(3.5f, 6.5f); arcTo(1.5f, 1.5f, 0f, false, true, 5f, 5f); close()
                line(7.5f, 9.3f, 16.5f, 9.3f); line(7.5f, 12.8f, 13.5f, 12.8f)
            }
        }
    }
    val Settings by lazy {
        build("Settings") {
            stroke {
                line(4f, 7f, 9f, 7f); line(13f, 7f, 20f, 7f); circle(11f, 7f, 2f)
                line(4f, 17f, 13f, 17f); line(17f, 17f, 20f, 17f); circle(15f, 17f, 2f)
            }
        }
    }
    val Palette by lazy {
        build("Palette") {
            stroke {
                moveTo(12f, 3.5f); arcTo(8.5f, 8.5f, 0f, true, false, 12f, 20.5f)
                curveTo(13.4f, 20.5f, 14f, 19.6f, 13.6f, 18.4f); curveTo(13f, 16.6f, 14f, 15.5f, 15.8f, 15.5f); lineTo(17.5f, 15.5f)
                curveTo(19.2f, 15.5f, 20.5f, 14.2f, 20.5f, 12f); arcTo(8.5f, 8.5f, 0f, false, false, 12f, 3.5f); close()
            }
            fill { circle(8f, 10.5f, 1.2f); circle(11.5f, 7.5f, 1.2f); circle(15.8f, 9f, 1.2f) }
        }
    }
    val Share by lazy {
        build("Share") {
            stroke {
                line(12f, 4f, 12f, 14.5f); moveTo(8f, 7.5f); lineTo(12f, 3.5f); lineTo(16f, 7.5f)
                moveTo(6f, 11.5f); lineTo(6f, 19f); arcTo(1f, 1f, 0f, false, false, 7f, 20f); lineTo(17f, 20f)
                arcTo(1f, 1f, 0f, false, false, 18f, 19f); lineTo(18f, 11.5f)
            }
        }
    }
    val Delete by lazy {
        build("Delete") {
            stroke {
                line(4.5f, 7f, 19.5f, 7f); moveTo(9f, 7f); lineTo(9f, 4.5f); lineTo(15f, 4.5f); lineTo(15f, 7f)
                moveTo(6.5f, 7f); lineTo(7.3f, 19f); arcTo(1f, 1f, 0f, false, false, 8.3f, 20f); lineTo(15.7f, 20f)
                arcTo(1f, 1f, 0f, false, false, 16.7f, 19f); lineTo(17.5f, 7f)
            }
        }
    }
    val Timer by lazy {
        build("Timer") {
            stroke { moveTo(19f, 14f); arcTo(7.5f, 7.5f, 0f, true, true, 10f, 5.8f); curveTo(9f, 9f, 13f, 13.5f, 19f, 14f); close() }
        }
    }
    val Radio by lazy {
        build("Radio") {
            fill { circle(12f, 12f, 2f) }
            stroke {
                moveTo(8.2f, 8.2f); arcTo(5.4f, 5.4f, 0f, false, false, 8.2f, 15.8f)
                moveTo(15.8f, 8.2f); arcTo(5.4f, 5.4f, 0f, false, true, 15.8f, 15.8f)
                moveTo(5.3f, 5.3f); arcTo(9.5f, 9.5f, 0f, false, false, 5.3f, 18.7f)
                moveTo(18.7f, 5.3f); arcTo(9.5f, 9.5f, 0f, false, true, 18.7f, 18.7f)
            }
        }
    }
    val Drag by lazy { build("Drag") { stroke { line(5f, 9f, 19f, 9f); line(5f, 15f, 19f, 15f) } } }
    val Equalizer by lazy {
        build("Equalizer") { stroke(2.4f) { line(6f, 20f, 6f, 11f); line(12f, 20f, 12f, 4f); line(18f, 20f, 18f, 14f) } }
    }
    val Edit by lazy {
        build("Edit") { stroke { moveTo(4f, 20f); lineTo(5f, 15.5f); lineTo(15.5f, 5f); lineTo(19f, 8.5f); lineTo(8.5f, 19f); close(); line(13.5f, 7f, 17f, 10.5f) } }
    }
    val Import by lazy {
        build("Import") {
            stroke {
                line(12f, 15f, 12f, 4.5f); moveTo(7.5f, 10.5f); lineTo(12f, 15f); lineTo(16.5f, 10.5f)
                moveTo(4.5f, 14f); lineTo(4.5f, 18.5f); arcTo(1.5f, 1.5f, 0f, false, false, 6f, 20f); lineTo(18f, 20f)
                arcTo(1.5f, 1.5f, 0f, false, false, 19.5f, 18.5f); lineTo(19.5f, 14f)
            }
        }
    }
    val History by lazy { build("History") { stroke { circle(12f, 12f, 8.5f); moveTo(12f, 7.5f); lineTo(12f, 12f); lineTo(15f, 14f) } } }
    val Playlist by lazy {
        build("Playlist") {
            stroke { line(4f, 6.5f, 20f, 6.5f); line(4f, 11.5f, 14f, 11.5f); line(4f, 16.5f, 10f, 16.5f); line(18.5f, 11f, 18.5f, 18f) }
            fill { circle(16.6f, 18f, 2.1f) }
        }
    }
    val Album by lazy { build("Album") { stroke { circle(12f, 12f, 8.5f); circle(12f, 12f, 2.4f) } } }
    val Person by lazy {
        build("Person") { stroke { circle(12f, 8.3f, 3.6f); moveTo(4.5f, 20f); curveTo(5.2f, 16f, 8.2f, 14.6f, 12f, 14.6f); curveTo(15.8f, 14.6f, 18.8f, 16f, 19.5f, 20f) } }
    }
    val Explicit by lazy {
        build("Explicit") {
            fill { roundRect(4f, 4f, 16f, 16f, 3.5f); moveTo(9.5f, 8f); lineTo(14.8f, 8f); lineTo(14.8f, 9.8f); lineTo(11.4f, 9.8f)
                lineTo(11.4f, 11.1f); lineTo(14.4f, 11.1f); lineTo(14.4f, 12.9f); lineTo(11.4f, 12.9f); lineTo(11.4f, 14.2f); lineTo(14.8f, 14.2f); lineTo(14.8f, 16f); lineTo(9.5f, 16f); close() }
        }
    }
    val Bolt by lazy {
        build("Bolt") { fill { moveTo(13.2f, 3f); lineTo(5.5f, 13.3f); lineTo(11f, 13.3f); lineTo(10f, 21f); lineTo(18.5f, 10.2f); lineTo(12.6f, 10.2f); close() } }
    }
    val Wave by lazy {
        build("Wave") {
            stroke(2.2f) { moveTo(3f, 12f); curveTo(5f, 6f, 7f, 6f, 9f, 12f); curveTo(11f, 18f, 13f, 18f, 15f, 12f); curveTo(16.5f, 7.5f, 18.5f, 7.5f, 21f, 12f) }
        }
    }
}
