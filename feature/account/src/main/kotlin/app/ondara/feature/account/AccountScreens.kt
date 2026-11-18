package app.ondara.feature.account

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.ondara.core.designsystem.component.*
import app.ondara.core.designsystem.icon.Icon
import app.ondara.core.designsystem.icon.OndaraIcons
import app.ondara.core.designsystem.theme.OndaraTheme
import app.ondara.core.model.SyncProgress
import app.ondara.core.ui.*

@Composable
fun AccountSheetContent(
    onNavigate: (Nav) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AccountViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    CollectEffects(viewModel.effects) { effect ->
        if (effect is AccountEffect.Navigate) { onDismiss(); onNavigate(effect.nav) }
    }
    val event = viewModel::onEvent
    Column(
        modifier.fillMaxWidth().heightIn(max = 680.dp).verticalScroll(rememberScrollState()).padding(OndaraTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(OndaraTheme.spacing.md),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(OndaraTheme.spacing.md)) {
            Avatar(ProfileBadge(state.settings), size = 64.dp)
            Column {
                Text(state.settings.profileName.ifBlank { "Tu perfil" }, style = OndaraTheme.type.title)
                OndaraButton("Ver perfil", { event(AccountEvent.Navigate(Nav.Profile)) }, style = ButtonStyle.GHOST)
            }
        }
        Text("Cuentas conectadas", style = OndaraTheme.type.headline)
        Row { Icon(OndaraIcons.YouTube, null); Text("YouTube Music") }
        Text(state.accounts.activeYouTube?.let { "Conectado como ${it.name}" } ?: "Sin conectar")
        state.accounts.youtube.forEach { account ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                OndaraButton(
                    account.name + if (account.id == state.accounts.activeYouTubeId) " · Activa" else " · Cambiar",
                    { event(AccountEvent.Switch(account.id)) }, style = ButtonStyle.GHOST, enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                )
                OndaraIconButton(OndaraIcons.Close, "Cerrar sesión de ${account.name}", { event(AccountEvent.AskRemove(account.id)) })
            }
        }
        OndaraButton(
            if (state.accounts.youtube.isEmpty()) "Conectar" else "Añadir otra cuenta",
            { event(AccountEvent.Navigate(Nav.Login)) }, style = ButtonStyle.TONAL,
        )
        Row { Icon(OndaraIcons.Spotify, null); Text("Spotify") }
        Text(state.accounts.spotify?.let { "Conectado como ${it.name}" } ?: "Sin conectar")
        OndaraButton(
            "Importar desde Spotify",
            { event(AccountEvent.Navigate(Nav.SpotifyConnect)) },
            style = ButtonStyle.TONAL, enabled = !state.busy,
        )
        ImportProgress(state.progress)
        if (state.accounts.anyConnected) OndaraButton("Sincronizar ahora", { event(AccountEvent.Sync) }, enabled = !state.progress.running)
        if (state.accounts.youtube.isNotEmpty()) {
            Text("Sincronizar Me gusta con YouTube Music")
            OndaraSwitch(state.settings.syncLikes, { event(AccountEvent.Likes(it)) }, Modifier.semantics { contentDescription = "Sincronizar Me gusta con YouTube Music" })
        }
        ErrorMessage(state.error)
        listOf("Temas" to Nav.Themes, "Ajustes" to Nav.Settings, "Descargas" to Nav.Downloads, "Acerca de" to Nav.About).forEach { (title, nav) ->
            OndaraButton(title, { event(AccountEvent.Navigate(nav)) }, modifier = Modifier.fillMaxWidth(), style = ButtonStyle.GHOST)
        }
        AuthorFooter("Ondara · por ${OndaraAuthor.NAME}", onClick = { event(AccountEvent.Navigate(Nav.About)) })
    }
    if (state.removal != null) OndaraDialog(
        "Cerrar sesión", { event(AccountEvent.AskRemove(null)) }, "Desconectar", { event(AccountEvent.Remove) },
        text = "Se eliminará la sesión guardada de esta cuenta de YouTube Music.",
    )
}

@Composable
internal fun ErrorMessage(message: String?) {
    if (message != null) Text(message, color = OndaraTheme.colors.error)
}

@Composable
internal fun ImportProgress(progress: SyncProgress) {
    val motion = OndaraTheme.motion
    val fraction = animateFloatAsState(progress.fraction.coerceIn(0f, 1f), motion.spring(), label = "Importación")
    val colors = OndaraTheme.colors
    if (progress.running) {
        Text(progress.message.ifBlank { "Importando…" }, style = OndaraTheme.type.caption)
        Canvas(
            Modifier.fillMaxWidth().height(OndaraTheme.spacing.sm).semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(progress.fraction.coerceIn(0f, 1f), 0f..1f)
            },
        ) {
            drawRoundRect(colors.surfaceHigh, cornerRadius = CornerRadius(size.height))
            drawRoundRect(colors.accent, size = Size(size.width * fraction.value, size.height), cornerRadius = CornerRadius(size.height))
        }
    }
    ErrorMessage(progress.error)
}

@Composable
fun SpotifyConnectScreen(
    onNavigate: (Nav) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SpotifyConnectViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val event = viewModel::onEvent
    CollectEffects(viewModel.effects) { effect ->
        when (effect) {
            is AccountEffect.Navigate -> onNavigate(effect.nav)
            is AccountEffect.OpenUrl -> runCatching {
                CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(effect.url))
            }.onFailure { event(AccountEvent.Error("No se pudo abrir el navegador: ${it.message}")) }
        }
    }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    if (state.spotifyWebLogin && state.accounts.spotify == null) {
        SpotifyWebLogin(state.busy, event, modifier, onAlternative = {
            event(AccountEvent.CloseSpotifyLogin)
            showAdvanced = true
        })
        return
    }

    ScreenScaffold("Spotify", modifier, onBack = { event(AccountEvent.Navigate(Nav.Back)) }) {
        item(key = "spotify", contentType = "conexión") {
            Column(Modifier.padding(horizontal = OndaraTheme.spacing.screen), verticalArrangement = Arrangement.spacedBy(OndaraTheme.spacing.lg)) {
                Text("Importa tus playlists, canciones y álbumes guardados. Ondara busca sus equivalentes en YouTube Music.")
                val spotify = state.accounts.spotify
                if (spotify != null) {
                    Text("Conectado como ${spotify.name}", style = OndaraTheme.type.title)
                    ImportProgress(state.progress)
                    OndaraButton("Importar biblioteca", { event(AccountEvent.Sync) }, enabled = !state.progress.running && !state.busy)
                    OndaraButton("Sin coincidencia (${state.unmatched})", { event(AccountEvent.Navigate(Nav.Unmatched)) })
                    OndaraButton("Desconectar", { event(AccountEvent.Disconnect) }, enabled = !state.busy)
                } else {
                    Text("La conexión web usa tu sesión del reproductor de Spotify y puede dejar de funcionar si Spotify cambia su servicio.",
                        style = OndaraTheme.type.caption)
                    OndaraButton("Iniciar sesión en Spotify", { event(AccountEvent.OpenSpotifyLogin) }, enabled = !state.busy)
                    OndaraButton(
                        if (showAdvanced) "Ocultar conexión con Client ID" else "Conexión con Client ID",
                        { showAdvanced = !showAdvanced }, style = ButtonStyle.GHOST,
                    )
                    if (showAdvanced) SpotifyClientIdSettings(state, event)
                }
                ErrorMessage(state.error)
            }
        }
    }
}

@Composable
private fun SpotifyClientIdSettings(state: AccountUiState, event: (AccountEvent) -> Unit) {
    Text("En Spotify for Developers, crea una app con Web API y añade ondara://spotify-callback como Redirect URI.")
    OndaraButton("Abrir Spotify for Developers", { event(AccountEvent.Developers) }, style = ButtonStyle.GHOST)
    OndaraTextField(state.settings.spotifyClientId, { event(AccountEvent.ClientId(it)) }, placeholder = "Client ID")
    OndaraButton("Conectar con Client ID", { event(AccountEvent.Authorize) }, enabled = !state.busy)
}
