package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.lostf1sh.pixelplayeross.data.model.SearchFilterType
import com.lostf1sh.pixelplayeross.data.model.SearchHistoryItem
import com.lostf1sh.pixelplayeross.data.model.SearchResultItem
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import com.lostf1sh.pixelplayeross.data.youtube.YouTubeMusicRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.FlowPreview

/**
 * Manages search state and operations.
 * Extracted from PlayerViewModel to improve modularity.
 *
 * Responsibilities:
 * - Search query execution
 * - Search filter management
 * - Search history CRUD operations
 */
@Singleton
class SearchStateHolder @Inject constructor(
    private val musicRepository: MusicRepository,
    private val youtubeMusicRepository: YouTubeMusicRepository,
) {
    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L

        /** YouTube is a network call per keystroke, so it waits longer than the library does. */
        const val YOUTUBE_SEARCH_DEBOUNCE_MS = 600L
    }

    private data class SearchRequest(
        val query: String,
        val filter: SearchFilterType,
        val requestId: Long,
        // A filter change re-runs a query the user already finished typing, so it skips the
        // typing debounce.
        val immediate: Boolean = false,
    )

    // Library results and YouTube results arrive at different speeds, so they are kept apart and
    // merged on every change: Room answers in milliseconds, YouTube in about a second.
    private val localResults = MutableStateFlow<ImmutableList<SearchResultItem>>(persistentListOf())
    private val youTubeResults = MutableStateFlow<ImmutableList<SearchResultItem>>(persistentListOf())
    private var youTubeJob: Job? = null

    private val _searchResults = MutableStateFlow<ImmutableList<SearchResultItem>>(persistentListOf())
    val searchResults = _searchResults.asStateFlow()

    private val _selectedSearchFilter = MutableStateFlow(SearchFilterType.ALL)
    val selectedSearchFilter = _selectedSearchFilter.asStateFlow()

    private val _searchHistory = MutableStateFlow<ImmutableList<SearchHistoryItem>>(persistentListOf())
    val searchHistory = _searchHistory.asStateFlow()

    // Replays the latest request so a collector restarted for another PlayerViewModel resumes
    // the current search; the request id check below drops a replayed query that is stale.
    private val searchRequests = MutableSharedFlow<SearchRequest>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val latestSearchRequestId = AtomicLong(0L)

    private var scope: CoroutineScope? = null
    private var searchJob: Job? = null
    private val owners = OwnerStack<CoroutineScope> { it }

    /**
     * Initialize with ViewModel scope.
     */
    fun initialize(scope: CoroutineScope) {
        owners.push(scope)
        this.scope = scope
        observeSearchRequests()
    }

    @OptIn(FlowPreview::class)
    private fun observeSearchRequests() {
        searchJob?.cancel()
        searchJob = scope?.launch {
            searchRequests
                .debounce { request -> if (request.immediate) 0L else SEARCH_DEBOUNCE_MS }
                .collectLatest { request ->
                    val normalizedQuery = request.query

                    if (normalizedQuery.isBlank()) {
                        youTubeJob?.cancel()
                        localResults.value = persistentListOf()
                        youTubeResults.value = persistentListOf()
                        if (_searchResults.value.isNotEmpty()) {
                            _searchResults.value = persistentListOf()
                        }
                        return@collectLatest
                    }

                    searchYouTube(normalizedQuery, request.filter, request.requestId)

                    try {
                        musicRepository.searchAll(normalizedQuery, request.filter).collect { resultsList ->
                            val sortedResults = resultsList.sortedWith(
                                compareBy { result ->
                                    when (result) {
                                        is SearchResultItem.SongItem -> 0
                                        is SearchResultItem.AlbumItem -> 1
                                        is SearchResultItem.ArtistItem -> 2
                                        is SearchResultItem.PlaylistItem -> 3
                                        is SearchResultItem.YouTubePlaylistItem -> 3
                                    }
                                }
                            )

                            if (request.requestId != latestSearchRequestId.get()) {
                                return@collect
                            }

                            localResults.value = sortedResults.toImmutableList()
                            publishResults()
                        }
                    } catch (_: CancellationException) {
                    } catch (e: Exception) {
                        if (request.requestId == latestSearchRequestId.get()) {
                            Timber.e(e, "Error performing search for query: $normalizedQuery")
                            localResults.value = persistentListOf()
                            publishResults()
                        }
                    }
                }
        }
    }


    /**
     * Republish the merged list. Called by both halves so a fast local answer is not held back
     * waiting for the network, and late YouTube rows land under the library hits.
     */
    private fun publishResults() {
        // YouTube can return the same video twice, and its list is merged with the library's.
        // The list keys rows by song id, so duplicates here are a crash, not a cosmetic issue.
        val merged = (localResults.value + youTubeResults.value)
            .distinctBy { result ->
                when (result) {
                    is SearchResultItem.SongItem -> "song_${result.song.id}"
                    is SearchResultItem.AlbumItem -> "album_${result.album.id}"
                    is SearchResultItem.ArtistItem -> "artist_${result.artist.id}"
                    is SearchResultItem.PlaylistItem -> "playlist_${result.playlist.id}"
                    is SearchResultItem.YouTubePlaylistItem -> "yt_playlist_${result.playlist.id}"
                }
            }
            .toImmutableList()
        if (_searchResults.value != merged) {
            _searchResults.value = merged
        }
    }

    /**
     * Song and playlist hits from YouTube, so search offers whole soundtracks next to single
     * tracks. Cancelled on every new query: a slow answer must not overwrite a newer one.
     */
    private fun searchYouTube(query: String, filter: SearchFilterType, requestId: Long) {
        youTubeJob?.cancel()
        youTubeResults.value = persistentListOf()
        // Lyrics search has its own provider; the other filters narrow which kinds are wanted,
        // matching what the local half returns for the same chip.
        if (filter == SearchFilterType.LYRICS) return
        val wantsSongs = filter == SearchFilterType.ALL || filter == SearchFilterType.SONGS
        val wantsPlaylists = filter == SearchFilterType.ALL || filter == SearchFilterType.PLAYLISTS

        youTubeJob = scope?.launch {
            delay(YOUTUBE_SEARCH_DEBOUNCE_MS)
            val songs = if (wantsSongs) {
                youtubeMusicRepository.searchSongs(query).getOrElse { emptyList() }
            } else {
                emptyList()
            }
            val playlists = if (wantsPlaylists) {
                youtubeMusicRepository.searchPlaylists(query).getOrElse { emptyList() }
            } else {
                emptyList()
            }
            if (requestId != latestSearchRequestId.get()) return@launch
            youTubeResults.value = (
                songs.map { SearchResultItem.SongItem(it) } +
                    playlists.map { SearchResultItem.YouTubePlaylistItem(it) }
                ).toImmutableList()
            publishResults()
        }
    }
    /**
     * Selects [filterType] and re-runs the current query with it straight away, so the results
     * switch together with the chip instead of after the screen re-issues the query and the
     * typing debounce runs out.
     */
    fun updateSearchFilter(filterType: SearchFilterType) {
        val previousFilter = _selectedSearchFilter.value
        if (previousFilter == filterType) return
        if (filterType == SearchFilterType.LYRICS || previousFilter == SearchFilterType.LYRICS) {
            youTubeJob?.cancel()
            localResults.value = persistentListOf()
            youTubeResults.value = persistentListOf()
            _searchResults.value = persistentListOf()
        }
        _selectedSearchFilter.value = filterType

        val currentQuery = searchRequests.replayCache.lastOrNull()?.query
        if (!currentQuery.isNullOrBlank()) {
            requestSearch(currentQuery, immediate = true)
        }
    }

    fun loadSearchHistory(limit: Int = 15) {
        scope?.launch {
            try {
                val history = withContext(Dispatchers.IO) {
                    musicRepository.getRecentSearchHistory(limit)
                }
                _searchHistory.value = history.toImmutableList()
            } catch (e: Exception) {
                Timber.e(e, "Error loading search history")
            }
        }
    }

    fun onSearchQuerySubmitted(query: String) {
        scope?.launch {
            if (query.isNotBlank()) {
                try {
                    withContext(Dispatchers.IO) {
                        musicRepository.addSearchHistoryItem(query)
                    }
                    loadSearchHistory()
                } catch (e: Exception) {
                    Timber.e(e, "Error adding search history item")
                }
            }
        }
    }

    fun performSearch(query: String) {
        requestSearch(query.trim(), immediate = false)
    }

    private fun requestSearch(normalizedQuery: String, immediate: Boolean) {
        val requestId = latestSearchRequestId.incrementAndGet()

        if (normalizedQuery.isBlank()) {
            if (_searchResults.value.isNotEmpty()) {
                _searchResults.value = persistentListOf()
            }
        }

        // The request carries its filter, so a request replayed for another PlayerViewModel
        // searches with the filter it was made for.
        searchRequests.tryEmit(
            SearchRequest(
                query = normalizedQuery,
                filter = _selectedSearchFilter.value,
                requestId = requestId,
                immediate = immediate,
            )
        )
    }

    fun deleteSearchHistoryItem(query: String) {
        scope?.launch {
            try {
                withContext(Dispatchers.IO) {
                    musicRepository.deleteSearchHistoryItemByQuery(query)
                }
                loadSearchHistory()
            } catch (e: Exception) {
                Timber.e(e, "Error deleting search history item")
            }
        }
    }

    fun clearSearchHistory() {
        scope?.launch {
            try {
                withContext(Dispatchers.IO) {
                    musicRepository.clearSearchHistory()
                }
                _searchHistory.value = persistentListOf()
            } catch (e: Exception) {
                Timber.e(e, "Error clearing search history")
            }
        }
    }

    /**
     * A stale PlayerViewModel clearing leaves the newer binding alone; the current one clearing
     * moves the search collector to the previous live PlayerViewModel's scope, where it resumes
     * the current query, so search keeps answering after ExternalPlayerActivity finishes over
     * MainActivity.
     */
    fun onCleared(owner: CoroutineScope) {
        owners.remove(owner) { next ->
            scope = next
            if (next != null) observeSearchRequests() else searchJob?.cancel()
        }
    }
}
