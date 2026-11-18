package app.ondara.source.innertube

import app.ondara.core.model.MusicVideoType
import app.ondara.core.model.MusicItem
import app.ondara.core.model.SearchFilter
import app.ondara.core.network.HttpClientFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Tests contra respuestas reales de InnerTube grabadas en `src/test/resources/fixtures`. */
class InnerTubeParserTest {
    private fun fixtureText(name: String): String =
        checkNotNull(javaClass.getResource("/fixtures/$name.json")) { "falta el fixture $name" }.readText()

    private fun fixture(name: String): JsonObject = Json.parseToJsonElement(fixtureText(name)).jsonObject

    @Test
    fun `tarjetas de dos filas conservan todos los tipos de reproduccion`() {
        val types = mapOf(
            "ATV" to MusicVideoType.SONG,
            "OMV" to MusicVideoType.OFFICIAL_VIDEO,
            "UGC" to MusicVideoType.USER_VIDEO,
            "PODCAST_EPISODE" to MusicVideoType.PODCAST,
            "SHOULDER" to MusicVideoType.UNKNOWN,
        )
        for ((remote, expected) in types) {
            val renderer = Json.parseToJsonElement(
                """{
                    "title":{"runs":[{"text":"Título"}]},
                    "navigationEndpoint":{"watchEndpoint":{"videoId":"id",
                        "watchEndpointMusicSupportedConfigs":{"watchEndpointMusicConfig":{"musicVideoType":"MUSIC_VIDEO_TYPE_$remote"}}}}
                }""",
            )
            assertEquals(expected, (InnerTubeParser.twoRowItem(renderer) as MusicItem.SongItem).song.videoType)
        }
    }

    @Test
    fun `renderers incompletos se descartan sin lanzar`() {
        assertTrue(InnerTubeParser.search(fixture("search_incomplete")).items.isEmpty())
    }

    @Test
    fun `sin artista ni duracion conserva valores por defecto y lee endpoint directo`() {
        val renderer = Json.parseToJsonElement(
            """{
                "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Título"}]}}}],
                "navigationEndpoint":{"watchEndpoint":{"videoId":"id",
                    "watchEndpointMusicSupportedConfigs":{"watchEndpointMusicConfig":{"musicVideoType":"MUSIC_VIDEO_TYPE_UGC"}}}}
            }""",
        )
        val song = (InnerTubeParser.listItem(renderer) as MusicItem.SongItem).song
        assertEquals(0, song.durationSec)
        assertTrue(song.artists.isEmpty())
        assertEquals(MusicVideoType.USER_VIDEO, song.videoType)
    }

    @Test
    fun `search songs yields songs with artist, duration and continuation`() {
        val page = InnerTubeParser.search(fixture("search_songs"))
        val songs = page.items.filterIsInstance<MusicItem.SongItem>().map { it.song }
        assertEquals(page.items.size, songs.size)
        assertTrue(songs.size >= 10)
        assertTrue(songs.all { it.videoType == MusicVideoType.SONG })
        assertTrue(songs.all { it.id.length == 11 && it.title.isNotBlank() })
        assertTrue(songs.count { it.durationSec > 0 } >= songs.size - 1)
        assertTrue(songs.any { s -> s.artists.any { it.name == "Daft Punk" && it.id != null } })
        assertTrue(songs.any { it.album != null })
        assertNotNull(page.continuation)
    }

    @Test
    fun `filtered searches yield the right item types`() {
        val albums = InnerTubeParser.search(fixture("search_albums")).items
        assertTrue(albums.isNotEmpty() && albums.all { it is MusicItem.AlbumItem })
        assertTrue(albums.any { (it as MusicItem.AlbumItem).album.id.startsWith("MPRE") })

        val artists = InnerTubeParser.search(fixture("search_artists")).items
        assertTrue(artists.isNotEmpty() && artists.all { it is MusicItem.ArtistItem })
        assertEquals("Daft Punk", (artists.first() as MusicItem.ArtistItem).artist.name)

        val playlists = InnerTubeParser.search(fixture("search_playlists")).items
        assertTrue(playlists.isNotEmpty() && playlists.all { it is MusicItem.PlaylistItem })
        assertTrue(playlists.none { (it as MusicItem.PlaylistItem).playlist.id.startsWith("VL") })
    }

    @Test
    fun `unfiltered search mixes types and has no continuation`() {
        val page = InnerTubeParser.search(fixture("search_all"))
        assertTrue(page.items.any { it is MusicItem.SongItem })
        val types = page.items.filterIsInstance<MusicItem.SongItem>().map { it.song.videoType }.toSet()
        assertTrue(MusicVideoType.SONG in types)
        assertTrue(MusicVideoType.OFFICIAL_VIDEO in types)
        assertTrue(page.items.map { it::class }.distinct().size >= 2)
        assertEquals(null, page.continuation)
    }

    @Test
    fun `suggestions are parsed`() {
        val list = InnerTubeParser.suggestions(fixture("suggestions"))
        assertTrue(list.isNotEmpty())
        assertTrue(list.all { it.startsWith("daft", ignoreCase = true) })
    }

    @Test
    fun `home and charts yield titled shelves`() {
        val home = InnerTubeParser.shelves(fixture("home"))
        assertTrue(home.isNotEmpty())
        assertTrue(home.all { it.title.isNotBlank() && it.items.isNotEmpty() })
        assertNotNull(InnerTubeParser.sectionContinuation(fixture("home")))
        assertTrue(InnerTubeParser.shelves(fixture("charts")).isNotEmpty())
    }

    @Test
    fun `moods have browse endpoints`() {
        val moods = InnerTubeParser.moods(fixture("moods"))
        assertTrue(moods.size > 5)
        assertTrue(moods.all { it.browseId.startsWith("FEmusic") && it.params != null })
    }

    @Test
    fun `album page has header and ordered tracks`() {
        val page = InnerTubeParser.album("MPREb_K8qWMWVqXGi", fixture("album"))
        assertTrue(page.album.title.isNotBlank())
        assertTrue(page.album.artists.isNotEmpty())
        assertNotNull(page.album.thumbnailUrl)
        assertNotNull(page.album.year)
        assertTrue(page.album.playlistId!!.startsWith("OLAK"))
        assertEquals(13, page.songs.size)
        assertTrue(page.songs.all { it.album?.id == "MPREb_K8qWMWVqXGi" && it.thumbnailUrl != null && it.durationSec > 0 })
        assertTrue(page.songs.all { it.artists.isNotEmpty() })
    }

    @Test
    fun `artist page has top songs, shelves and radio`() {
        val page = InnerTubeParser.artist("UCRr1xG_2WIDs18a6cIiCxeA", fixture("artist"))
        assertEquals("Daft Punk", page.artist.name)
        assertNotNull(page.artist.thumbnailUrl)
        assertNotNull(page.artist.subscribers)
        assertTrue(page.topSongs.isNotEmpty())
        assertTrue(page.shelves.size >= 3)
        assertTrue(page.shelves.any { s -> s.items.any { it is MusicItem.AlbumItem } })
        assertNotNull(page.radioPlaylistId)
    }

    @Test
    fun `playlist page has songs and metadata`() {
        val page = InnerTubeParser.playlist("PLRTT1eLEoNHc", fixture("playlist"))
        assertEquals("Daft Punk", page.playlist.title)
        assertEquals(22, page.playlist.songCount)
        assertEquals(22, page.songs.size)
        assertEquals("Revolution 909", page.songs.first().title)
        assertEquals("Homework (25th Anniversary Edition)", page.songs.first().album?.title)
    }

    @Test
    fun `next yields a radio queue`() {
        val queue = InnerTubeParser.queue(fixture("next"))
        assertTrue(queue.size >= 20)
        assertEquals(queue.size, queue.map { it.id }.distinct().size)
        assertTrue(queue.all { it.title.isNotBlank() && it.artists.isNotEmpty() && it.durationSec > 0 })
    }

    @Test
    fun `client posts context and filter params`() = runTest {
        var body = ""
        var url = ""
        val engine = MockEngine { request ->
            body = String(request.body.toByteArray())
            url = request.url.toString()
            respond(fixtureText("search_songs"), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = InnerTubeClient(HttpClientFactory.create(engine)) { InnerTubeConfig("es", "CO") }
        val page = client.search("daft punk", SearchFilter.SONGS)
        assertTrue(page.items.isNotEmpty())
        assertTrue(url.startsWith("https://music.youtube.com/youtubei/v1/search"))
        val sent = Json.parseToJsonElement(body).jsonObject
        assertEquals("\"daft punk\"", sent["query"].toString())
        assertTrue(body.contains("\"clientName\":\"WEB_REMIX\"") && body.contains("\"hl\":\"es\"") && body.contains("\"gl\":\"CO\""))
        assertTrue(body.contains("EgWKAQIIAWoKEAkQBRAKEAMQBA"))
    }

    @Test
    fun `sapisid hash is deterministic`() {
        val h = InnerTubeClient.sapisidHash("foo=1; SAPISID=abc123; bar=2", "https://music.youtube.com", nowSec = 1_700_000_000)
        assertTrue(h!!.startsWith("SAPISIDHASH 1700000000_"))
        assertEquals(40, h.substringAfter('_').length)
        assertEquals(null, InnerTubeClient.sapisidHash("foo=1", "https://music.youtube.com"))
    }
}
