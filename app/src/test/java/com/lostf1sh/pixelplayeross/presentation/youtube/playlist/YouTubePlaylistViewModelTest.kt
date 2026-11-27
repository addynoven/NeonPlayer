package com.lostf1sh.pixelplayeross.presentation.youtube.playlist

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.MainCoroutineExtension
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.youtube.YouTubeMusicRepository
import com.lostf1sh.pixelplayeross.data.youtube.YouTubePlaylist
import com.lostf1sh.pixelplayeross.data.youtube.YouTubePlaylistCache
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MainCoroutineExtension::class)
class YouTubePlaylistViewModelTest {

    private val repository = mockk<YouTubeMusicRepository>()
    private val cache = mockk<YouTubePlaylistCache>(relaxed = true)

    @BeforeEach
    fun setUp() {
        // A relaxed mock would invent a playlist here; the real cache returns null on a miss.
        coEvery { cache.read(any()) } returns null
    }

    @Test
    fun `loading exposes the playlist and its tracks`() = runTest {
        val playlist = playlist("PL1", songs("a", "b"))
        coEvery { repository.fetchPlaylist("PL1", prefetch = false) } returns Result.success(playlist)

        val viewModel = viewModel("PL1")
        viewModel.load()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.isLoading).isFalse()
        assertThat(viewModel.uiState.value.errorMessage).isNull()
        assertThat(viewModel.uiState.value.playlist?.songs?.map { it.id })
            .containsExactly("a", "b").inOrder()
    }

    @Test
    fun `a failed load keeps an error until retry succeeds`() = runTest {
        val playlist = playlist("PL2", songs("c"))
        coEvery { repository.fetchPlaylist("PL2", prefetch = false) } returnsMany listOf(
            Result.failure(IllegalStateException("offline")),
            Result.success(playlist)
        )

        val viewModel = viewModel("PL2")
        viewModel.load()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.playlist).isNull()
        assertThat(viewModel.uiState.value.errorMessage).isNotNull()

        viewModel.retry()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.errorMessage).isNull()
        assertThat(viewModel.uiState.value.playlist?.id).isEqualTo("PL2")
    }

    @Test
    fun `a failed load falls back to the saved copy`() = runTest {
        val saved = playlist("PL4", songs("e", "f"))
        coEvery { repository.fetchPlaylist("PL4", prefetch = false) } returns
            Result.failure(IllegalStateException("offline"))
        coEvery { cache.read("PL4") } returns saved

        val viewModel = viewModel("PL4")
        viewModel.load()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.errorMessage).isNull()
        assertThat(viewModel.uiState.value.isFromCache).isTrue()
        assertThat(viewModel.uiState.value.playlist?.songs?.map { it.id })
            .containsExactly("e", "f").inOrder()
    }

    @Test
    fun `a successful load is written to the cache for later`() = runTest {
        val loaded = playlist("PL5", songs("g"))
        coEvery { repository.fetchPlaylist("PL5", prefetch = false) } returns Result.success(loaded)

        val viewModel = viewModel("PL5")
        viewModel.load()
        advanceUntilIdle()

        io.mockk.coVerify { cache.write(loaded) }
    }

    @Test
    fun `loading twice only asks the repository once`() = runTest {
        coEvery { repository.fetchPlaylist("PL3", prefetch = false) } returns
            Result.success(playlist("PL3", songs("d")))

        val viewModel = viewModel("PL3")
        viewModel.load()
        advanceUntilIdle()
        viewModel.load()
        advanceUntilIdle()

        io.mockk.coVerify(exactly = 1) { repository.fetchPlaylist("PL3", prefetch = false) }
    }

    private fun viewModel(playlistId: String) = YouTubePlaylistViewModel(
        youtubeMusicRepository = repository,
        playlistCache = cache,
        savedStateHandle = SavedStateHandle(mapOf("playlistId" to playlistId))
    )

    private fun playlist(id: String, songs: List<Song>) = YouTubePlaylist(
        id = id,
        title = "Playlist $id",
        songs = songs
    )

    private fun songs(vararg ids: String): List<Song> = ids.map { id ->
        Song(
            id = id,
            title = "Song $id",
            artist = "Artist",
            artistId = 1L,
            album = "Album",
            albumId = 1L,
            path = "youtube://$id",
            contentUriString = "youtube://$id",
            albumArtUriString = null,
            duration = 1000L,
            mimeType = "audio/mp4",
            bitrate = null,
            sampleRate = null
        )
    }
}