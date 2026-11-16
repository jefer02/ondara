package app.ondara.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import app.ondara.core.designsystem.component.GlassSurface
import app.ondara.core.designsystem.component.LocalBottomInset
import app.ondara.core.designsystem.component.LocalStartInset
import app.ondara.core.designsystem.component.OndaraIconButton
import app.ondara.core.designsystem.component.Text
import app.ondara.core.designsystem.icon.OndaraIcons
import app.ondara.core.designsystem.theme.OndaraTheme

/**
 * Andamio de las pantallas de detalle (álbum, artista, playlist): cabecera con *parallax*,
 * botón de volver flotante y una barra de vidrio con el título que aparece al desplazarse.
 */
@Composable
fun DetailScaffold(
    imageUrl: String?,
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    header: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val listState = rememberLazyListState()
    val collapsed by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f else ((listState.firstVisibleItemScrollOffset - 500) / 240f).coerceIn(0f, 1f)
        }
    }
    val status = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(modifier.fillMaxSize().padding(start = LocalStartInset.current)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = LocalBottomInset.current + OndaraTheme.spacing.xl),
        ) {
            item(key = "__header", contentType = "header") {
                ParallaxHeader(
                    imageUrl, title, subtitle,
                    scrollOffset = { if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset.toFloat() else 0f },
                    content = header,
                )
                Spacer(Modifier.height(OndaraTheme.spacing.md))
            }
            content()
        }
        Box(Modifier.fillMaxWidth().height(status + 52.dp)) {
            GlassSurface(Modifier.fillMaxSize().graphicsLayer { alpha = collapsed }, shape = RoundedCornerShape(0.dp)) {}
            Text(
                title,
                Modifier.align(Alignment.BottomCenter).padding(bottom = 15.dp).widthIn(max = 200.dp).graphicsLayer { alpha = collapsed },
                style = OndaraTheme.type.headline,
                maxLines = 1,
            )
            Row(Modifier.fillMaxWidth().statusBarsPadding().height(52.dp).padding(horizontal = OndaraTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                OndaraIconButton(OndaraIcons.Back, "Atrás", onBack, container = OndaraTheme.colors.background.copy(alpha = 0.45f))
                Spacer(Modifier.weight(1f))
                actions()
            }
        }
    }
}
