package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PlaylistCoversMemoryTest {
    @Test
    fun `resolved covers are remembered per playlist`() {
        val covers = rememberPlaylistCovers(emptyMap(), "PL1", listOf("a", "b"))

        assertThat(covers["PL1"]).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `an empty answer is remembered instead of being retried`() {
        val covers = rememberPlaylistCovers(emptyMap(), "PL1", emptyList())

        assertThat(covers).containsKey("PL1")
        assertThat(covers["PL1"]).isEmpty()
    }

    @Test
    fun `the oldest playlists are dropped once the bound is reached`() {
        var covers = emptyMap<String, List<String>>()
        repeat(5) { index ->
            covers = rememberPlaylistCovers(covers, "PL$index", listOf("cover$index"), max = 3)
        }

        assertThat(covers.keys).containsExactly("PL2", "PL3", "PL4").inOrder()
    }

    @Test
    fun `refreshing a known playlist does not grow the map`() {
        val first = rememberPlaylistCovers(emptyMap(), "PL1", listOf("a"), max = 2)
        val second = rememberPlaylistCovers(first, "PL1", listOf("b"), max = 2)

        assertThat(second).hasSize(1)
        assertThat(second["PL1"]).containsExactly("b")
    }
}