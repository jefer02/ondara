package app.ondara.core.data

import app.ondara.core.database.LibraryDao
import app.ondara.core.database.PlaylistEntity
import app.ondara.core.database.SpotifyDao
import app.ondara.core.model.AccountsState
import app.ondara.core.model.SpotifyAccount
import app.ondara.source.spotify.SpotifyApiException
import app.ondara.source.spotify.SpotifyClient
import app.ondara.source.spotify.SpotifyPlaylist
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpotifySyncTest {
    @Test
    fun `a failed Spotify playlist keeps its previous tracks while other playlists import`() = runTest {
        val spotify = mockk<SpotifyClient>()
        coEvery { spotify.playlists() } returns listOf(
            SpotifyPlaylist("blocked", "Seguida", null, null, 10, null),
            SpotifyPlaylist("owned", "Propia", null, null, 0, null),
        )
        coEvery { spotify.playlistTracks("blocked") } throws SpotifyApiException(403, "Acceso denegado")
        coEvery { spotify.playlistTracks("owned") } returns emptyList()
        coEvery { spotify.savedTracks() } throws SpotifyApiException(503, "No disponible")
        coEvery { spotify.savedAlbums() } returns emptyList()
        val accounts = mockk<AccountStore>()
        coEvery { accounts.awaitLoaded() } returns Unit
        every { accounts.state } returns MutableStateFlow(AccountsState(spotify = SpotifyAccount("user", "Usuario")))
        val library = mockk<LibraryDao>(relaxUnitFun = true)
        coEvery { library.playlistsBySource("SPOTIFY") } returns listOf(
            PlaylistEntity("sp_blocked", "Seguida", 10L, "SPOTIFY"),
            PlaylistEntity("sp_liked", "Me gusta de Spotify", 10L, "SPOTIFY"),
        )
        val matches = mockk<SpotifyDao>(relaxUnitFun = true)
        coEvery { matches.tracks("sp_owned") } returns emptyList()
        val repository = SyncRepositoryImpl(mockk(), mockk(), spotify, accounts, library, matches, mockk(), mockk())

        repository.runSync()

        coVerify(exactly = 0) { library.deletePlaylist(any()) }
        coVerify(exactly = 0) { matches.clearTracks("sp_blocked") }
        coVerify(exactly = 0) { matches.clearTracks("sp_liked") }
        coVerify { library.upsertPlaylist(match { it.id == "sp_owned" }) }
        assertTrue(repository.progress.value.error.orEmpty().contains("Importación parcial"))
        assertTrue(repository.progress.value.error.orEmpty().contains("Seguida: Acceso denegado"))
    }
}
