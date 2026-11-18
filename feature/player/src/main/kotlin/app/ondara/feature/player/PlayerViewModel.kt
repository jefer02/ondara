package app.ondara.feature.player

import androidx.lifecycle.viewModelScope
import app.ondara.core.domain.LibraryRepository
import app.ondara.core.domain.SettingsRepository
import app.ondara.core.domain.CanvasController
import app.ondara.core.model.UserSettings
import app.ondara.core.model.CanvasState
import app.ondara.core.model.CanvasSurfaces
import app.ondara.core.domain.PlayerController
import app.ondara.core.model.PlayerState
import app.ondara.core.model.VideoOffer
import app.ondara.core.ui.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlayerUiState(
    val player: PlayerState = PlayerState(),
    val favorite: Boolean = false,
    val canvas: CanvasState = CanvasState.Idle,
    val canvasSurfaces: CanvasSurfaces = CanvasSurfaces(),
    val videoOffer: VideoOffer? = null,
    val settings: UserSettings = UserSettings(),
)

sealed interface PlayerEvent {
    data class SeekBy(val deltaMs: Long) : PlayerEvent
    data object NotInterested : PlayerEvent
    data class CanvasActive(val active: Boolean) : PlayerEvent
    data class CanvasSurface(val slot: Int, val surface: Any?) : PlayerEvent
    data object ToggleVideo : PlayerEvent
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
    private val canvas: CanvasController,
    private val settings: SettingsRepository,
) : MviViewModel<PlayerUiState, PlayerEvent, Nothing>(PlayerUiState()) {
    init {
        viewModelScope.launch { canvas.videoOffer.collect { setState { copy(videoOffer = it) } } }
        viewModelScope.launch { canvas.surfaces.collect { setState { copy(canvasSurfaces = it) } } }
        viewModelScope.launch { canvas.state.collect { setState { copy(canvas = it) } } }
        viewModelScope.launch { settings.settings.collect { setState { copy(settings = it) } } }
        viewModelScope.launch { player.state.collect { setState { copy(player = it) } } }
        viewModelScope.launch {
            player.state.map { it.current?.id }.distinctUntilChanged()
                .flatMapLatest { id -> if (id == null) flowOf(false) else library.isFavorite(id) }
                .collect { setState { copy(favorite = it) } }
        }
    }

    fun positionMs(): Long = player.positionMs()

    fun positionFlow(): Flow<Long> = player.positionFlow(500)

    private fun toggleFavorite() {
        state.value.player.current?.let { song -> viewModelScope.launch { library.toggleFavorite(song) } }
    }

    private fun setCanvasActive(active: Boolean) {
        if (active) canvas.start() else canvas.stop()
    }

    override fun onCleared() {
        canvas.stop()
        super.onCleared()
    }

    override fun onEvent(event: PlayerEvent) {
        when (event) {
            is PlayerEvent.SeekBy -> player.seekBy(event.deltaMs)
            PlayerEvent.NotInterested -> player.removeQueueItem(state.value.player.currentIndex)
            is PlayerEvent.CanvasActive -> setCanvasActive(event.active)
            is PlayerEvent.CanvasSurface -> canvas.setSurface(event.slot, event.surface)
            PlayerEvent.ToggleVideo -> canvas.toggleFullVideo()
            PlayerEvent.TogglePlay -> player.togglePlayPause()
            PlayerEvent.Next -> player.next()
            PlayerEvent.Previous -> player.previous()
            is PlayerEvent.SeekFraction -> player.seekTo((state.value.player.durationMs * event.fraction).toLong())
            PlayerEvent.ToggleShuffle -> player.toggleShuffle()
            PlayerEvent.CycleRepeat -> player.cycleRepeat()
            PlayerEvent.ToggleFavorite -> toggleFavorite()
            is PlayerEvent.SkipTo -> player.skipToIndex(event.index)
            is PlayerEvent.MoveQueueItem -> player.moveQueueItem(event.from, event.to)
            is PlayerEvent.RemoveQueueItem -> player.removeQueueItem(event.index)
            is PlayerEvent.SleepTimer -> player.setSleepTimer(event.minutes)
        }
    }
}
