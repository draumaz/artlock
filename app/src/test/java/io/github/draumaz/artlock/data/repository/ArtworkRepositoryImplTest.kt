package io.github.draumaz.artlock.data.repository

import io.github.draumaz.artlock.data.model.ItunesTrack
import io.github.draumaz.artlock.data.repository.ArtworkRepositoryImpl.Companion.cleanText
import io.github.draumaz.artlock.data.repository.ArtworkRepositoryImpl.Companion.pickBestMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtworkRepositoryImplTest {

    @Test
    fun cleanText_stripsDecorations() {
        assertEquals("Song", cleanText("Song (Explicit)"))
        assertEquals("Song", cleanText("Song [Explicit]"))
        assertEquals("Song", cleanText("Song (feat. Someone)"))
        assertEquals("Album", cleanText("Album - Single"))
        assertEquals("Album", cleanText("Album - EP"))
        assertEquals("Song", cleanText("  Song🅴 "))
    }

    @Test
    fun cleanText_leavesPlainTitlesAlone() {
        assertEquals("eighteen", cleanText("eighteen"))
        assertEquals("Love Love (Love Love)", cleanText("Love Love (Love Love)"))
    }

    @Test
    fun pickBestMatch_prefersMatchingArtist() {
        val results = listOf(
            ItunesTrack(artistName = "Someone Else", trackName = "eighteen", artworkUrl100 = "wrong"),
            ItunesTrack(artistName = "daine", trackName = "eighteen", artworkUrl100 = "right")
        )
        assertEquals("right", pickBestMatch(results, "daine")?.artworkUrl100)
    }

    @Test
    fun pickBestMatch_matchesCollaborationsLoosely() {
        val results = listOf(
            ItunesTrack(artistName = "Other", trackName = "It's You", artworkUrl100 = "wrong"),
            ItunesTrack(artistName = "Ninajirachi & daine", trackName = "It's You", artworkUrl100 = "right")
        )
        assertEquals("right", pickBestMatch(results, "daine")?.artworkUrl100)
    }

    @Test
    fun pickBestMatch_fallsBackToTopResultWithArtwork() {
        val results = listOf(
            ItunesTrack(artistName = "A", trackName = "x", artworkUrl100 = null),
            ItunesTrack(artistName = "B", trackName = "x", artworkUrl100 = "top")
        )
        assertEquals("top", pickBestMatch(results, "nobody")?.artworkUrl100)
    }

    @Test
    fun pickBestMatch_returnsNullWithoutArtwork() {
        assertNull(pickBestMatch(emptyList(), "daine"))
        assertNull(pickBestMatch(listOf(ItunesTrack("daine", "x", null)), "daine"))
    }
}
