package com.lostf1sh.pixelplayeross.presentation.screens

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SongStreamLoadingTest {

    private val songId = "song-a"

    @Test
    fun `preparing song shows loading even before the player has the item`() {
        assertTrue(
            isSongStreamLoading(
                songId = songId,
                currentSongId = "previous-song",
                isBuffering = false,
                preparingSongId = songId
            )
        )
    }

    @Test
    fun `buffering current song shows loading`() {
        assertTrue(
            isSongStreamLoading(
                songId = songId,
                currentSongId = songId,
                isBuffering = true,
                preparingSongId = null
            )
        )
    }

    @Test
    fun `stalled other row does not light up this row`() {
        assertFalse(
            isSongStreamLoading(
                songId = songId,
                currentSongId = "another-song",
                isBuffering = true,
                preparingSongId = null
            )
        )
    }

    @Test
    fun `playing song without buffering is not loading`() {
        assertFalse(
            isSongStreamLoading(
                songId = songId,
                currentSongId = songId,
                isBuffering = false,
                preparingSongId = null
            )
        )
    }

    @Test
    fun `preparing another song does not light up this row`() {
        assertFalse(
            isSongStreamLoading(
                songId = songId,
                currentSongId = songId,
                isBuffering = false,
                preparingSongId = "another-song"
            )
        )
    }
}
