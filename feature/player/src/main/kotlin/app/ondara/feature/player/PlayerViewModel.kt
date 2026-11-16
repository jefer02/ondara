package app.ondara.feature.player

import androidx.lifecycle.viewModelScope
import app.ondara.core.domain.LibraryRepository
import app.ondara.core.domain.PlayerController
import app.ondara.core.model.PlayerState
import app.ondara.core.ui.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlayerUiState(val player: PlayerState = PlayerState(), val favorite: Boolean = false)

sealed interface PlayerEvent {
    data object TogglePlay : PlayerEvent
    data object Next : PlayerEvent
    data object Previous : PlayerEvent
    data class SeekFraction(val fraction: Float) : PlayerEvent
    data object ToggleShuffle : PlayerEvent
    data object CycleRepeat : PlayerEvent
    data object ToggleFavorite : PlayerEvent
    data class SkipTo(val index: Int) : PlayerEvent
    data class MoveQueueItem(val from: Int, val to: Int) : PlayerEvent
    data class RemoveQueueItem(val index: Int) : PlayerEvent
    data class SleepTimer(val minutes: Int?) : PlayerEvent
}

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val player: PlayerController,
    private val library: LibraryRepository,
) : MviViewModel<PlayerUiState, PlayerEvent, Nothing>(PlayerUiState()) {
    init {
        viewModelScope.launch { player.state.collect { setState { copy(player = it) } } }
        viewModelScope.launch {
            player.state.map { it.current?.id }.distinctUntilChanged()
                .flatMapLatest { id -> if (id == null) flowOf(false) else library.isFavorite(id) }
                .collect { setState { copy(favorite = it) } }
        }
    }

    fun positionMs(): Long = player.positionMs()

    fun positionFlow(): Flow<Long> = player.positionFlow(500)

    override fun onEvent(event: PlayerEvent) {
        when (event) {
            PlayerEvent.TogglePlay -> player.togglePlayPause()
            PlayerEvent.Next -> player.next()
            PlayerEvent.Previous -> player.previous()
            is PlayerEvent.SeekFraction -> player.seekTo((state.value.player.durationMs * event.fraction).toLong())
            PlayerEvent.ToggleShuffle -> player.toggleShuffle()
            PlayerEvent.CycleRepeat -> player.cycleRepeat()
            PlayerEvent.ToggleFavorite -> state.value.player.current?.let { song -> viewModelScope.launch { library.toggleFavorite(song) } }
            is PlayerEvent.SkipTo -> player.skipToIndex(event.index)
            is PlayerEvent.MoveQueueItem -> player.moveQueueItem(event.from, event.to)
            is PlayerEvent.RemoveQueueItem -> player.removeQueueItem(event.index)
            is PlayerEvent.SleepTimer -> player.setSleepTimer(event.minutes)
        }
    }
}
