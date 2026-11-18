package app.ondara.feature.settings

import androidx.lifecycle.viewModelScope
import app.ondara.core.domain.AccountRepository
import app.ondara.core.domain.SyncRepository
import app.ondara.core.ui.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(val checking: Boolean = false, val saved: Boolean = false, val error: String? = null)

sealed interface LoginEvent {
    data class SessionFound(val cookie: String) : LoginEvent
    data object Retry : LoginEvent
}

sealed interface LoginEffect {
    data object Saved : LoginEffect
}

/** Valida y guarda la cuenta; la cookie pendiente solo permanece en memoria hasta completar el intento. */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val accounts: AccountRepository,
    private val sync: SyncRepository,
) : MviViewModel<LoginUiState, LoginEvent, LoginEffect>(LoginUiState()) {
    private var pendingCookie: String? = null

    override fun onEvent(event: LoginEvent) {
        when (event) {
            is LoginEvent.SessionFound -> {
                if (state.value.checking || state.value.saved || state.value.error != null) return
                pendingCookie = event.cookie
                save()
            }
            LoginEvent.Retry -> save()
        }
    }

    private fun save() {
        val cookie = pendingCookie ?: return
        if (state.value.checking || state.value.saved) return
        setState { copy(checking = true, error = null) }
        viewModelScope.launch {
            try {
                accounts.addYouTubeAccount(cookie)
                pendingCookie = null
                setState { copy(checking = false, saved = true) }
                emit(LoginEffect.Saved)
                sync.syncNow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { copy(checking = false, error = e.message ?: "No se pudo comprobar la cuenta") }
            }
        }
    }
}
