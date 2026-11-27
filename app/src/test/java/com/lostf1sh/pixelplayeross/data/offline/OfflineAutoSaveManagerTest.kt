package com.lostf1sh.pixelplayeross.data.offline

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class OfflineAutoSaveManagerTest {

    private val repository: CloudOfflineRepository = mockk(relaxed = true)
    private val preferences: UserPreferencesRepository = mockk(relaxed = true)
    private val manager = OfflineAutoSaveManager(repository, preferences)
    private val song = Song.emptySong().copy(
        id = "youtube-1",
        title = "Night Drive",
        artist = "Someone",
        contentUriString = "youtube://abc123"
    )

    @BeforeEach
    fun setUp() {
        every { preferences.autoSaveFavoritesFlow } returns flowOf(true)
        every { preferences.recentOfflineCountFlow } returns flowOf(10)
        every { preferences.offlineStorageLimitMbFlow } returns flowOf(1024)
    }

    @Test
    fun `hearting a song queues a favorite download`() = runTest {
        every { preferences.autoSaveFavoritesFlow } returns flowOf(true)

        manager.onFavoriteChanged(song, isFavorite = true)

        coVerify { repository.enqueue(song, OfflineTrackOrigin.FAVORITE) }
        coVerify { repository.trim(any(), any()) }
    }

    @Test
    fun `the setting turns the heart trigger off`() = runTest {
        every { preferences.autoSaveFavoritesFlow } returns flowOf(false)

        manager.onFavoriteChanged(song, isFavorite = true)

        coVerify(exactly = 0) { repository.enqueue(any(), any()) }
    }

    @Test
    fun `un-hearting removes a copy the heart created`() = runTest {
        coEvery { repository.find(song.contentUriString) } returns download(OfflineTrackOrigin.FAVORITE)

        manager.onFavoriteChanged(song, isFavorite = false)

        coVerify { repository.remove(song) }
    }

    @Test
    fun `un-hearting keeps a download the listener made`() = runTest {
        coEvery { repository.find(song.contentUriString) } returns download(OfflineTrackOrigin.USER)
        every { preferences.recentOfflineCountFlow } returns flowOf(10)

        manager.onFavoriteChanged(song, isFavorite = false)

        coVerify(exactly = 0) { repository.remove(any<Song>()) }
    }

    @Test
    fun `a skipped track is not kept`() = runTest {
        manager.rememberSong(song)

        manager.onListened(song.id, listenedMs = 5_000L)

        coVerify(exactly = 0) { repository.enqueue(any(), any()) }
    }

    @Test
    fun `a track played long enough joins the recent cache`() = runTest {
        every { preferences.recentOfflineCountFlow } returns flowOf(10)
        manager.rememberSong(song)

        manager.onListened(song.id, listenedMs = 45_000L)

        coVerify { repository.enqueue(song, OfflineTrackOrigin.RECENT) }
    }

    private fun download(origin: OfflineTrackOrigin) = OfflineDownload(
        downloadId = "download-1",
        sourceUri = song.contentUriString,
        status = OfflineDownloadStatus.COMPLETE,
        bytesDownloaded = 4_000_000L,
        totalBytes = 4_000_000L,
        localPath = "/tmp/song.m4a",
        errorMessage = null,
        title = song.title,
        provider = "youtube",
        origin = origin
    )
}