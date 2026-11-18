package app.ondara.feature.account

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.ondara.core.designsystem.component.ButtonStyle
import app.ondara.core.designsystem.component.OndaraButton
import app.ondara.core.designsystem.component.ScreenScaffold
import app.ondara.core.designsystem.component.Text
import app.ondara.core.designsystem.component.pressable
import app.ondara.core.designsystem.icon.Icon
import app.ondara.core.designsystem.icon.OndaraIcons
import app.ondara.core.designsystem.theme.OndaraTheme
import app.ondara.core.ui.Nav

/** Autoría de la app: se muestra en Acerca de, la hoja de Cuenta y el primer arranque. */
object OndaraAuthor {
    const val NAME = "Jeferson Manuel Morillo Vallejo"
    const val GITHUB_URL = "https://github.com/jefer02"
    const val INSTAGRAM_URL = "https://www.instagram.com/morillo9725"
    internal const val INSTAGRAM_PACKAGE = "com.instagram.android"
}

/**
 * Abre [url] con `ACTION_VIEW`. Si se indica [preferredPackage] y esa app está instalada (p. ej. Instagram),
 * se intenta primero con ella; si no, decide el sistema (navegador). Devuelve `false` si nada pudo abrirlo.
 */
internal fun openExternalLink(context: Context, url: String, preferredPackage: String? = null): Boolean {
    val uri = Uri.parse(url)
    val base = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (preferredPackage != null) {
        try {
            context.startActivity(Intent(base).setPackage(preferredPackage))
            return true
        } catch (_: ActivityNotFoundException) {
            // La app preferida no está instalada: se cae al navegador.
        }
    }
    return try {
        context.startActivity(base)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/** Logotipo de Ondara dibujado con los tokens del tema activo (la onda sobre un degradado del acento). */
@Composable
internal fun OndaraLogo(modifier: Modifier = Modifier, size: Dp = 88.dp) {
    val colors = OndaraTheme.colors
    Box(
        modifier
            .size(size)
            .clip(OndaraTheme.shapes.large)
            .background(Brush.linearGradient(listOf(colors.accent, colors.blockPrimary))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(OndaraIcons.Wave, "Logotipo de Ondara", tint = colors.onAccent, size = size * 0.5f)
    }
}

@Composable
fun AboutScreen(onNavigate: (Nav) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    val spacing = OndaraTheme.spacing
    ScreenScaffold("Acerca de Ondara", modifier, onBack = { onNavigate(Nav.Back) }) {
        item(key = "marca", contentType = "cabecera") {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = spacing.screen, vertical = spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                OndaraLogo()
                Text("Ondara", style = OndaraTheme.type.display)
                if (version.isNotEmpty()) Text("Versión $version", style = OndaraTheme.type.caption, color = OndaraTheme.colors.onMuted)
            }
        }
        item(key = "autor", contentType = "autor") {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = spacing.screen, vertical = spacing.md)
                    .clip(OndaraTheme.shapes.large).background(OndaraTheme.colors.surface).padding(spacing.lg),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                Text("Creada por ${OndaraAuthor.NAME}", style = OndaraTheme.type.headline)
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    OndaraButton(
                        "GitHub", { openExternalLink(context, OndaraAuthor.GITHUB_URL) }, Modifier.weight(1f),
                        style = ButtonStyle.TONAL, icon = OndaraIcons.Link,
                    )
                    OndaraButton(
                        "Instagram",
                        { openExternalLink(context, OndaraAuthor.INSTAGRAM_URL, OndaraAuthor.INSTAGRAM_PACKAGE) },
                        Modifier.weight(1f), style = ButtonStyle.TONAL, icon = OndaraIcons.Link,
                    )
                }
            }
        }
        item(key = "créditos", contentType = "texto") {
            Column(
                Modifier.padding(horizontal = spacing.screen, vertical = spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.lg),
            ) {
                Text("Licencia GPL-3.0")
                Text(
                    "YouTube Music vía InnerTube · NewPipe Extractor · Echo Music y Metrolist por las técnicas de sesión, " +
                        "Spotify y canvas · LRCLIB / KuGou",
                )
                Text("Tipografías OFL: Space Grotesk, Inter, JetBrains Mono, Doto, Fraunces, Unbounded, Fredoka y Nunito.")
                Text("Ondara no está afiliada a YouTube, Spotify ni Apple.", color = OndaraTheme.colors.onMuted)
            }
        }
    }
}

/** Pie discreto de autoría; [onClick] lleva a "Acerca de". */
@Composable
internal fun AuthorFooter(text: String, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val clickable = if (onClick != null) Modifier.pressable(onClick = onClick) else Modifier
    Text(
        text,
        modifier.fillMaxWidth().then(clickable).padding(vertical = OndaraTheme.spacing.sm),
        style = OndaraTheme.type.caption,
        color = OndaraTheme.colors.onMuted,
        textAlign = TextAlign.Center,
    )
}
