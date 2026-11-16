package app.ondara.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ondara.core.model.MusicItem
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Base MVI unidireccional: un `UiState` inmutable en un [StateFlow], eventos de UI que entran por
 * [onEvent] y efectos de un solo uso (navegación, avisos) por [effects].
 */
abstract class MviViewModel<S, E, F>(initial: S) : ViewModel() {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<S> = _state.asStateFlow()

    private val _effects = Channel<F>(Channel.BUFFERED)
    val effects: Flow<F> = _effects.receiveAsFlow()

    abstract fun onEvent(event: E)

    protected fun setState(reduce: S.() -> S) = _state.update(reduce)

    protected fun emit(effect: F) {
        viewModelScope.launch { _effects.send(effect) }
    }
}

/** Consume los efectos de un [MviViewModel] mientras la pantalla esté en composición. */
@Composable
fun <F> CollectEffects(effects: Flow<F>, handler: suspend (F) -> Unit) {
    LaunchedEffect(effects) { effects.collect { handler(it) } }
}

/** Destinos de navegación. Los features los emiten; `:app` los traduce a rutas (ningún feature conoce a otro). */
sealed interface Nav {
    data object Back : Nav
    data class Album(val id: String) : Nav
    data class Artist(val id: String) : Nav
    data class Playlist(val id: String) : Nav
    data class Mood(val title: String, val browseId: String, val params: String?) : Nav
    data object Settings : Nav
    data object Themes : Nav
    data object ThemeEditor : Nav
    data object Showcase : Nav
    data object Login : Nav
}

/** Destino de un elemento no reproducible, o null si es una canción. */
fun MusicItem.toNav(): Nav? = when (this) {
    is MusicItem.AlbumItem -> Nav.Album(album.id)
    is MusicItem.ArtistItem -> Nav.Artist(artist.id)
    is MusicItem.PlaylistItem -> Nav.Playlist(playlist.id)
    is MusicItem.SongItem -> null
}
