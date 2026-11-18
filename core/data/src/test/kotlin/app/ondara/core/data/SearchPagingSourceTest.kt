package app.ondara.core.data

import androidx.paging.PagingSource
import app.ondara.core.model.ArtistRef
import app.ondara.core.model.MusicItem
import app.ondara.core.model.MusicVideoType
import app.ondara.core.model.SearchFilter
import app.ondara.core.model.SearchPage
import app.ondara.core.model.Song
import app.ondara.source.innertube.InnerTubeClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SearchPagingSourceTest {
    private val video = Song("v", "Blue (Official Video)", listOf(ArtistRef(null, "Yung Kai")), videoType = MusicVideoType.OFFICIAL_VIDEO)
    private val song = video.copy(id = "s", title = "Blue", videoType = MusicVideoType.SONG)
    private val refresh = PagingSource.LoadParams.Refresh<String>(null, 20, false)

    @Test
    fun `completa solo la primera pagina de todo y conserva continuaciones`() = runTest {
        val client = mockk<InnerTubeClient>()
        coEvery { client.search("blue yung kai", SearchFilter.ALL) } returns SearchPage(listOf(MusicItem.SongItem(video)), "next")
        coEvery { client.search("blue yung kai", SearchFilter.SONGS) } returns SearchPage(listOf(MusicItem.SongItem(song)), null)
        coEvery { client.searchContinuation("next") } returns SearchPage(listOf(MusicItem.SongItem(video)), null)
        val source = SearchPagingSource(client, "blue yung kai", SearchFilter.ALL)
        val page = source.load(refresh) as PagingSource.LoadResult.Page<String, MusicItem>
        assertEquals(listOf(MusicItem.SongItem(song), MusicItem.SongItem(video)), page.data)
        assertEquals("next", page.nextKey)
        val next = source.load(PagingSource.LoadParams.Append("next", 20, false)) as PagingSource.LoadResult.Page<String, MusicItem>
        // Lo ya mostrado en la página 1 no se repite y, sin nada nuevo, la paginación termina.
        assertEquals(emptyList<MusicItem>(), next.data)
        assertEquals(null, next.nextKey)
        coVerify(exactly = 1) { client.search("blue yung kai", SearchFilter.SONGS) }
    }

    @Test
    fun `no hace peticion auxiliar si ya existe equivalente o se usa un filtro`() = runTest {
        val client = mockk<InnerTubeClient>()
        val items = listOf(MusicItem.SongItem(video), MusicItem.SongItem(song))
        coEvery { client.search(any(), any()) } returns SearchPage(items, null)
        SearchPagingSource(client, "blue", SearchFilter.ALL).load(refresh)
        coVerify(exactly = 0) { client.search("blue", SearchFilter.SONGS) }
        SearchPagingSource(client, "blue", SearchFilter.SONGS).load(refresh)
        coVerify(exactly = 1) { client.search("blue", SearchFilter.SONGS) }
    }

    @Test
    fun `un fallo auxiliar conserva resultados pero una cancelacion se propaga`() = runTest {
        val client = mockk<InnerTubeClient>()
        val items = listOf(MusicItem.SongItem(video))
        coEvery { client.search("blue", SearchFilter.ALL) } returns SearchPage(items, null)
        coEvery { client.search("blue", SearchFilter.SONGS) } throws IllegalStateException("Sin conexión")
        val page = SearchPagingSource(client, "blue", SearchFilter.ALL).load(refresh) as PagingSource.LoadResult.Page<String, MusicItem>
        assertEquals(items, page.data)
        coEvery { client.search("blue", SearchFilter.SONGS) } throws CancellationException("Cambio de filtro")
        val error = runCatching { SearchPagingSource(client, "blue", SearchFilter.ALL).load(refresh) }.exceptionOrNull()
        assertTrue(error is CancellationException)
    }
}
