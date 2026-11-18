package app.ondara.feature.account

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.viewModelScope
import app.ondara.core.domain.*
import app.ondara.core.model.*
import app.ondara.core.ui.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

data class ProfileUiState(
    val settings: UserSettings = UserSettings(),
    val accounts: AccountsState = AccountsState(),
    val stats: ListeningStats = ListeningStats(),
    val firstPlay: Long? = null,
    val editing: Boolean = false,
    val name: String = "",
    val loading: Boolean = true,
    val error: String? = null,
    val photo: Bitmap? = null,
)
sealed interface ProfileEvent {
    data object Back : ProfileEvent
    data object Edit : ProfileEvent
    data object CancelEdit : ProfileEvent
    data class Name(val value: String) : ProfileEvent
    data object SaveName : ProfileEvent
    data class Avatar(val uri: Uri) : ProfileEvent
    data object CancelCrop : ProfileEvent
    data class SavePhoto(val crop: PhotoCrop) : ProfileEvent
    data class PixelEffect(val enabled: Boolean) : ProfileEvent
    data class PixelBlocks(val blocks: Int) : ProfileEvent
    data object Initials : ProfileEvent
    data object Play : ProfileEvent
    data object Share : ProfileEvent
    data class Error(val message: String) : ProfileEvent
}
sealed interface ProfileEffect { data object Back : ProfileEffect; data object Share : ProfileEffect }

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val settings: SettingsRepository,
    accounts: AccountRepository,
    personal: PersonalRepository,
    library: LibraryRepository,
    private val player: PlayerController,
    @ApplicationContext private val context: Context,
) : MviViewModel<ProfileUiState, ProfileEvent, ProfileEffect>(ProfileUiState()) {
    private val photoWrite = Mutex()

    init {
        viewModelScope.launch { settings.settings.collect { setState { copy(settings = it) } } }
        viewModelScope.launch { accounts.accounts.collect { setState { copy(accounts = it) } } }
        work {
            val stats = personal.stats()
            val first = library.firstPlayAt()
            setState { copy(stats = stats, firstPlay = first, loading = false) }
        }
    }
    override fun onEvent(event: ProfileEvent) {
        when (event) {
            ProfileEvent.Back -> emit(ProfileEffect.Back)
            ProfileEvent.Share -> emit(ProfileEffect.Share)
            ProfileEvent.Edit -> setState { copy(editing = true, name = settings.profileName) }
            ProfileEvent.CancelEdit -> setState { copy(editing = false) }
            is ProfileEvent.Name -> setState { copy(name = event.value) }
            ProfileEvent.SaveName -> work {
                settings.update { it.copy(profileName = state.value.name.trim()) }
                setState { copy(editing = false) }
            }
            is ProfileEvent.Avatar -> work {
                val photo = withContext(Dispatchers.IO) { decodeProfilePhoto(context, event.uri) }
                setState { copy(photo = photo, error = null) }
            }
            ProfileEvent.CancelCrop -> setState { copy(photo = null) }
            is ProfileEvent.SavePhoto -> {
                val photo = state.value.photo ?: return
                setState { copy(photo = null) }
                work {
                    photoWrite.withLock {
                        val path = withContext(Dispatchers.IO) { saveProfilePhoto(context.filesDir, photo, event.crop) }
                        settings.update { it.copy(avatarPath = path, avatarRevision = it.avatarRevision + 1) }
                    }
                }
            }
            is ProfileEvent.PixelEffect -> work { settings.update { it.copy(avatarPixelEffect = event.enabled) } }
            is ProfileEvent.PixelBlocks -> work { settings.update { it.copy(avatarPixelBlocks = event.blocks) } }
            ProfileEvent.Initials -> work { photoWrite.withLock { settings.update { it.copy(avatarPath = "") } } }
            ProfileEvent.Play -> state.value.stats.songOfTheDay?.let {
                player.play(listOf(it), context = PlaybackContext("Perfil", "Canción del día"))
            }
            is ProfileEvent.Error -> setState { copy(error = event.message) }
        }
    }

    private fun work(action: suspend () -> Unit) {
        viewModelScope.launch {
            try { action() } catch (error: CancellationException) { throw error } catch (error: Exception) {
                setState { copy(error = error.message ?: "No se pudo actualizar el perfil", loading = false) }
            }
        }
    }
}
