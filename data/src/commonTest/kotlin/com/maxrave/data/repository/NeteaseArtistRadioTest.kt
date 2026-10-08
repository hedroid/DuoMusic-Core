package com.maxrave.data.repository

import com.maxrave.domain.data.model.browse.album.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NeteaseArtistRadioTest {
    private fun track(id: String) = Track(
        album = null,
        artists = null,
        duration = null,
        durationSeconds = null,
        isAvailable = true,
        isExplicit = false,
        likeStatus = null,
        thumbnails = null,
        title = id,
        videoId = id,
        videoType = null,
        category = null,
        feedbackTokens = null,
        resultType = null,
    )

    @Test
    fun mixKeepsTheArtistPresentAndDropsDuplicateRecommendations() {
        val tracks = NeteaseArtistRadio.mix(
            artistSongs = listOf(track("1"), track("2"), track("3")),
            similarSongs = listOf(track("9"), track("2"), track("8")),
        )
        assertEquals(listOf("1", "9", "2", "3", "8"), tracks.map { it.videoId })
    }

    @Test
    fun queueAndCursorRoundTripWithoutConfusingTheSourceIds() {
        val id = NeteaseArtistRadio.queueId("6452", "186016")
        assertEquals("6452" to "186016", NeteaseArtistRadio.idsFromQueueId(id))
        assertNull(NeteaseArtistRadio.idsFromQueueId("NETEASE_RADIO_186016"))
        assertEquals(
            NeteaseArtistRadio.Cursor(15, -1),
            NeteaseArtistRadio.Cursor.parse(NeteaseArtistRadio.Cursor(15, -1).encode()),
        )
        assertNull(NeteaseArtistRadio.Cursor.parse("15:bad"))
    }
}
