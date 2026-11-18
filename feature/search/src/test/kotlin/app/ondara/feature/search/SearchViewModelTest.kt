package app.ondara.feature.search

import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import app.ondara.core.domain.CatalogRepository
import app.ondara.core.domain.LibraryRepository
import app.ondara.core.domain.PlayerController
import app.ondara.core.domain.Resource
import app.ondara.core.model.MusicItem
import app.ondara.core.model.SearchFilter
import app.ondara.core.model.ArtistRef
import app.ondara.core.model.MusicVideoType
import app.ondara.core.model.Song
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    @Test
    fun `consultas unicode llegan intactas y cada filtro inicia una solicitud propia`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val catalog = mockk<CatalogRepository>()
        val library = mockk<LibraryRepository>(relaxed = true)
        val player = mockk<PlayerController>(relaxed = true)
        every { catalog.moods() } returns flowOf(Resource.Success(emptyList(), fromCache = false))
        every { library.searchHistory() } returns flowOf(emptyList())
        every { catalog.search(any(), any()) } returns flowOf(PagingData.empty<MusicItem>())
        coEvery { catalog.suggestions(any()) } returns emptyList()
        val model = SearchViewModel(catalog, library, player)
        try {
            backgroundScope.launch { model.results.collect() }
            runCurrent()
            val video = Song("video", "Blue", listOf(ArtistRef(null, "Yung Kai")), videoType = MusicVideoType.OFFICIAL_VIDEO)
            val song = video.copy(id = "audio", videoType = MusicVideoType.SONG)
            val item = MusicItem.SongItem(video)
            val equivalent = searchResults(listOf(item, MusicItem.SongItem(song)), SearchFilter.ALL).equivalents[video.id]
            model.onEvent(SearchEvent.Open(item, equivalent))
            verify { player.startRadio(song) }
            for (query in listOf("blue yung kai", "canción ñandú", "🔥 bad bunny")) {
                model.onEvent(SearchEvent.Submit(query))
                runCurrent()
                assertEquals(query, model.state.value.submitted)
                coVerify { library.addSearch(query) }
                for (filter in SearchFilter.entries) {
                    model.onEvent(SearchEvent.FilterChanged(filter))
                    runCurrent()
                    verify { catalog.search(query, filter) }
                }
            }
        } finally {
            model.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }
}
