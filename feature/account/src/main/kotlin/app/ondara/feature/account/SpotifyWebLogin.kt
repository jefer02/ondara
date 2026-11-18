package app.ondara.feature.account

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import app.ondara.core.designsystem.component.ButtonStyle
import app.ondara.core.designsystem.component.OndaraButton
import app.ondara.core.designsystem.component.OndaraIconButton
import app.ondara.core.designsystem.component.Text
import app.ondara.core.designsystem.icon.OndaraIcons
import app.ondara.core.designsystem.theme.OndaraTheme
import kotlinx.coroutines.delay

private const val SPOTIFY_PLAYER = "https://open.spotify.com"
private const val SPOTIFY_LOGIN = "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"
private const val LOGIN_LOAD_TIMEOUT_MS = 15_000L

@Composable
internal fun SpotifyWebLogin(
    connecting: Boolean,
    event: (AccountEvent) -> Unit,
    modifier: Modifier = Modifier,
    onAlternative: () -> Unit,
) {
    val latestEvent by rememberUpdatedState(event)
    var attempt by remember { mutableIntStateOf(0) }
    var pageLoading by remember(attempt) { mutableStateOf(true) }
    var playerReached by remember(attempt) { mutableStateOf(false) }
    var failure by remember(attempt) { mutableStateOf<String?>(null) }
    val close = { event(AccountEvent.CloseSpotifyLogin) }
    BackHandler { if (!connecting) close() }
    LaunchedEffect(attempt, pageLoading, connecting, failure) {
        if (!pageLoading || connecting || failure != null) return@LaunchedEffect
        delay(LOGIN_LOAD_TIMEOUT_MS)
        failure = "Spotify tardó demasiado en abrir el formulario."
    }
    LaunchedEffect(attempt, playerReached, failure) {
        if (!playerReached || failure != null) return@LaunchedEffect
        while (true) {
            val cookie = CookieManager.getInstance().getCookie(SPOTIFY_PLAYER).orEmpty()
                .split(';').map { it.trim() }.firstOrNull { it.startsWith("sp_dc=") }?.substringAfter('=')
            if (!cookie.isNullOrBlank()) {
                latestEvent(AccountEvent.Cookie(cookie))
                break
            }
            delay(1_000)
        }
    }
    val spacing = OndaraTheme.spacing
    Column(modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            OndaraIconButton(OndaraIcons.Back, "Volver", close, enabled = !connecting)
            Text(if (connecting) "Conectando con Spotify…" else "Iniciar sesión en Spotify", style = OndaraTheme.type.title)
        }
        val error = failure
        if (error != null) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(spacing.screen), contentAlignment = Alignment.Center) {
                ErrorMessage(error)
            }
        } else {
            key(attempt) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = { context ->
                        SpotifyLoginView(
                            context,
                            onLoading = { pageLoading = it },
                            onPlayer = { playerReached = it },
                            onError = { failure = it },
                        )
                    },
                    onRelease = SpotifyLoginView::release,
                )
            }
        }
        if (!connecting) {
            Column(Modifier.fillMaxWidth().padding(horizontal = spacing.screen, vertical = spacing.sm)) {
                if (error == null && pageLoading) Text("Abriendo Spotify…", style = OndaraTheme.type.caption)
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    OndaraButton("Reintentar", { attempt++ }, style = ButtonStyle.TONAL)
                    OndaraButton("Usar Client ID", onAlternative, style = ButtonStyle.GHOST)
                }
            }
        }
    }
}

/** Mantiene la página de Spotify y, si el usuario elige un proveedor, su ventana de inicio de sesión. */
private class SpotifyLoginView(
    context: Context,
    private val onLoading: (Boolean) -> Unit,
    private val onPlayer: (Boolean) -> Unit,
    private val onError: (String) -> Unit,
) : FrameLayout(context) {
    private val login = createWebView()
    private var popup: WebView? = null

    init {
        addView(login, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        login.loadUrl(SPOTIFY_LOGIN)
    }

    private fun createWebView(): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.setSupportMultipleWindows(true)
        CookieManager.getInstance().setAcceptCookie(true)
        webViewClient = Client()
        webChromeClient = Chrome()
    }

    private inner class Client : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            if (view === currentView) {
                onLoading(true)
                onPlayer(false)
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            val page = Uri.parse(url)
            if (page.scheme == "https" && page.host == "open.spotify.com") onPlayer(true)
            if (view === currentView) onLoading(false)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame && view === currentView) {
                onError("No se pudo abrir Spotify. Revisa tu conexión y vuelve a intentarlo.")
            }
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            if (request.isForMainFrame && view === currentView) {
                onError("Spotify no pudo abrir el formulario (HTTP ${errorResponse.statusCode}).")
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            removeView(view)
            onError("El formulario de Spotify se cerró. Vuelve a intentarlo.")
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val transport = resultMsg.obj as? WebView.WebViewTransport
            if (!isUserGesture || popup != null || transport == null) return false
            val child = createWebView()
            popup = child
            addView(child, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            onLoading(true)
            transport.webView = child
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView) {
            if (window !== popup) return
            popup = null
            destroy(window)
            onLoading(login.progress < 100)
        }
    }

    private val currentView: WebView get() = popup ?: login

    fun release() {
        popup?.let(::destroy)
        popup = null
        destroy(login)
    }

    private fun destroy(view: WebView) {
        removeView(view)
        view.stopLoading()
        view.destroy()
    }
}
