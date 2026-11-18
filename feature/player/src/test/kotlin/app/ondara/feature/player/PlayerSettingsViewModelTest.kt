package app.ondara.feature.player

import androidx.lifecycle.viewModelScope
import app.ondara.core.domain.CanvasController
import app.ondara.core.domain.LibraryRepository
import app.ondara.core.domain.PlayerController
import app.ondara.core.domain.SettingsRepository
import app.ondara.core.model.CanvasState
import app.ondara.core.model.CanvasSurfaces
import app.ondara.core.model.PlayerState
import app.ondara.core.model.UserSettings
import app.ondara.core.model.VideoOffer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** La misma preferencia observable llega al selector y al reproductor abierto; O(1) por actualización. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerSettingsViewModelTest {
    @Test
    fun `elegir Vinilo actualiza repositorio selector y reproductor abierto`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = MemorySettings()
        val player = mockk<PlayerController>(relaxed = true)
        every { player.state } returns MutableStateFlow(PlayerState())
        val canvas = mockk<CanvasController>(relaxed = true)
        every { canvas.state } returns MutableStateFlow<CanvasState>(CanvasState.Idle)
        every { canvas.surfaces } returns MutableStateFlow(CanvasSurfaces())
        every { canvas.videoOffer } returns MutableStateFlow<VideoOffer?>(null)
        val selector = PlayerSettingsViewModel(repository, player)
        val opened = PlayerViewModel(player, mockk<LibraryRepository>(relaxed = true), canvas, repository)
        try {
            runCurrent()
            selector.onEvent(PlayerSettingsEvent.Layout("VINYL"))
            runCurrent()
            assertEquals("VINYL", repository.settings.value.playerLayout)
            assertEquals("VINYL", selector.state.value.settings.playerLayout)
            assertEquals("VINYL", opened.state.value.settings.playerLayout)
            selector.onEvent(PlayerSettingsEvent.Layout(UserSettings.PLAYER_LAYOUT_THEME))
            runCurrent()
            assertEquals("THEME", opened.state.value.settings.playerLayout)
        } finally {
            selector.viewModelScope.cancel()
            opened.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    private class MemorySettings : SettingsRepository {
        override val settings = MutableStateFlow(UserSettings())
        override suspend fun update(transform: (UserSettings) -> UserSettings) {
            settings.value = transform(settings.value)
        }
    }
}
