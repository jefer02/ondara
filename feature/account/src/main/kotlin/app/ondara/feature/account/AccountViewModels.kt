package app.ondara.feature.account

import androidx.lifecycle.viewModelScope
import app.ondara.core.domain.AccountRepository
import app.ondara.core.domain.SettingsRepository
import app.ondara.core.domain.SyncRepository
import app.ondara.core.model.AccountsState
import app.ondara.core.model.SyncProgress
import app.ondara.core.model.UserSettings
import app.ondara.core.ui.MviViewModel
import app.ondara.core.ui.Nav
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import javax.inject.Inject

data class AccountUiState(
    val accounts: AccountsState = AccountsState(),
    val settings: UserSettings = UserSettings(),
    val progress: SyncProgress = SyncProgress(),
    val unmatched: Int = 0,
    val removal: String? = null,
    val spotifyWebLogin: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

sealed interface AccountEvent {
    data class Navigate(val nav: Nav) : AccountEvent
    data class Switch(val id: String) : AccountEvent
    data class AskRemove(val id: String?) : AccountEvent
    data object Remove : AccountEvent
    data object Disconnect : AccountEvent
    data object Sync : AccountEvent
    data class Likes(val enabled: Boolean) : AccountEvent
    data class ClientId(val value: String) : AccountEvent
    data object Authorize : AccountEvent
    data object Developers : AccountEvent
    data object OpenSpotifyLogin : AccountEvent
    data object CloseSpotifyLogin : AccountEvent
    data class Cookie(val value: String) : AccountEvent
    data class Error(val message: String) : AccountEvent
}

sealed interface AccountEffect {
    data class Navigate(val nav: Nav) : AccountEffect
    data class OpenUrl(val url: String) : AccountEffect
}

open class ConnectedAccountsViewModel(
    private val account: AccountRepository,
    private val settings: SettingsRepository,
    private val sync: SyncRepository,
) : MviViewModel<AccountUiState, AccountEvent, AccountEffect>(AccountUiState()) {
    private var connectingCookie = false
    private var clientIdSave: Job? = null
    private var clientIdDraft: String? = null

    init {
        viewModelScope.launch {
            combine(account.accounts, settings.settings, sync.progress, sync.unmatched()) { accounts, preferences, progress, unmatched ->
                AccountUiState(accounts, preferences, progress, unmatched.size)
            }.collect { latest ->
                setState {
                    latest.copy(
                        settings = latest.settings.copy(spotifyClientId = clientIdDraft ?: latest.settings.spotifyClientId),
                        removal = removal, spotifyWebLogin = spotifyWebLogin, busy = busy, error = error,
                    )
                }
            }
        }
    }

    override fun onEvent(event: AccountEvent) {
        when (event) {
            is AccountEvent.Navigate -> emit(AccountEffect.Navigate(event.nav))
            is AccountEvent.AskRemove -> setState { copy(removal = event.id) }
            AccountEvent.Sync -> perform { sync.syncNow() }
            is AccountEvent.Switch -> perform { account.switchYouTubeAccount(event.id) }
            AccountEvent.Remove -> state.value.removal?.let { id ->
                setState { copy(removal = null) }
                perform { account.removeYouTubeAccount(id) }
            }
            AccountEvent.Disconnect -> perform { account.disconnectSpotify() }
            is AccountEvent.Likes -> perform { settings.update { it.copy(syncLikes = event.enabled) } }
            is AccountEvent.ClientId -> {
                clientIdDraft = event.value
                setState { copy(settings = settings.copy(spotifyClientId = event.value)) }
                clientIdSave?.cancel()
                clientIdSave = viewModelScope.launch {
                    delay(300)
                    perform { settings.update { it.copy(spotifyClientId = event.value.trim()) } }
                }
            }
            AccountEvent.Developers -> emit(AccountEffect.OpenUrl("https://developer.spotify.com/dashboard"))
            AccountEvent.Authorize -> perform {
                val clientId = state.value.settings.spotifyClientId.trim()
                require(clientId.isNotBlank()) { "Pega primero tu Client ID" }
                clientIdSave?.cancel()
                settings.update { it.copy(spotifyClientId = clientId) }
                emit(AccountEffect.OpenUrl(account.spotifyAuthorizationUrl()))
            }
            AccountEvent.OpenSpotifyLogin -> setState { copy(spotifyWebLogin = true, error = null) }
            AccountEvent.CloseSpotifyLogin -> setState { copy(spotifyWebLogin = false) }
            is AccountEvent.Cookie -> connectCookie(event.value)
            is AccountEvent.Error -> setState { copy(error = event.message) }
        }
    }

    private fun connectCookie(cookie: String) {
        if (!state.value.spotifyWebLogin || connectingCookie || state.value.accounts.spotify != null) return
        connectingCookie = true
        perform {
            try {
                account.connectSpotifyWithCookie(cookie)
                sync.syncNow()
            } finally {
                connectingCookie = false
                setState { copy(spotifyWebLogin = false) }
            }
        }
    }

    private fun perform(action: suspend () -> Unit) {
        viewModelScope.launch {
            setState { copy(busy = true, error = null) }
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                setState { copy(error = error.message ?: "No se pudo completar la operación") }
            } finally {
                setState { copy(busy = false) }
            }
        }
    }
}

@HiltViewModel
class AccountViewModel @Inject constructor(account: AccountRepository, settings: SettingsRepository, sync: SyncRepository) :
    ConnectedAccountsViewModel(account, settings, sync)

@HiltViewModel
class SpotifyConnectViewModel @Inject constructor(account: AccountRepository, settings: SettingsRepository, sync: SyncRepository) :
    ConnectedAccountsViewModel(account, settings, sync)
