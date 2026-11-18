package app.ondara.source.spotify

import app.ondara.core.model.ArtistRef
import app.ondara.core.model.Song
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TrackMatcherTest {
    private fun track(title: String, artist: String, durationMs: Long, vararg more: String) =
        SpotifyTrack("id", title, listOf(artist, *more), "Álbum", durationMs, null)

    private fun song(title: String, durationSec: Int, vararg artists: String) =
        Song(id = "v-$title-$durationSec", title = title, artists = artists.map { ArtistRef(null, it) }, durationSec = durationSec)

    @Test
    fun `normalize lowercases, strips accents and collapses punctuation`() {
        assertEquals("corazon espinado", TrackMatcher.normalize("  Corazón   Espinado!! "))
        assertEquals("beyonce", TrackMatcher.normalize("Beyoncé"))
        assertEquals("ac dc", TrackMatcher.normalize("AC/DC"))
        assertEquals("sigur ros", TrackMatcher.normalize("Sigur Rós"))
        assertEquals("東京 2020", TrackMatcher.normalize("東京・2020"))
    }

    @Test
    fun `normalize drops featuring credits, brackets and edition suffixes`() {
        assertEquals("corazon espinado", TrackMatcher.normalize("Corazón Espinado (feat. Maná)"))
        assertEquals("stay", TrackMatcher.normalize("STAY (with Justin Bieber)"))
        assertEquals("umbrella", TrackMatcher.normalize("Umbrella ft. JAY-Z"))
        assertEquals("bohemian rhapsody", TrackMatcher.normalize("Bohemian Rhapsody - Remastered 2011"))
        assertEquals("heroes", TrackMatcher.normalize("Heroes - 2017 Remaster"))
        assertEquals("come together", TrackMatcher.normalize("Come Together - Remaster"))
        assertEquals("hotel california", TrackMatcher.normalize("Hotel California - Live"))
        assertEquals("thriller", TrackMatcher.normalize("Thriller (Deluxe)"))
        assertEquals("despacito", TrackMatcher.normalize("Despacito [Official Video]"))
        assertEquals("smells like teen spirit", TrackMatcher.normalize("Smells Like Teen Spirit (Remastered 2021) [HD]"))
    }

    @Test
    fun `normalize keeps meaningful parentheses and never returns empty for real text`() {
        assertEquals("shine on you crazy diamond pts 1 5", TrackMatcher.normalize("Shine On You Crazy Diamond (Pts. 1-5)"))
        assertEquals("levels skrillex remix", TrackMatcher.normalize("Levels (Skrillex Remix)"))
        assertEquals("live", TrackMatcher.normalize("(Live)"))
        assertEquals("", TrackMatcher.normalize("  "))
    }

    @Test
    fun `jaro winkler matches the published reference values`() {
        assertEquals(0.961, TrackMatcher.jaroWinkler("MARTHA", "MARHTA"), 0.001)
        assertEquals(0.840, TrackMatcher.jaroWinkler("DWAYNE", "DUANE"), 0.001)
        assertEquals(0.813, TrackMatcher.jaroWinkler("DIXON", "DICKSONX"), 0.001)
        assertEquals(1.0, TrackMatcher.jaroWinkler("abc", "abc"))
        assertEquals(1.0, TrackMatcher.jaroWinkler("", ""))
        assertEquals(0.0, TrackMatcher.jaroWinkler("abc", ""))
        assertEquals(0.0, TrackMatcher.jaroWinkler("abc", "xyz"))
        assertEquals(TrackMatcher.jaroWinkler("CRATE", "TRACE"), TrackMatcher.jaroWinkler("TRACE", "CRATE"), 1e-9)
    }

    @Test
    fun `query is title plus main artist`() {
        assertEquals("Under Pressure Queen", TrackMatcher.query(track("Under Pressure", "Queen", 248_000, "David Bowie")))
        assertEquals("Intro", TrackMatcher.query(SpotifyTrack("id", "Intro", emptyList(), null, 1000, null)))
    }

    @Test
    fun `exact match scores at least 0_95 even with edition noise`() {
        val spotify = track("Bohemian Rhapsody - Remastered 2011", "Queen", 354_320)
        assertTrue(TrackMatcher.score(spotify, song("Bohemian Rhapsody", 354, "Queen")) >= 0.95)
        assertTrue(TrackMatcher.score(spotify, song("Bohemian Rhapsody (Official Video)", 355, "Queen")) >= 0.95)
        val featured = track("Corazón Espinado (feat. Maná)", "Santana", 276_000)
        assertTrue(TrackMatcher.score(featured, song("Corazon Espinado", 276, "Maná", "Santana")) >= 0.95)
    }

    @Test
    fun `live version with a very different duration stays below the accept score`() {
        val spotify = track("Hotel California", "Eagles", 391_000)
        val live = TrackMatcher.score(spotify, song("Hotel California (Live on MTV, 1994)", 432, "Eagles"))
        assertTrue(live < TrackMatcher.ACCEPT_SCORE, "live = $live")
        assertEquals(0.75, live, 1e-9)
    }

    @Test
    fun `duration term decays linearly over ten seconds`() {
        val spotify = track("Song", "Artist", 200_000)
        assertEquals(1.0, TrackMatcher.score(spotify, song("Song", 200, "Artist")), 1e-9)
        assertEquals(0.875, TrackMatcher.score(spotify, song("Song", 205, "Artist")), 1e-9)
        assertEquals(0.75, TrackMatcher.score(spotify, song("Song", 0, "Artist")), 1e-9)
    }

    @Test
    fun `wrong artist stays below the accept score`() {
        val spotify = track("Hurt", "Nine Inch Nails", 373_000)
        val wrong = TrackMatcher.score(spotify, song("Hurt", 373, "Vybz"))
        assertTrue(wrong < TrackMatcher.ACCEPT_SCORE, "wrong = $wrong")
        assertTrue(TrackMatcher.score(spotify, song("Hurt", 373)) < TrackMatcher.ACCEPT_SCORE)
        assertTrue(TrackMatcher.score(spotify, song("Hurt", 373, "Nine Inch Nails")) >= 0.95)
    }

    @Test
    fun `best picks the top score among the first candidates only`() {
        val spotify = track("Yellow", "Coldplay", 266_000)
        val exact = song("Yellow", 266, "Coldplay")
        val candidates = listOf(
            song("Yellow (Live in Buenos Aires)", 310, "Coldplay"),
            song("Yellow Submarine", 159, "The Beatles"),
            exact,
            song("Yellow", 240, "Cover Band"),
            song("Mellow Yellow", 220, "Donovan"),
        )
        val best = TrackMatcher.best(spotify, candidates)
        assertEquals(exact, best?.song)
        assertTrue(best!!.score >= 0.95)

        // El sexto resultado queda fuera aunque sea perfecto; se devuelve el mejor de los cinco primeros.
        val late = TrackMatcher.best(spotify, candidates.filter { it != exact } + song("Filler", 1, "Nobody") + exact)
        assertTrue(late!!.song != exact && late.score < TrackMatcher.ACCEPT_SCORE)
        assertNull(TrackMatcher.best(spotify, emptyList()))
    }
}
