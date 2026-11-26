package com.lostf1sh.pixelplayeross.presentation.youtube.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.youtube.YouTubeMusicRepository
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class YouTubeDashboardUiState(
    val searchQuery: String = "",
    val isSearching: Boolean = false,
    val searchResults: List<Song> = emptyList(),
    val isSyncing: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class YouTubeDashboardViewModel @Inject constructor(
    private val repository: YouTubeMusicRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(YouTubeDashboardUiState())
    val uiState: StateFlow<YouTubeDashboardUiState> = _uiState.asStateFlow()

    fun onSearchQueryChanged(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    fun search() {
        val query = _uiState.value.searchQuery.trim()
        if (query.isEmpty()) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSearching = true, errorMessage = null)
            repository.searchSongs(query).fold(
                onSuccess = { results ->
                    _uiState.value = _uiState.value.copy(
                        isSearching = false,
                        searchResults = results,
                        errorMessage = null
                    )
                },
                onFailure = { e ->
                    Timber.e(e, "YouTube search failed")
                    _uiState.value = _uiState.value.copy(
                        isSearching = false,
                        searchResults = emptyList(),
                        errorMessage = e.localizedMessage ?: "YouTube search failed"
                    )
                }
            )
        }
    }

    fun playTrack(song: Song, playerViewModel: PlayerViewModel) {
        viewModelScope.launch {
            repository.saveSongsToLibrary(listOf(song))
                .onFailure { e ->
                    Timber.e(e, "Failed to save YouTube track to library")
                    _uiState.value = _uiState.value.copy(
                        errorMessage = e.localizedMessage ?: "Could not save to library"
                    )
                }
            // Play regardless: the stream proxy resolves playback without a library row.
            playerViewModel.playSong(song)
        }
    }

    fun saveAllToLibrary() {
        val results = _uiState.value.searchResults
        if (results.isEmpty()) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSyncing = true)
            repository.saveSongsToLibrary(results)
                .fold(
                    onSuccess = {
                        _uiState.value = _uiState.value.copy(isSyncing = false)
                    },
                    onFailure = { e ->
                        Timber.e(e, "Failed to save YouTube tracks to library")
                        _uiState.value = _uiState.value.copy(
                            isSyncing = false,
                            errorMessage = e.localizedMessage ?: "Could not save to library"
                        )
                    }
                )
        }
    }
}
