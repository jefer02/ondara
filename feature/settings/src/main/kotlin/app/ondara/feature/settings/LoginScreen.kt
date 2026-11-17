package app.ondara.feature.settings

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ondara.core.designsystem.component.OndaraButton
import app.ondara.core.designsystem.component.OndaraIconButton
import app.ondara.core.designsystem.component.Skeleton
import app.ondara.core.designsystem.component.Text
import app.ondara.core.designsystem.icon.OndaraIcons
import app.ondara.core.designsystem.theme.OndaraTheme
import app.ondara.core.ui.CollectEffects
import app.ondara.core.ui.Nav

/** Inicio de sesión oficial aislado de las cuentas que ya están guardadas cifradas en la app. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(onNavigate: (Nav) -> Unit, modifier: Modifier = Modifier, viewModel: LoginViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CollectEffects(viewModel.effects) {
        CookieManager.getInstance().apply {
            removeAllCookies {
                flush()
                onNavigate(Nav.Back)
            }
        }
    }
    Column(modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(OndaraTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            OndaraIconButton(OndaraIcons.Back, "Atrás", { onNavigate(Nav.Back) })
            Text("Iniciar sesión", style = OndaraTheme.type.title)
        }
        when {
            state.checking || state.saved -> Column(Modifier.padding(OndaraTheme.spacing.screen)) {
                Text("Comprobando la cuenta…", style = OndaraTheme.type.headline)
                Skeleton(Modifier.padding(top = OndaraTheme.spacing.md).fillMaxWidth().height(OndaraTheme.spacing.sm))
            }
            state.error != null -> Column(Modifier.padding(OndaraTheme.spacing.screen)) {
                Text(state.error.orEmpty(), style = OndaraTheme.type.body, color = OndaraTheme.colors.error)
                OndaraButton("Reintentar", { viewModel.onEvent(LoginEvent.Retry) })
            }
            else -> AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        val cookies = CookieManager.getInstance()
                        cookies.setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                val uri = Uri.parse(url)
                                if (uri.scheme != "https" || uri.host != "music.youtube.com") return
                                val cookie = cookies.getCookie(MUSIC).orEmpty()
                                if (cookie.split(';').any { it.trim().startsWith("SAPISID=") && it.substringAfter('=').isNotBlank() }) {
                                    viewModel.onEvent(LoginEvent.SessionFound(cookie))
                                }
                            }
                        }
                        cookies.removeAllCookies {
                            cookies.flush()
                            loadUrl(LOGIN)
                        }
                    }
                },
                onRelease = { it.stopLoading(); it.destroy() },
            )
        }
    }
}

private const val MUSIC = "https://music.youtube.com"
private const val LOGIN = "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&continue=https%3A%2F%2Fmusic.youtube.com%2F"
