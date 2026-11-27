package com.lostf1sh.pixelplayeross.presentation.youtube.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.youtube.YouTubeMusicRepository
import com.lostf1sh.pixelplayeross.data.youtube.extractYouTubePlaylistId
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
    val errorMessage: String? = null,
    /**
     * Last query that reached the network. Keeps "no results" from showing before the user
     * has searched at all, which otherwise looks like the feature is broken on first open.
     */
    val lastQuery: String? = null,
    /** Set when the query was a playlist link instead of a search; shown as the list header. */
    val playlistTitle: String? = null,
    val playlistId: String? = null
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

        // A pasted playlist link is a request to queue the whole list, not to search its text.
        val playlistId = extractYouTubePlaylistId(query)
        if (playlistId != null) {
            loadPlaylist(query, playlistId)
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isSearching = true,
                errorMessage = null,
                lastQuery = query
            )
            repository.searchSongs(query).fold(
                onSuccess = { results ->
                    _uiState.value = _uiState.value.copy(
                        isSearching = false,
                        searchResults = results,
                        errorMessage = null,
                        playlistTitle = null,
                        playlistId = null
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

    /** Plays the current results as a queue, starting at the first one. */
    fun playAll(playerViewModel: PlayerViewModel, queueName: String) {
        val results = _uiState.value.searchResults
        if (results.isEmpty()) return
        val name = _uiState.value.playlistTitle ?: queueName
        playerViewModel.playSongs(results, results.first(), name)
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

    /**
     * Load a whole YouTube playlist as the queue. Kept separate from [search] because it has a
     * different failure story: an empty result means the link is wrong or the list is private,
     * not that the query found nothing.
     */
    private fun loadPlaylist(query: String, playlistId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isSearching = true,
                errorMessage = null,
                lastQuery = query,
                searchResults = emptyList(),
                playlistTitle = null,
                playlistId = playlistId
            )
            repository.fetchPlaylist(query).fold(
                onSuccess = { playlist ->
                    _uiState.value = _uiState.value.copy(
                        isSearching = false,
                        searchResults = playlist.songs,
                        playlistTitle = playlist.title,
                        playlistId = playlist.id,
                        errorMessage = null
                    )
                },
                onFailure = { e ->
                    Timber.e(e, "YouTube playlist load failed for %s", playlistId)
                    _uiState.value = _uiState.value.copy(
                        isSearching = false,
                        searchResults = emptyList(),
                        playlistTitle = null,
                        errorMessage = e.localizedMessage ?: "Could not load that playlist"
                    )
                }
            )
        }
    }
}
