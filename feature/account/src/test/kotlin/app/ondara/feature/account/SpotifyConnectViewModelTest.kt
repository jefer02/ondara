package app.ondara.feature.account

import app.ondara.core.domain.AccountRepository
import app.ondara.core.domain.SettingsRepository
import app.ondara.core.domain.SyncRepository
import app.ondara.core.model.AccountsState
import app.ondara.core.model.SpotifyAccount
import app.ondara.core.model.SyncProgress
import app.ondara.core.model.UserSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpotifyConnectViewModelTest {
    @Test
    fun `web sign in needs no client id and queues one import after connection succeeds`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val account = mockk<AccountRepository>()
            every { account.accounts } returns MutableStateFlow(AccountsState())
            val settings = mockk<SettingsRepository>()
            every { settings.settings } returns MutableStateFlow(UserSettings())
            val sync = mockk<SyncRepository>(relaxUnitFun = true)
            every { sync.progress } returns MutableStateFlow(SyncProgress())
            every { sync.unmatched() } returns flowOf(emptyList())
            val viewModel = SpotifyConnectViewModel(account, settings, sync)
            runCurrent()

            val session = AccountEvent.Cookie("fixture")
            coEvery { account.connectSpotifyWithCookie(session.value) } throws IllegalStateException("Sesión rechazada")
            viewModel.onEvent(session)
            viewModel.onEvent(AccountEvent.OpenSpotifyLogin)
            assertTrue(viewModel.state.value.spotifyWebLogin)
            viewModel.onEvent(session)
            runCurrent()
            assertFalse(viewModel.state.value.spotifyWebLogin)
            assertTrue(viewModel.state.value.error.orEmpty().contains("Sesión rechazada"))
            verify(exactly = 0) { sync.syncNow() }

            coEvery { account.connectSpotifyWithCookie(session.value) } returns SpotifyAccount("user", "Usuario")
            viewModel.onEvent(AccountEvent.OpenSpotifyLogin)
            viewModel.onEvent(session)
            viewModel.onEvent(session)
            runCurrent()
            coVerify(exactly = 2) { account.connectSpotifyWithCookie(session.value) }
            coVerify(exactly = 0) { settings.update(any()) }
            verify(exactly = 1) { sync.syncNow() }
            assertFalse(viewModel.state.value.spotifyWebLogin)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
