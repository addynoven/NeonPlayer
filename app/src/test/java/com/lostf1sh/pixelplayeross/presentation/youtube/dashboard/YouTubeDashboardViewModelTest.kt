package com.lostf1sh.pixelplayeross.presentation.youtube.dashboard

import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.youtube.YouTubeMusicRepository
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * A failed YouTube search used to come back as an empty list, so the dashboard rendered
 * "no results" and the error path in the view model was unreachable. These tests pin the
 * contract that a failure is reported as a failure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class YouTubeDashboardViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val repository: YouTubeMusicRepository = mockk(relaxed = true)
    private lateinit var viewModel: YouTubeDashboardViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        viewModel = YouTubeDashboardViewModel(repository)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun song(id: String = "yt1") = Song(
        id = id,
        title = "Imagine",
        artist = "John Lennon",
        artistId = 1L,
        album = "YouTube Music",
        albumId = 2L,
        path = "youtube://$id",
        contentUriString = "youtube://$id",
        albumArtUriString = null,
        duration = 180_000L,
        mimeType = "audio/opus",
        bitrate = 160,
        sampleRate = 48_000
    )

    @Test
    fun `failed search surfaces the error and clears results`() = runTest(dispatcher) {
        coEvery { repository.searchSongs("aurora") } returns
            Result.failure(IllegalStateException("network down"))

        viewModel.onSearchQueryChanged("aurora")
        viewModel.search()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isSearching)
        assertTrue(state.searchResults.isEmpty())
        assertEquals("network down", state.errorMessage)
    }

    @Test
    fun `successful search keeps results and clears an earlier error`() = runTest(dispatcher) {
        coEvery { repository.searchSongs("aurora") } returnsMany listOf(
            Result.failure(IllegalStateException("network down")),
            Result.success(listOf(song()))
        )

        viewModel.onSearchQueryChanged("aurora")
        viewModel.search()
        advanceUntilIdle()
        assertEquals("network down", viewModel.uiState.value.errorMessage)

        viewModel.search()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.errorMessage)
        assertEquals(listOf("yt1"), state.searchResults.map { it.id })
    }

    @Test
    fun `blank query does not hit the network`() = runTest(dispatcher) {
        viewModel.onSearchQueryChanged("   ")
        viewModel.search()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isSearching)
        io.mockk.coVerify(exactly = 0) { repository.searchSongs(any()) }
    }

    @Test
    fun `play all queues every result and starts at the first`() = runTest(dispatcher) {
        val songs = listOf(song("yt1"), song("yt2"))
        coEvery { repository.searchSongs("aurora") } returns Result.success(songs)
        viewModel.onSearchQueryChanged("aurora")
        viewModel.search()
        advanceUntilIdle()

        val player: PlayerViewModel = mockk(relaxed = true)
        viewModel.playAll(player, "YouTube Music")

        io.mockk.verify { player.playSongs(songs, songs.first(), "YouTube Music", null) }
    }
}