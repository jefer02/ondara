package app.ondara.feature.account

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.BitmapFactory
import android.os.Build
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
import java.io.File
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
)
sealed interface ProfileEvent {
    data object Back : ProfileEvent
    data object Edit : ProfileEvent
    data object CancelEdit : ProfileEvent
    data class Name(val value: String) : ProfileEvent
    data object SaveName : ProfileEvent
    data class Avatar(val uri: Uri?) : ProfileEvent
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
            is ProfileEvent.Avatar -> event.uri?.let { uri -> work {
                val path = copyAvatar(uri)
                settings.update { it.copy(avatarPath = path) }
            } }
            ProfileEvent.Initials -> work { settings.update { it.copy(avatarPath = "") } }
            ProfileEvent.Play -> state.value.stats.songOfTheDay?.let {
                player.play(listOf(it), context = PlaybackContext("Perfil", "Canción del día"))
            }
            is ProfileEvent.Error -> setState { copy(error = event.message) }
        }
    }

    /** Decodifica, centra y reduce a un cuadrado de 512 px. O(w·h) tiempo y memoria del bitmap reducido. */
    private suspend fun copyAvatar(uri: Uri): String = withContext(Dispatchers.IO) {
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val scale = (512f / maxOf(info.size.width, info.size.height)).coerceAtMost(1f)
            decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
        }
        else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
            val options = BitmapFactory.Options().apply {
                inSampleSize = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 1024) inSampleSize *= 2
            }
            requireNotNull(context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }) {
                "No se pudo leer la foto"
            }
        }
        val side = minOf(bitmap.width, bitmap.height)
        val cropped = Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
        try {
            val directory = File(context.filesDir, "profile").apply { mkdirs() }
            val file = File(directory, "avatar_${System.currentTimeMillis()}.jpg")
            file.outputStream().use { check(cropped.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            file.absolutePath
        } finally {
            if (cropped !== bitmap) cropped.recycle()
            bitmap.recycle()
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
