package app.ondara.feature.settings

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import app.ondara.core.designsystem.component.OndaraIconButton
import app.ondara.core.designsystem.component.Text
import app.ondara.core.designsystem.icon.OndaraIcons
import app.ondara.core.designsystem.theme.OndaraTheme
import app.ondara.core.ui.Nav

/**
 * Inicio de sesión opcional: se abre la página oficial de Google en un WebView y, al volver a
 * music.youtube.com, se guarda la cookie de sesión **solo en el dispositivo** (DataStore).
 * Ondara nunca ve la contraseña.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(onNavigate: (Nav) -> Unit, modifier: Modifier = Modifier, viewModel: SettingsViewModel = hiltViewModel()) {
    Column(modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(OndaraTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            OndaraIconButton(OndaraIcons.Back, "Atrás", { onNavigate(Nav.Back) })
            Text("Iniciar sesión", style = OndaraTheme.type.title)
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            if (!url.startsWith(MUSIC)) return
                            val cookie = CookieManager.getInstance().getCookie(MUSIC).orEmpty()
                            if ("SAPISID=" in cookie) {
                                viewModel.onEvent(SettingsEvent.SaveCookie(cookie))
                                onNavigate(Nav.Back)
                            }
                        }
                    }
                    loadUrl("https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&continue=$MUSIC")
                }
            },
            onRelease = { it.destroy() },
        )
    }
}

private const val MUSIC = "https://music.youtube.com"
