package com.lostf1sh.pixelplayeross.presentation.viewmodel

import android.bluetooth.BluetoothManager
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkRequest
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Lyrics
import com.lostf1sh.pixelplayeross.data.model.LyricsSourcePreference
import com.lostf1sh.pixelplayeross.data.model.SearchResultItem
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import com.lostf1sh.pixelplayeross.data.youtube.YouTubeMusicRepository
import com.lostf1sh.pixelplayeross.data.service.SleepTimerSession
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * MainActivity's PlayerViewModel binds the singleton holders first; ExternalPlayerActivity's binds
 * them again when it opens an audio file and becomes the current owner. When it finishes, the
 * holders have to keep working for MainActivity's surviving view model.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedStateHolderHandoverTest {

    private val youtubeMusicRepository = mockk<YouTubeMusicRepository>(relaxed = true)

    init {
        coEvery { youtubeMusicRepository.searchSongs(any()) } returns Result.success(emptyList())
        coEvery { youtubeMusicRepository.searchPlaylists(any()) } returns Result.success(emptyList())
    }

    @Nested
    inner class SleepTimer {
        private val context = mockk<Context>(relaxed = true) {
            every { getString(R.string.sleep_timer_eot_no_song_toast) } returns "No song playing"
        }

        @Test
        fun `current owner clearing re-binds the surviving owner's timer mirror and callbacks`() = runTest {
            val session = SleepTimerSession()
            val holder = SleepTimerStateHolder(context, session)
            val mainActivityOwner = ownerScope()
            val externalPlayerOwner = ownerScope()
            val mainToasts = mutableListOf<String>()
            val externalToasts = mutableListOf<String>()
            var mainControllerRequests = 0
            holder.initialize(
                scope = mainActivityOwner,
                toastEmitter = { mainToasts += it },
                mediaControllerProvider = { mainControllerRequests++; null },
                songTitleResolver = { null },
            )
            holder.initialize(
                scope = externalPlayerOwner,
                toastEmitter = { externalToasts += it },
                mediaControllerProvider = { null },
                songTitleResolver = { null },
            )
            runCurrent()

            clear(externalPlayerOwner) { holder.onCleared(it) }
            session.setTimer(SleepTimerSession.Timer.Duration(endsAtMillis = 0L, minutes = 30))
            holder.setEndOfTrackTimer(enable = true, currentSongId = null)
            holder.setSleepTimer(durationMinutes = 15)
            runCurrent()

            assertEquals(30, holder.activeTimerDurationMinutes.value)
            assertEquals(listOf("No song playing"), mainToasts)
            assertTrue(externalToasts.isEmpty())
            assertEquals(1, mainControllerRequests)
            mainActivityOwner.cancel()
        }

        @Test
        fun `last owner clearing stops mirroring the session`() = runTest {
            val session = SleepTimerSession()
            val holder = SleepTimerStateHolder(context, session)
            val owner = ownerScope()
            holder.initialize(owner, toastEmitter = {}, mediaControllerProvider = { null }, songTitleResolver = { null })
            runCurrent()

            clear(owner) { holder.onCleared(it) }
            session.setTimer(SleepTimerSession.Timer.Duration(endsAtMillis = 0L, minutes = 30))
            runCurrent()

            assertEquals(null, holder.activeTimerDurationMinutes.value)
        }
    }

    @Nested
    inner class Search {
        private val musicRepository = mockk<MusicRepository>(relaxed = true) {
            every { searchAll(any(), any()) } returns flowOf(emptyList())
        }

        @Test
        fun `current owner clearing moves search to the surviving owner`() = runTest {
            val holder = SearchStateHolder(musicRepository, youtubeMusicRepository)
            val mainActivityOwner = ownerScope()
            val externalPlayerOwner = ownerScope()
            holder.initialize(mainActivityOwner)
            holder.initialize(externalPlayerOwner)
            runCurrent()

            clear(externalPlayerOwner) { holder.onCleared(it) }
            // viewModelScope runs on Main.immediate, so the moved collector subscribes before
            // the user can type; the test dispatcher needs a turn for that.
            runCurrent()
            holder.performSearch("indian summer")
            advanceTimeBy(SEARCH_SETTLE_MS)
            runCurrent()

            verify(exactly = 1) { musicRepository.searchAll("indian summer", any()) }
            mainActivityOwner.cancel()
        }

        @Test
        fun `stale owner clearing keeps search on the current owner`() = runTest {
            val holder = SearchStateHolder(musicRepository, youtubeMusicRepository)
            val mainActivityOwner = ownerScope()
            val externalPlayerOwner = ownerScope()
            holder.initialize(mainActivityOwner)
            holder.initialize(externalPlayerOwner)
            runCurrent()

            clear(mainActivityOwner) { holder.onCleared(it) }
            holder.performSearch("indian summer")
            advanceTimeBy(SEARCH_SETTLE_MS)
            runCurrent()

            verify(exactly = 1) { musicRepository.searchAll("indian summer", any()) }
            externalPlayerOwner.cancel()
        }

        @Test
        fun `current owner clearing resumes the current search for the surviving owner`() = runTest {
            val matches = MutableStateFlow<List<SearchResultItem>>(emptyList())
            every { musicRepository.searchAll("indian summer", any()) } returns matches
            val holder = SearchStateHolder(musicRepository, youtubeMusicRepository)
            val mainActivityOwner = ownerScope()
            val externalPlayerOwner = ownerScope()
            holder.initialize(mainActivityOwner)
            holder.initialize(externalPlayerOwner)
            runCurrent()
            holder.performSearch("indian summer")
            advanceTimeBy(SEARCH_SETTLE_MS)
            runCurrent()

            // No new request after the handover: the search screen keeps its query.
            clear(externalPlayerOwner) { holder.onCleared(it) }
            advanceTimeBy(SEARCH_SETTLE_MS)
            runCurrent()
            val addedToLibrary = SearchResultItem.SongItem(song("indian-summer-live"))
            matches.value = listOf(addedToLibrary)
            runCurrent()

            assertEquals(listOf(addedToLibrary), holder.searchResults.value)
            mainActivityOwner.cancel()
        }
    }

    @Nested
    inner class LyricsLoading {
        @Test
        fun `current owner clearing hands lyric loading back to the surviving owner`() = runTest {
            val holder = LyricsStateHolder(
                musicRepository = mockk(relaxed = true),
                userPreferencesRepository = mockk(relaxed = true),
                songMetadataEditor = mockk(relaxed = true),
            )
            val playerState = MutableStateFlow(StablePlayerState())
            val mainCallback = RecordingLyricsLoadCallback()
            val externalCallback = RecordingLyricsLoadCallback()
            val mainActivityOwner = ownerScope()
            val externalPlayerOwner = ownerScope()
            holder.initialize(mainActivityOwner, mainCallback, playerState)
            holder.initialize(externalPlayerOwner, externalCallback, playerState)
            runCurrent()

            clear(externalPlayerOwner) { holder.onCleared(it) }
            holder.loadLyricsForSong(song("lyrics-song"), LyricsSourcePreference.API_FIRST)
            runCurrent()

            assertEquals(listOf("lyrics-song"), mainCallback.startedSongIds)
            assertTrue(externalCallback.startedSongIds.isEmpty())
            mainActivityOwner.cancel()
        }

        @Test
        fun `current owner clearing restarts a lyrics load it interrupted`() = runTest {
            val fetchedLyrics = CompletableDeferred<Lyrics?>()
            val musicRepository = mockk<MusicRepository>(relaxed = true) {
                coEvery { getLyrics(any(), any(), any()) } coAnswers { fetchedLyrics.await() }
            }
            val holder = LyricsStateHolder(
                musicRepository = musicRepository,
                userPreferencesRepository = mockk(relaxed = true),
                songMetadataEditor = mockk(relaxed = true),
            )
            val playerState = MutableStateFlow(StablePlayerState())
            val mainCallback = RecordingLyricsLoadCallback()
            val externalCallback = RecordingLyricsLoadCallback()
            val mainActivityOwner = ownerScope()
            val externalPlayerOwner = ownerScope()
            holder.initialize(mainActivityOwner, mainCallback, playerState)
            holder.initialize(externalPlayerOwner, externalCallback, playerState)
            runCurrent()
            // ExternalPlayerActivity's view model starts the load, then finishes mid-fetch.
            holder.loadLyricsForSong(song("lyrics-song"), LyricsSourcePreference.API_FIRST)
            runCurrent()
            assertEquals(listOf("lyrics-song"), externalCallback.startedSongIds)

            clear(externalPlayerOwner) { holder.onCleared(it) }
            runCurrent()
            assertEquals(listOf("lyrics-song"), mainCallback.startedSongIds)
            fetchedLyrics.complete(Lyrics(plain = listOf("Restarted lyrics")))

            // The fetch resumes from Dispatchers.IO, so wait for the result.
            assertEquals(listOf("lyrics-song"), mainCallback.loadedSongIds.first { it.isNotEmpty() })
            assertTrue(externalCallback.loadedSongIds.value.isEmpty())
            mainActivityOwner.cancel()
        }
    }

    @Nested
    inner class DailyMix {
        @Test
        fun `current owner clearing keeps Daily Mix saves on the surviving owner`() = runTest {
            val preferences = mockk<UserPreferencesRepository>(relaxed = true)
            val holder = DailyMixStateHolder(
                dailyMixManager = mockk(relaxed = true),
                userPreferencesRepository = preferences,
                musicRepository = mockk(relaxed = true),
            )
            val mainActivityOwner = ownerScope()
            val externalPlayerOwner = ownerScope()
            holder.initialize(mainActivityOwner)
            holder.initialize(externalPlayerOwner)

            clear(externalPlayerOwner) { holder.onCleared(it) }
            holder.setDailyMixSongs(listOf(song("mix-song")))
            runCurrent()

            coVerify(exactly = 1) { preferences.saveDailyMixSongIds(listOf("mix-song")) }
            mainActivityOwner.cancel()
        }
    }

    @Nested
    inner class Connectivity {
        private val connectivityManager = mockk<ConnectivityManager>(relaxed = true)
        private val registeredAudioDeviceCallbacks = mutableSetOf<AudioDeviceCallback>()
        private val audioManager = mockk<AudioManager>(relaxed = true) {
            every { registerAudioDeviceCallback(any(), any()) } answers {
                registeredAudioDeviceCallbacks += firstArg<AudioDeviceCallback>()
            }
            every { unregisterAudioDeviceCallback(any()) } answers {
                registeredAudioDeviceCallbacks -= firstArg<AudioDeviceCallback>()
            }
        }
        private val context = mockk<Context>(relaxed = true).also { context ->
            every { context.applicationContext } returns context
            every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivityManager
            every { context.getSystemService(Context.WIFI_SERVICE) } returns null
            every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns mockk<BluetoothManager>(relaxed = true) {
                every { adapter } returns null
            }
            every { context.getSystemService(Context.AUDIO_SERVICE) } returns audioManager
        }

        init {
            // The android.jar stubs return null from builder calls, so give the request builder
            // a working chain.
            mockkConstructor(NetworkRequest.Builder::class)
            every { anyConstructed<NetworkRequest.Builder>().addCapability(any()) } answers { self as NetworkRequest.Builder }
            every { anyConstructed<NetworkRequest.Builder>().build() } returns mockk()
        }

        @AfterEach
        fun restoreNetworkRequestBuilder() {
            unmockkConstructor(NetworkRequest.Builder::class)
        }

        @Test
        fun `monitoring keeps running until the last owner is cleared`() = runTest {
            val holder = ConnectivityStateHolder(context)
            val mainActivityOwner = ownerScope()
            val externalPlayerOwner = ownerScope()
            holder.initialize(mainActivityOwner)
            holder.initialize(externalPlayerOwner)
            verify(exactly = 1) {
                connectivityManager.registerNetworkCallback(any<NetworkRequest>(), any<ConnectivityManager.NetworkCallback>())
            }

            clear(externalPlayerOwner) { holder.onCleared(it) }
            verify(exactly = 0) {
                connectivityManager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>())
            }

            clear(mainActivityOwner) { holder.onCleared(it) }
            verify(exactly = 1) {
                connectivityManager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>())
            }
        }

        @Test
        fun `an owner arriving after a full teardown leaves one audio device callback registered`() = runTest {
            val holder = ConnectivityStateHolder(context)
            val firstOwner = ownerScope()
            holder.initialize(firstOwner)
            clear(firstOwner) { holder.onCleared(it) }

            val nextOwner = ownerScope()
            holder.initialize(nextOwner)

            assertEquals(1, registeredAudioDeviceCallbacks.size)
            nextOwner.cancel()
        }
    }

    /** Clears [owner] the way ViewModel.clear() does: the scope is cancelled before onCleared. */
    private inline fun clear(owner: CoroutineScope, onCleared: (CoroutineScope) -> Unit) {
        owner.cancel()
        onCleared(owner)
    }

    // Separate Job so each owner has its own identity, while still running on the test
    // dispatcher that runCurrent and advanceTimeBy drive.
    private fun TestScope.ownerScope(): CoroutineScope = CoroutineScope(coroutineContext + Job())

    private class RecordingLyricsLoadCallback : LyricsLoadCallback {
        val startedSongIds = mutableListOf<String>()
        val loadedSongIds = MutableStateFlow(emptyList<String>())

        override fun onLoadingStarted(songId: String) {
            startedSongIds += songId
        }

        override fun onLyricsLoaded(songId: String, lyrics: Lyrics?) {
            loadedSongIds.value += songId
        }
    }

    private fun song(id: String) = Song(
        id = id,
        title = id,
        artist = "Artist",
        artistId = 1L,
        album = "Album",
        albumId = 1L,
        path = "/music/$id.mp3",
        contentUriString = "content://media/external/audio/media/$id",
        albumArtUriString = null,
        duration = 180_000L,
        mimeType = "audio/mpeg",
        bitrate = 320_000,
        sampleRate = 44_100
    )

    private companion object {
        /** Past SearchStateHolder's 300 ms debounce. */
        const val SEARCH_SETTLE_MS = 500L
    }
}
