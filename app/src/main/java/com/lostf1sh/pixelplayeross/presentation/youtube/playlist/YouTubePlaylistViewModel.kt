package com.lostf1sh.pixelplayeross.presentation.youtube.playlist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lostf1sh.pixelplayeross.data.youtube.YouTubeMusicRepository
import com.lostf1sh.pixelplayeross.data.youtube.YouTubePlaylistCache
import com.lostf1sh.pixelplayeross.data.youtube.YouTubePlaylist
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Loads one YouTube playlist so the playlist screen can list its tracks before anything plays.
 * The tracks are fetched without resolving stream URLs; those only resolve for the song that is
 * actually started, which keeps opening a playlist fast and cheap.
 */
@HiltViewModel
class YouTubePlaylistViewModel @Inject constructor(
    private val youtubeMusicRepository: YouTubeMusicRepository,
    private val playlistCache: YouTubePlaylistCache,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val playlistId: String = savedStateHandle.get<String>("playlistId").orEmpty()

    private val _uiState = MutableStateFlow(YouTubePlaylistUiState())
    val uiState: StateFlow<YouTubePlaylistUiState> = _uiState.asStateFlow()

    private var loadStarted = false

    fun load() {
        if (playlistId.isBlank() || loadStarted) return
        loadStarted = true
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            youtubeMusicRepository.fetchPlaylist(playlistId, prefetch = false)
                .onSuccess { playlist ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            playlist = playlist,
                            errorMessage = null,
                            isFromCache = false
                        )
                    }
                    playlistCache.write(playlist)
                }
                .onFailure { error ->
                    Timber.e(error, "Could not load YouTube playlist %s", playlistId)
                    val cached = playlistCache.read(playlistId)
                    _uiState.update {
                        if (cached != null) {
                            it.copy(
                                isLoading = false,
                                playlist = cached,
                                errorMessage = null,
                                isFromCache = true
                            )
                        } else {
                            it.copy(
                                isLoading = false,
                                errorMessage = "Could not load this playlist"
                            )
                        }
                    }
                }
        }
    }

    fun retry() {
        loadStarted = false
        load()
    }
}

data class YouTubePlaylistUiState(
    val isLoading: Boolean = false,
    val playlist: YouTubePlaylist? = null,
    val errorMessage: String? = null,
    /** True when the network failed and the list came from the saved copy instead. */
    val isFromCache: Boolean = false
)