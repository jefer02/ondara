package app.ondara.core.designsystem.component

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ondara.core.designsystem.theme.OndaraTheme

/** Mide en O(n) al cambiar el texto, el estilo o el ancho. Solo enmascara texto que desborda. */
@Composable
fun MarqueeText(text: String, style: TextStyle, modifier: Modifier = Modifier, alignment: Alignment = Alignment.CenterStart) {
    val measurer = rememberTextMeasurer()
    val reduced = OndaraTheme.motion.reduced
    val edge = with(LocalDensity.current) { OndaraTheme.spacing.xl.toPx() }
    BoxWithConstraints(modifier, contentAlignment = alignment) {
        val width = constraints.maxWidth
        val scrolling = !reduced && remember(text, style, width, measurer) {
            measurer.measure(text, style, softWrap = false, maxLines = 1).size.width > width
        }
        val fade = if (scrolling) Modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                if (size.width > 0f) {
                    val stop = (edge / size.width).coerceAtMost(.5f)
                    drawRect(
                        Brush.horizontalGradient(
                            0f to Color.Transparent, stop to Color.Black,
                            1f - stop to Color.Black, 1f to Color.Transparent,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                }
            }
            .basicMarquee(Int.MAX_VALUE, repeatDelayMillis = 2000, initialDelayMillis = 2000, velocity = 30.dp)
        else Modifier
        BasicText(text, fade, style = style, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}
