package com.lostf1sh.pixelplayeross.data.youtube

import android.content.Context
import com.lostf1sh.pixelplayeross.data.database.AlbumEntity
import com.lostf1sh.pixelplayeross.data.database.ArtistEntity
import com.lostf1sh.pixelplayeross.data.database.MusicDao
import com.lostf1sh.pixelplayeross.data.database.SongEntity
import com.lostf1sh.pixelplayeross.data.database.SourceType
import com.lostf1sh.pixelplayeross.data.model.ArtistRef
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.model.SongSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.absoluteValue

@Singleton
class YouTubeMusicRepository @Inject constructor(
    private val downloader: YouTubeDownloaderImpl,
    private val musicDao: MusicDao,
    private val okHttpClient: OkHttpClient,
    @ApplicationContext private val context: Context
) {
    private companion object {
        private const val YOUTUBE_SONG_ID_OFFSET = 7_000_000_000_000L
        private const val YOUTUBE_PARENT_DIRECTORY = "/Cloud/YouTube"
        private const val YOUTUBE_GENRE = "YouTube Music"

        /** googlevideo URLs are IP-bound and expire; cache resolved URLs well inside that window. */
        private const val STREAM_CACHE_TTL_MS = 60L * 60 * 1000
        private const val EXPIRY_BUFFER_MS = 5L * 60 * 1000

        private const val YOUTUBE_URI_PREFIX = "youtube://"

        /** How many results ahead of the tap get resolved in the background. */
        private const val PREFETCH_LIMIT = 2

        /** A pasted playlist link becomes a queue; cap it so a 5000-song list cannot hang the load. */
        private const val MAX_PLAYLIST_ITEMS = 300

        /** Upper bound on continuation pages fetched for one playlist. */
        private const val MAX_PLAYLIST_PAGES = 12

        /** How many playlist cards one search shows. */
        private const val PLAYLIST_RESULT_LIMIT = 12

        /** Joined budget for both probe requests; the resolver falls through on timeout. */
        private const val PROBE_TIMEOUT_MS = 8_000L
        private const val M4A_ATTEMPTS = 3
        private const val M4A_RETRY_DELAY_MS = 500L
        private val INVIDIOUS_HOSTS = listOf(
            "yt.chocolatemoo53.com",
            "inv.nadeko.net",
            "invidious.nerdvpn.de",
            "invidious.tiekoetter.com"
        )

        private const val PROBE_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

        // InnerTube client strategies, tried in parallel (first verified success wins).
        // Order mirrors battle-tested FOSS resolvers (Metrolist defaults): ANDROID_VR /
        // TVHTML5 need no PoToken, WEB_REMIX gives the best quality.
        // The IOS client used to be excluded here because its URLs only served bounded
        // ranges. NewPipeExtractor (v0.26.5) now does exactly that internally as the SABR
        // workaround, and it is our primary resolver — so this list is the InnerTube
        // fallback only.
        private val INNERTUBE_CLIENTS = listOf(
            InnerTubeClient(
                name = "ANDROID_VR",
                apiKey = "AIzaSyAO_FJ2SlqU8Q4sYse5xHxD262mP-LWyVA",
                endpoint = "https://www.youtube.com/youtubei/v1/player",
                userAgent = "com.google.android.apps.youtube.vr.oculus/1.56.21 (Linux; U; Android 12)",
                clientNameHeader = "28",
                clientVersionHeader = "1.56.21",
                isEmbedded = false,
                clientBody = """
                    "clientName": "ANDROID_VR",
                    "clientVersion": "1.56.21",
                    "deviceMake": "Oculus",
                    "deviceModel": "Quest 3",
                    "platform": "MOBILE",
                    "hl": "en",
                    "gl": "US"
                """.trimIndent()
            ),
            InnerTubeClient(
                name = "TVHTML5",
                apiKey = "AIzaSyAO_FJ2SlqU8Q4sYse5xHxD262mP-LWyVA",
                endpoint = "https://www.youtube.com/youtubei/v1/player",
                userAgent = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version,gzip(gfe)",
                clientNameHeader = "7",
                clientVersionHeader = "7.20251105.10.00",
                isEmbedded = false,
                clientBody = """
                    "clientName": "TVHTML5",
                    "clientVersion": "7.20251105.10.00",
                    "platform": "DESKTOP",
                    "hl": "en",
                    "gl": "US"
                """.trimIndent()
            ),
            InnerTubeClient(
                name = "WEB_REMIX",
                apiKey = "AIzaSyAO_FJ2SlqU8Q4sYse5xHxD262mP-LWyVA",
                endpoint = "https://music.youtube.com/youtubei/v1/player",
                userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                clientNameHeader = "67",
                clientVersionHeader = "1.20260114.03.00",
                isEmbedded = false,
                clientBody = """
                    "clientName": "WEB_REMIX",
                    "clientVersion": "1.20260114.03.00",
                    "hl": "en",
                    "gl": "US"
                """.trimIndent()
            ),
            InnerTubeClient(
                name = "ANDROID_SDKLESS",
                apiKey = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w",
                endpoint = "https://www.youtube.com/youtubei/v1/player",
                userAgent = "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip",
                clientNameHeader = "1",
                clientVersionHeader = "20.10.38",
                isEmbedded = false,
                clientBody = """
                    "clientName": "ANDROID",
                    "clientVersion": "20.10.38",
                    "platform": "MOBILE",
                    "osName": "Android",
                    "osVersion": "11",
                    "hl": "en",
                    "gl": "US"
                """.trimIndent()
            ),
            InnerTubeClient(
                name = "WEB_EMBEDDED_PLAYER",
                apiKey = "AIzaSyAO_FJ2SlqU8Q4sYse5xHxD262mP-LWyVA",
                endpoint = "https://www.youtube.com/youtubei/v1/player",
                userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
                clientNameHeader = "56",
                clientVersionHeader = "1.20240101.01.00",
                isEmbedded = true,
                clientBody = """
                    "clientName": "WEB_EMBEDDED_PLAYER",
                    "clientVersion": "1.20240101.01.00",
                    "platform": "DESKTOP",
                    "hl": "en",
                    "gl": "US"
                """.trimIndent()
            )
        )
    }

    private data class InnerTubeClient(
        val name: String,
        val apiKey: String,
        val endpoint: String,
        val userAgent: String,
        val clientNameHeader: String,
        val clientVersionHeader: String,
        val isEmbedded: Boolean,
        val clientBody: String
    )

    @Volatile
    private var cachedVisitorData: String? = null

    private data class CachedStreamUrl(val url: String, val resolvedAtMs: Long) {
        val isUsable: Boolean
            get() {
                // Trust the URL's own expiry when present (googlevideo always sets it),
                // falling back to the fixed TTL window otherwise. Treat as expired 5
                // minutes early, like battle-tested resolvers do.
                val effectiveExpiryMs = runCatching {
                    val expire = java.net.URI(url).rawQuery
                        ?.split('&')
                        ?.firstOrNull { it.startsWith("expire=") }
                        ?.substringAfter("expire=")
                        ?.toLongOrNull()
                    expire?.let { it * 1000L }
                }.getOrNull()
                    ?: (resolvedAtMs + STREAM_CACHE_TTL_MS)
                return System.currentTimeMillis() < effectiveExpiryMs - EXPIRY_BUFFER_MS
            }
    }

    private val resolvedStreamCache = ConcurrentHashMap<String, CachedStreamUrl>()

    /**
     * Resolving a stream URL is the slow half of starting playback: the extractor fetches the
     * watch page, picks a format and probes it (measured 5.6 s cold vs 0.19 s cached on the
     * emulator). None of that work needs the user's tap, so the tracks a user is most likely
     * to play next are resolved ahead of time and the tap lands on the cache instead.
     *
     * Two workers: a single worker made a tap on the second row wait for the first row's
     * resolution to finish, which is worse than no prefetch at all.
     */
    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(2))

    /** Ids with a prefetch already running, so repeated searches don't queue duplicates. */
    private val prefetchInFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Resolutions currently running, keyed by video id.
     *
     * Without this, tapping a row while its prefetch is still in flight starts the whole
     * extractor ladder a second time — the user waits for a duplicate of work that was
     * already three seconds along, and both requests hit YouTube.
     */
    private val pendingResolutions = ConcurrentHashMap<String, CompletableDeferred<Result<String>>>()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    init {
        try {
            NewPipe.init(downloader)
            _isInitialized.value = true
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize NewPipeExtractor")
        }
    }

    /** The video id behind a YouTube [song], or null for anything else. */
    fun videoIdOf(song: Song): String? =
        song.contentUriString
            .takeIf { it.startsWith(YOUTUBE_URI_PREFIX) }
            ?.removePrefix(YOUTUBE_URI_PREFIX)
            ?.substringBefore('?')
            ?.takeIf { it.isNotBlank() }

    /**
     * Resolve the first [limit] tracks' stream URLs in the background, so tapping one is a
     * cache hit instead of a five second wait. Best effort: a failure here is invisible and
     * simply means the tap pays the normal price.
     */
    fun prefetchStreamUrls(songs: List<Song>, limit: Int = PREFETCH_LIMIT) {
        songs.asSequence()
            .mapNotNull(::videoIdOf)
            .distinct()
            .filter { resolvedStreamCache[it]?.isUsable != true }
            .take(limit)
            .forEach { videoId ->
                if (!prefetchInFlight.add(videoId)) return@forEach
                prefetchScope.launch {
                    try {
                        getAudioStreamUrl(videoId)
                    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.d(e, "Prefetch failed for %s", videoId)
                    } finally {
                        prefetchInFlight.remove(videoId)
                    }
                }
            }
    }

    /**
     * Search YouTube / YouTube Music for tracks matching [query].
     * Uses NewPipe extractor (reliable for search).
     */
    suspend fun searchSongs(query: String): Result<List<Song>> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext Result.success(emptyList())
        try {
            val searchInfo = SearchInfo.getInfo(
                ServiceList.YouTube,
                ServiceList.YouTube.searchQHFactory.fromQuery(query)
            )

            val songs = mutableListOf<Song>()
            for (item in searchInfo.relatedItems) {
                if (item is StreamInfoItem) {
                    val song = mapStreamInfoToSong(item)
                    songs.add(song)
                }
            }
            // The first rows are the ones a tap reaches for; resolving them now turns a five
            // second cold start into an instant one.
            prefetchStreamUrls(songs)
            Result.success(songs)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Swallowing this into an empty list is what made a failed search look like
            // "no results"; callers need the failure to say something honest.
            Timber.e(e, "YouTube search failed for query: $query")
            Result.failure(e)
        }
    }

    /**
     * Playlist results for a query, so search can offer whole soundtracks next to single songs.
     * Only summaries: fetching the tracks is deferred until the user actually taps one.
     */
    suspend fun searchPlaylists(
        query: String,
        limit: Int = PLAYLIST_RESULT_LIMIT
    ): Result<List<YouTubePlaylistSummary>> = withContext(Dispatchers.IO) {
        try {
            val handler = ServiceList.YouTube.searchQHFactory.fromQuery(
                query,
                listOf(YoutubeSearchQueryHandlerFactory.PLAYLISTS),
                ""
            )
            val info = SearchInfo.getInfo(ServiceList.YouTube, handler)
            val playlists = info.relatedItems
                .filterIsInstance<PlaylistInfoItem>()
                .mapNotNull { item ->
                    val id = extractYouTubePlaylistId(item.url) ?: return@mapNotNull null
                    YouTubePlaylistSummary(
                        id = id,
                        title = item.name,
                        uploader = item.uploaderName,
                        trackCount = item.streamCount.takeIf { it > 0 },
                        thumbnailUrl = item.thumbnails.firstOrNull()?.url
                    )
                }
                .distinctBy { it.id }
                .take(limit)
            Result.success(playlists)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Could not search YouTube playlists for %s", query)
            Result.failure(e)
        }
    }
    /**
     * Resolve a YouTube playlist link into songs the player can queue, using the same extractor
     * as search so every item carries the same ids, artwork and stream URI a search hit would.
     *
     * Links arrive in every shape YouTube hands out (watch links with `&list=`, music.youtube.com
     * share links, or a bare id), so the id is parsed first and one canonical URL is fetched.
     */
    suspend fun fetchPlaylist(
        linkOrId: String,
        maxItems: Int = MAX_PLAYLIST_ITEMS,
        prefetch: Boolean = true
    ): Result<YouTubePlaylist> = withContext(Dispatchers.IO) {
        val playlistId = extractYouTubePlaylistId(linkOrId)
            ?: return@withContext Result.failure(
                IllegalArgumentException("Not a YouTube playlist link: $linkOrId")
            )
        val playlistUrl = "https://www.youtube.com/playlist?list=$playlistId"

        try {
            val info = PlaylistInfo.getInfo(ServiceList.YouTube, playlistUrl)
            // Keep insertion order: the queue should play in the order the playlist shows.
            val songs = LinkedHashMap<String, Song>()
            fun add(item: StreamInfoItem) {
                if (songs.size >= maxItems) return
                val song = mapStreamInfoToSong(item)
                if (!songs.containsKey(song.path)) songs[song.path] = song
            }

            info.relatedItems.filterIsInstance<StreamInfoItem>().forEach(::add)
            var page = info.nextPage
            var pages = 0
            while (page != null && songs.size < maxItems && pages < MAX_PLAYLIST_PAGES) {
                val more = PlaylistInfo.getMoreItems(ServiceList.YouTube, playlistUrl, page)
                more.items.filterIsInstance<StreamInfoItem>().forEach(::add)
                page = more.nextPage
                pages++
            }

            val ordered = songs.values.toList()
            if (ordered.isEmpty()) {
                return@withContext Result.failure(
                    IllegalStateException("Playlist $playlistId has no playable items")
                )
            }
            if (prefetch) prefetchStreamUrls(ordered, limit = PREFETCH_LIMIT)
            Timber.d("Loaded playlist %s (%d items in %d pages)", playlistId, ordered.size, pages)
            Result.success(
                YouTubePlaylist(
                    id = playlistId,
                    title = info.name?.takeIf { it.isNotBlank() } ?: playlistId,
                    songs = ordered
                )
            )
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Could not load YouTube playlist: %s", playlistId)
            Result.failure(e)
        }
    }
    /**
     * The first few track covers of a playlist, for the stacked cover in search results.
     *
     * Only the first page is read and no stream URL is resolved: this runs for every playlist
     * card that scrolls into view, so it has to stay cheap. Returns an empty list when the
     * playlist has no artwork at all, and the caller falls back to the playlist thumbnail.
     */
    suspend fun fetchPlaylistCovers(
        playlistId: String,
        limit: Int = 3
    ): Result<List<String>> = fetchPlaylist(playlistId, maxItems = limit, prefetch = false)
        .map { playlist -> playlist.songs.mapNotNull { it.albumArtUriString }.distinct().take(limit) }

    /**
     * Resolve the direct audio stream URL for a given YouTube [videoId].
     *
     * Strategy: race all InnerTube clients in parallel (first success wins), then race every
     * fallback service in parallel. Resolved URLs are cached for [STREAM_CACHE_TTL_MS] so
     * replays and queue skips don't pay the resolver ladder again.
     */
    /**
     * The stream to save when the user downloads a track.
     *
     * Downloads prefer the AAC/MP4 variant because that is a real music file other players can read
     * and tag; the opus/webm variant that streaming prefers has no tag support in practice.
     */
    suspend fun getDownloadStream(videoId: String): Result<YouTubeDownloadStream> =
        withContext(Dispatchers.IO) {
            extractM4aStream(videoId)?.let { return@withContext Result.success(it) }
            Timber.d("No m4a stream for %s, falling back to the streaming url", videoId)
            getAudioStreamUrl(videoId).map { url -> YouTubeDownloadStream(url, "", 0) }
        }

    // YouTube intermittently answers an InnerTube call with an HTML error page, which NewPipe
    // surfaces as a parsing exception. One retry usually lands on a working response, and when
    // every attempt fails the Invidious endpoints can still name an AAC stream.
    private suspend fun extractM4aStream(videoId: String): YouTubeDownloadStream? {
        repeat(M4A_ATTEMPTS) { attempt ->
            tryExtractM4aStream(videoId)?.let { return it }
            if (attempt < M4A_ATTEMPTS - 1) delay(M4A_RETRY_DELAY_MS)
        }
        return getM4aStreamViaInvidious(videoId)
    }

    private fun tryExtractM4aStream(videoId: String): YouTubeDownloadStream? = try {
        val extractor = ServiceList.YouTube.getStreamExtractor(
            "https://www.youtube.com/watch?v=$videoId"
        )
        extractor.fetchPage()
        val candidates = extractor.audioStreams
            .filter { stream -> !stream.url.isNullOrBlank() }
            .filter { stream ->
                val mime = stream.format?.mimeType.orEmpty()
                mime.startsWith("audio/mp4", ignoreCase = true) ||
                    stream.format?.name?.contains("m4a", ignoreCase = true) == true
            }
            .sortedByDescending { stream -> stream.averageBitrate }
        for (stream in candidates) {
            val url = stream.url ?: continue
            // A probe that times out says nothing about the stream, and downloads do their own
            // byte-range check while fetching, so a dead probe is not a reason to give up.
            val verified = try {
                verifyStreamUrlNot403(url)
            } catch (error: Exception) {
                Timber.d(error, "m4a probe for %s did not finish", videoId)
                false
            }
            if (verified) {
                Timber.d("m4a download stream for %s: %d kbps", videoId, stream.averageBitrate)
                return YouTubeDownloadStream(url, "audio/mp4", stream.averageBitrate)
            }
        }
        val best = candidates.firstOrNull()?.url?.takeIf { it.isNotBlank() }
            ?: return null
        Timber.d("No verified m4a stream for %s, saving the best candidate anyway", videoId)
        YouTubeDownloadStream(best, "audio/mp4", candidates.first().averageBitrate)
    } catch (error: Exception) {
        Timber.w(error, "Could not extract an m4a stream for %s", videoId)
        null
    }

    suspend fun getAudioStreamUrl(videoId: String): Result<String> = withContext(Dispatchers.IO) {
        resolvedStreamCache[videoId]?.let { cached ->
            if (cached.isUsable) {
                Timber.d("Stream cache hit for %s", videoId)
                return@withContext Result.success(cached.url)
            }
            resolvedStreamCache.remove(videoId)
        }

        // Join a resolution that is already running (a prefetch, or a second tap on the same
        // row) instead of paying for the same ladder twice.
        val pending = CompletableDeferred<Result<String>>()
        pendingResolutions.putIfAbsent(videoId, pending)?.let { running ->
            return@withContext running.await()
        }
        try {
            resolveAudioStreamUrl(videoId).also { pending.complete(it) }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            pending.complete(Result.failure(e))
            throw e
        } catch (e: Exception) {
            pending.complete(Result.failure(e))
            Result.failure(e)
        } finally {
            pendingResolutions.remove(videoId, pending)
        }
    }

    private suspend fun resolveAudioStreamUrl(videoId: String): Result<String> = withContext(Dispatchers.IO) {
        resolvedStreamCache[videoId]?.let { cached ->
            if (cached.isUsable) {
                Timber.d("Stream cache hit for %s", videoId)
                return@withContext Result.success(cached.url)
            }
            resolvedStreamCache.remove(videoId)
        }

        // Phase 1: NewPipeExtractor first. We pin v0.26.5, which carries upstream's
        // SABR workaround (NewPipeExtractor PR #1508): it fetches playback formats with
        // a player client YouTube has not put behind SABR yet, so the returned
        // googlevideo URLs answer open-ended GETs and high byte offsets (verified:
        // bytes=0-1999999 -> 206, bytes=2000000-2524287 -> 206), unlike the
        // PoToken-capped URLs every other path can produce. This is the most reliable
        // resolver, so it gets first refusal and everything below is a fallback.
        if (_isInitialized.value) {
            val newPipeResult = getAudioStreamViaNewPipe(videoId)
            if (newPipeResult.isSuccess) {
                cacheStreamUrl(videoId, newPipeResult.getOrThrow())
                return@withContext newPipeResult
            }
            // Not fatal: some videos (live, age-gated, region-locked) expose no audio
            // formats to the extractor at all. The InnerTube and hybrid fallbacks below
            // cover those, so this stays at debug level instead of looking like an error.
            Timber.d(newPipeResult.exceptionOrNull(), "NewPipe resolve failed for %s", videoId)
        }

        // Phase 2: race all InnerTube clients concurrently.
        val innerTubeResult = getAudioStreamViaInnerTube(videoId)
        if (innerTubeResult.isSuccess) {
            cacheStreamUrl(videoId, innerTubeResult.getOrThrow())
            return@withContext innerTubeResult
        }
        Timber.w(innerTubeResult.exceptionOrNull(), "InnerTube race failed for %s", videoId)

        // Phase 3: race every remaining fallback service concurrently. They run
        // regardless of NewPipe init state — Cobalt/Piped/Invidious don't need the
        // extractor, so a failed init must not disable them.
        val fallbackJobs = mutableListOf<suspend () -> Result<String>>(
            { getAudioStreamViaCobalt(videoId) },
            { getAudioStreamViaPiped(videoId) },
            { getAudioStreamViaInvidious(videoId) }
        )
        val fallbackResult = raceForFirstSuccess(fallbackJobs)
        if (fallbackResult.isSuccess) {
            cacheStreamUrl(videoId, fallbackResult.getOrThrow())
        } else {
            Timber.e(
                fallbackResult.exceptionOrNull(),
                "All stream strategies (InnerTube, Cobalt, NewPipe, Piped, Invidious) failed for videoId: %s",
                videoId
            )
        }
        fallbackResult
    }

    /**
     * Launch every job concurrently and return the first successful result, cancelling the
     * rest. Turns a serial fallback ladder (seconds to minutes) into a single latency budget.
     */
    private suspend fun raceForFirstSuccess(jobs: List<suspend () -> Result<String>>): Result<String> {
        if (jobs.isEmpty()) {
            return Result.failure(IllegalStateException("No stream strategies to race"))
        }
        var lastError: Throwable? = null
        return coroutineScope {
            val channel = Channel<Result<String>>(Channel.UNLIMITED)
            jobs.forEach { job ->
                launch {
                    try {
                        channel.send(job())
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        channel.send(Result.failure(e))
                    }
                }
            }
            repeat(jobs.size) {
                val result = channel.receive()
                if (result.isSuccess) {
                    return@coroutineScope result
                }
                if (lastError == null) lastError = result.exceptionOrNull()
            }
            Result.failure(lastError ?: IllegalStateException("All stream strategies failed"))
        }
    }

    private fun cacheStreamUrl(videoId: String, url: String) {
        resolvedStreamCache[videoId] = CachedStreamUrl(url, System.currentTimeMillis())
    }

    /** Drop a cached URL, e.g. after the player hits an expired/IP-bound 403 mid-playback. */
    fun evictStreamUrl(videoId: String) {
        resolvedStreamCache.remove(videoId)
    }

    /**
     * Cobalt API fallback — high-availability media extractor API.
     */
    private suspend fun getAudioStreamViaCobalt(videoId: String): Result<String> = withContext(Dispatchers.IO) {
        val cobaltInstances = listOf(
            "https://api.cobalt.tools/",
            "https://cobalt.api.sc7.io/"
        )
        val bodyJson = """
            {
                "url": "https://www.youtube.com/watch?v=$videoId"
            }
        """.trimIndent()

        for (apiUrl in cobaltInstances) {
            try {
                Timber.d("Trying Cobalt API instance: $apiUrl for videoId: $videoId")
                val request = Request.Builder()
                    .url(apiUrl)
                    .post(bodyJson.toRequestBody("application/json".toMediaType()))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "PixelPlayer/1.0")
                    .build()
                val response = okHttpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    Timber.w("Cobalt instance $apiUrl HTTP ${response.code}")
                    continue
                }
                val responseBody = response.body.string()
                val json = JSONObject(responseBody)
                val streamUrl = json.optString("url", "").ifBlank { json.optString("picker", "") }
                if (streamUrl.isNotBlank()) {
                    Timber.d("Cobalt API SUCCESS via $apiUrl: ${streamUrl.take(80)}...")
                    return@withContext Result.success(streamUrl)
                }
            } catch (e: Exception) {
                Timber.w("Cobalt instance $apiUrl exception: ${e.message}")
            }
        }
        return@withContext Result.failure(IllegalStateException("All Cobalt instances failed for $videoId"))
    }

    /**
     * Invidious API fallback — queries reliable public Invidious instances for audio streams.
     */
    private suspend fun getAudioStreamViaInvidious(videoId: String): Result<String> = withContext(Dispatchers.IO) {
        for (apiUrl in invidiousVideoEndpoints(videoId)) {
            try {
                Timber.d("Trying Invidious API instance: $apiUrl")
                val request = Request.Builder()
                    .url(apiUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .build()
                val response = okHttpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    Timber.w("Invidious instance $apiUrl HTTP ${response.code}")
                    continue
                }
                val responseBody = response.body.string()
                val json = JSONObject(responseBody)
                val adaptiveFormats = json.optJSONArray("adaptiveFormats") ?: continue

                var bestUrl: String? = null
                var maxBitrate = -1
                for (i in 0 until adaptiveFormats.length()) {
                    val format = adaptiveFormats.getJSONObject(i)
                    val mimeType = format.optString("type", "")
                    if (!mimeType.startsWith("audio/")) continue
                    val url = format.optString("url", "")
                    if (url.isBlank()) continue
                    val bitrate = format.optInt("bitrate", 0)
                    val isOpus = mimeType.contains("opus", ignoreCase = true)
                    val score = if (isOpus) bitrate + 100_000 else bitrate

                    if (score > maxBitrate) {
                        maxBitrate = score
                        bestUrl = url
                    }
                }
                if (bestUrl != null) {
                    // The instance load-balances each request to a random worker node,
                    // but the stream URL's pot= PoToken is bound to the node listed in
                    // issuer=. A redirect to a different node can 403. Rewrite the host
                    // to the issuer so every fetch lands on the node that minted the
                    // token (verified empirically: issuer-direct works, roulette 403s).
                    val issuer = Regex("[?&]issuer=([^&]+)").find(bestUrl)?.groupValues?.get(1)
                    val finalUrl = if (!issuer.isNullOrBlank()) {
                        Regex("^(https://)[^/]+/").replace(bestUrl, "$1$issuer/")
                    } else bestUrl
                    // Verify with a real 100KB range before handing to the player:
                    // instances 403 some requests transiently, and ExoPlayer does not
                    // retry 403s — it would skip the track. A failed URL means try the
                    // next instance instead.
                    if (verifyStreamUrlNot403(finalUrl)) {
                        Timber.d("Invidious API SUCCESS via $apiUrl: selected stream url=${finalUrl.take(80)}...")
                        return@withContext Result.success(finalUrl)
                    }
                    Timber.w("Invidious instance $apiUrl URL failed live verify, trying next instance")
                }
            } catch (e: Exception) {
                Timber.w("Invidious instance $apiUrl exception: ${e.message}")
            }
        }
        return@withContext Result.failure(IllegalStateException("All Invidious instances failed for $videoId"))
    }

    /**
     * Ask the Invidious endpoints for an AAC stream when the InnerTube extractor keeps failing.
     *
     * The same host rewrite and range probe as the playback fallback apply: an instance hands out
     * a PoToken bound to the node named in `issuer`, so the URL is pinned to that node before use.
     */
    private suspend fun getM4aStreamViaInvidious(videoId: String): YouTubeDownloadStream? =
        withContext(Dispatchers.IO) {
            for (apiUrl in invidiousVideoEndpoints(videoId)) {
                try {
                    val request = Request.Builder()
                        .url(apiUrl)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                        .build()
                    val response = okHttpClient.newCall(request).execute()
                    if (!response.isSuccessful) continue
                    val formats = JSONObject(response.body.string()).optJSONArray("adaptiveFormats")
                        ?: continue
                    var bestUrl: String? = null
                    var bestBitrate = -1
                    for (index in 0 until formats.length()) {
                        val format = formats.getJSONObject(index)
                        val mime = format.optString("type", "")
                        if (!mime.startsWith("audio/mp4", ignoreCase = true)) continue
                        val url = format.optString("url", "")
                        if (url.isBlank()) continue
                        val bitrate = format.optInt("bitrate", 0)
                        if (bitrate > bestBitrate) {
                            bestBitrate = bitrate
                            bestUrl = url
                        }
                    }
                    val candidate = bestUrl?.let(::pinToIssuerNode)?.takeIf { it.isNotBlank() }
                        ?: continue
                    val usable = try {
                        verifyStreamUrlNot403(candidate)
                    } catch (error: Exception) {
                        Timber.d(error, "m4a probe for %s did not finish", videoId)
                        false
                    }
                    if (usable) {
                        Timber.d("m4a stream for %s from Invidious: %d kbps", videoId, bestBitrate / 1000)
                        return@withContext YouTubeDownloadStream(candidate, "audio/mp4", bestBitrate / 1000)
                    }
                } catch (error: Exception) {
                    Timber.w(error, "Invidious m4a lookup failed for %s", videoId)
                }
            }
            null
        }

    private fun invidiousVideoEndpoints(videoId: String): List<String> = INVIDIOUS_HOSTS.map { host ->
        "https://$host/api/v1/videos/$videoId?local=true"
    }

    /** Pin a PoToken-bearing URL to the node that minted it (see the playback fallback). */
    private fun pinToIssuerNode(url: String): String {
        val issuer = Regex("[?&]issuer=([^&]+)").find(url)?.groupValues?.get(1)
        return if (issuer.isNullOrBlank()) url
        else Regex("^(https://)[^/]+/").replace(url, "$1$issuer/")
    }
    /**
     * Piped API fallback — queries reliable public Piped instances for audio streams.
     */
    private suspend fun getAudioStreamViaPiped(videoId: String): Result<String> = withContext(Dispatchers.IO) {
        val pipedInstances = listOf(
            "https://pipedapi.kavin.rocks/streams/$videoId",
            "https://pipedapi.leptons.xyz/streams/$videoId",
            "https://pipedapi.nosebs.ru/streams/$videoId",
            "https://piped-api.privacy.com.de/streams/$videoId",
            "https://pipedapi.adminforge.de/streams/$videoId"
        )
        for (apiUrl in pipedInstances) {
            try {
                Timber.d("Trying Piped API instance: $apiUrl")
                val request = Request.Builder()
                    .url(apiUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .build()
                val response = okHttpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    Timber.w("Piped instance $apiUrl HTTP ${response.code}")
                    continue
                }
                val responseBody = response.body.string()
                val json = JSONObject(responseBody)
                val audioStreams = json.optJSONArray("audioStreams") ?: continue

                var bestUrl: String? = null
                var maxBitrate = -1
                for (i in 0 until audioStreams.length()) {
                    val stream = audioStreams.getJSONObject(i)
                    val streamUrl = stream.optString("url", "")
                    if (streamUrl.isBlank()) continue
                    val bitrate = stream.optInt("bitrate", 0)
                    val mimeType = stream.optString("mimeType", "")
                    val isOpus = mimeType.contains("opus", ignoreCase = true)
                    val score = if (isOpus) bitrate + 100_000 else bitrate

                    if (score > maxBitrate) {
                        maxBitrate = score
                        bestUrl = streamUrl
                    }
                }
                if (bestUrl != null) {
                    Timber.d("Piped API SUCCESS via $apiUrl: selected stream url=${bestUrl.take(80)}...")
                    return@withContext Result.success(bestUrl)
                }
            } catch (e: Exception) {
                Timber.w("Piped instance $apiUrl exception: ${e.message}")
            }
        }
        return@withContext Result.failure(IllegalStateException("All Piped instances failed for $videoId"))
    }

    /**
     * Direct InnerTube API call — tries ANDROID_VR, WEB_REMIX, TVHTML5, ANDROID_MUSIC, IOS clients in order.
     * Returns the highest-quality audio stream URL.
     */
    /**
     * Race all InnerTube clients concurrently — first success wins and the remaining
     * requests are cancelled. Six serial round trips collapse into one latency budget.
     */
    private suspend fun getAudioStreamViaInnerTube(videoId: String): Result<String> {
        return raceForFirstSuccess(
            INNERTUBE_CLIENTS.map { client -> { callInnerTubePlayer(videoId, client) } }
        )
    }

    private fun fetchVisitorData(): String? {
        cachedVisitorData?.let { return it }
        try {
            val body = """
                {
                    "context": {
                        "client": {
                            "clientName": "WEB",
                            "clientVersion": "2.20260114.00.00"
                        }
                    }
                }
            """.trimIndent()
            val request = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false&key=AIzaSyAO_FJ2SlqU8Q4sYse5xHxD262mP-LWyVA")
                .post(body.toRequestBody("application/json".toMediaType()))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("X-YouTube-Client-Name", "1")
                .header("X-YouTube-Client-Version", "2.20260114.00.00")
                .build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body.string()
                val json = JSONObject(responseBody)
                val rawVisitorData = json.optJSONObject("responseContext")?.optString("visitorData")
                if (!rawVisitorData.isNullOrBlank()) {
                    val visitorData = java.net.URLDecoder.decode(rawVisitorData, "UTF-8")
                    cachedVisitorData = visitorData
                    Timber.d("Fetched visitorData: $visitorData")
                    return visitorData
                }
            } else {
                Timber.w("fetchVisitorData HTTP failure: ${response.code}")
            }
        } catch (e: Exception) {
            Timber.w("fetchVisitorData exception: ${e.message}")
        }
        return null
    }

    private fun callInnerTubePlayer(videoId: String, client: InnerTubeClient): Result<String> {
        val thirdPartyPart = if (client.isEmbedded) {
            """, "thirdParty": { "embedUrl": "https://www.youtube.com/watch?v=$videoId" }"""
        } else ""

        val visitorData = fetchVisitorData()
        val visitorDataJson = if (!visitorData.isNullOrBlank()) {
            """, "visitorData": "$visitorData""""
        } else ""

        val body = """
            {
                "context": {
                    "client": {
                        ${client.clientBody}
                        $visitorDataJson
                    }
                },
                "videoId": "$videoId",
                "contentCheckOk": true,
                "racyCheckOk": true
                $thirdPartyPart
            }
        """.trimIndent()

        val requestBuilder = Request.Builder()
            .url("${client.endpoint}?prettyPrint=false&key=${client.apiKey}")
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("User-Agent", client.userAgent)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("X-YouTube-Client-Name", client.clientNameHeader)
            .header("X-YouTube-Client-Version", client.clientVersionHeader)

        if (!visitorData.isNullOrBlank()) {
            requestBuilder.header("X-Goog-Visitor-Id", visitorData)
        }

        if (client.name.startsWith("WEB") || client.isEmbedded) {
            requestBuilder.header("Origin", "https://music.youtube.com")
            requestBuilder.header("Referer", "https://music.youtube.com/")
        }

        val request = requestBuilder.build()

        Timber.d("Posting to InnerTube ${client.name}: ${client.endpoint}")
        val response = okHttpClient.newCall(request).execute()
        val responseBody = response.body.string()

        if (!response.isSuccessful) {
            Timber.w("InnerTube ${client.name} HTTP failure: ${response.code}, body: $responseBody")
            return Result.failure(IllegalStateException("InnerTube ${client.name} HTTP ${response.code}"))
        }

        val json = JSONObject(responseBody)

        // Check playability status
        val playabilityStatus = json.optJSONObject("playabilityStatus")
        val status = playabilityStatus?.optString("status", "")
        Timber.d("InnerTube ${client.name} playability status: $status")
        if (status != "OK") {
            val reason = playabilityStatus?.optString("reason", "Unknown")
            Timber.w("Video not playable (${client.name}): status=$status, reason=$reason")
            return Result.failure(IllegalStateException("Video not playable ($status): $reason"))
        }

        // Extract adaptive audio streams
        val streamingData = json.optJSONObject("streamingData")
        if (streamingData == null) {
            Timber.w("No streamingData in response for ${client.name}")
            return Result.failure(IllegalStateException("No streamingData in response"))
        }

        val formatsList = mutableListOf<JSONObject>()
        streamingData.optJSONArray("adaptiveFormats")?.let { array ->
            for (i in 0 until array.length()) formatsList.add(array.getJSONObject(i))
        }
        streamingData.optJSONArray("formats")?.let { array ->
            for (i in 0 until array.length()) formatsList.add(array.getJSONObject(i))
        }

        if (formatsList.isEmpty()) {
            Timber.w("No formats in response for ${client.name}")
            return Result.failure(IllegalStateException("No formats in response"))
        }

        Timber.d("InnerTube ${client.name} returned ${formatsList.size} formats")

        // Find best audio stream candidate — prefer Opus, then highest bitrate, and verify non-403.
        // Audio-only formats only: this is an audio player, and video-only formats carry no audio
        // track. Their raw bitrate dwarfs any audio format, so admitting them to the score race
        // always picks silence. (The old HEAD probe accidentally filtered video URLs out by
        // failing on them; the ranged-GET probe passes them, which unmasked this bug.)
        val candidateFormats = mutableListOf<Pair<Int, String>>()

        for (format in formatsList) {
            val mimeType = format.optString("mimeType", "")
            val isAudioOnly = mimeType.startsWith("audio/")

            if (!isAudioOnly) continue

            // Direct URL is required; signatureCipher requires JS deciphering so skip un-deciphered raw url
            val url = format.optString("url", "")
            if (url.isBlank()) continue

            // rqh=1 URLs are PoToken-gated: googlevideo serves only the first ~1MB
            // (verified empirically: start<999050 -> 206, start>=999100 -> 403, any
            // range shape). Songs longer than ~1MB would stop mid-playback, so these
            // URLs are unusable — fall through to the PoToken-backed fallbacks
            // (Invidious local=true, NewPipe extractor).
            if (url.contains("rqh=1")) continue

            val bitrate = format.optInt("bitrate", 0)
            val isOpus = mimeType.contains("opus", ignoreCase = true)
            val score = if (isOpus) bitrate + 100_000 else bitrate

            candidateFormats.add(score to url)
        }

        candidateFormats.sortByDescending { it.first }

        for ((score, url) in candidateFormats) {
            if (verifyStreamUrlNot403(url)) {
                Timber.d("InnerTube ${client.name} selected verified stream: score=$score, url=${url.take(80)}...")
                return Result.success(url)
            } else {
                Timber.w("InnerTube ${client.name} candidate returned 403 Forbidden, checking next format...")
            }
        }

        Timber.w("No verified audio stream URL found in adaptiveFormats for ${client.name}")
        return Result.failure(IllegalStateException("No verified audio streams found via ${client.name}"))
    }

    private fun verifyStreamUrlNot403(streamUrl: String): Boolean {
        // A short probe near the start is not enough: PoToken-gated URLs happily answer
        // bytes=0-99999 with 206 and then 403 every byte past ~1 MB, which makes a song
        // die a few seconds in. So probe the head AND a tiny window past the 1 MB mark,
        // and only accept URLs that serve both.
        //
        // The two probes are independent, so they are issued at the same time: running them
        // one after the other added a full round trip to every cold start, and this sits on
        // the path between the tap and the first audible sample.
        val headCode = AtomicInteger(0)
        val pastFirstMegabyteCode = AtomicInteger(0)
        val bothDone = CountDownLatch(2)
        enqueueProbe(streamUrl, "bytes=0-99999", headCode, bothDone)
        enqueueProbe(streamUrl, "bytes=1048576-1099999", pastFirstMegabyteCode, bothDone)
        bothDone.await(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)

        val head = headCode.get()
        if (head !in 200..206) {
            Timber.d("Stream probe rejected %s: head HTTP %s", describeStreamHost(streamUrl), head)
            return false
        }
        val pastFirstMegabyte = pastFirstMegabyteCode.get()
        if (pastFirstMegabyte in 200..206) return true
        // 416 means the track simply ends before the 1 MB mark; the head probe already
        // proved it can be played, so a short track is fine.
        if (pastFirstMegabyte == 416) return true
        Timber.d(
            "Stream probe rejected %s: capped past 1 MB (HTTP %s)",
            describeStreamHost(streamUrl), pastFirstMegabyte
        )
        return false
    }

    /** Queues one bounded GET probe; records the HTTP code (0 when it failed) and counts down. */
    private fun enqueueProbe(
        streamUrl: String,
        range: String,
        code: AtomicInteger,
        done: CountDownLatch
    ) {
        val request = Request.Builder()
            .url(streamUrl)
            .header("Range", range)
            .header("User-Agent", PROBE_USER_AGENT)
            .build()
        try {
            okHttpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Timber.w(e, "Stream probe failed for %s (%s)", describeStreamHost(streamUrl), range)
                    code.set(0)
                    done.countDown()
                }

                override fun onResponse(call: Call, response: Response) {
                    code.set(response.code)
                    response.close()
                    done.countDown()
                }
            })
        } catch (e: Exception) {
            Timber.w(e, "Stream probe could not be queued for %s", describeStreamHost(streamUrl))
            done.countDown()
        }
    }

    private fun describeStreamHost(streamUrl: String): String =
        streamUrl.substringAfter("://").substringBefore('/') + if (streamUrl.contains("pot=")) " [pot]" else ""

    /**
     * NewPipe extractor fallback — used when InnerTube API fails.
     */
    @Suppress("DEPRECATION")
    private fun getAudioStreamViaNewPipe(videoId: String): Result<String> {
        return try {
            val url = "https://www.youtube.com/watch?v=$videoId"
            val extractor = ServiceList.YouTube.getStreamExtractor(url)
            val fetchStartedAtMs = System.currentTimeMillis()
            extractor.fetchPage()
            Timber.d(
                "NewPipe fetchPage took %d ms for %s",
                System.currentTimeMillis() - fetchStartedAtMs,
                videoId
            )

            val audioStreams = extractor.audioStreams
            if (audioStreams.isEmpty()) {
                return Result.failure(IllegalStateException("NewPipe: no audio streams for $videoId"))
            }

            val sortedStreams = audioStreams.sortedByDescending { stream ->
                val isOpus = stream.format?.name?.contains("opus", ignoreCase = true) == true
                val bitrate = stream.averageBitrate.takeIf { it > 0 } ?: 128
                if (isOpus) bitrate + 1000 else bitrate
            }

            for (stream in sortedStreams) {
                val streamUrl = stream.url
                if (!streamUrl.isNullOrBlank() && verifyStreamUrlNot403(streamUrl)) {
                    Timber.d("NewPipe verified stream for $videoId: format=${stream.format?.name}, bitrate=${stream.averageBitrate}")
                    return Result.success(streamUrl)
                }
            }

            Result.failure(IllegalStateException("NewPipe: no verified working audio streams for $videoId"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Save a list of YouTube songs into Room database so they appear in local library/playlists.
     *
     * Inserts the parent artist and album rows together with the songs inside one transaction:
     * `songs.album_id` is NOT NULL with a foreign key to `albums`, so songs without parent rows
     * would violate the constraint (Room runs with PRAGMA foreign_keys = ON) and every save
     * would fail silently.
     */
    suspend fun saveSongsToLibrary(songs: List<Song>): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val entities = songs.map { song ->
                val songIdLong = generateSongIdLong(song.id)
                SongEntity(
                    id = songIdLong,
                    title = song.title,
                    artistId = generateArtistIdLong(song.artist),
                    artistName = song.artist,
                    albumId = generateAlbumIdLong(song.album),
                    albumName = song.album,
                    albumArtist = song.albumArtist ?: song.artist,
                    filePath = song.contentUriString,
                    parentDirectoryPath = YOUTUBE_PARENT_DIRECTORY,
                    contentUriString = song.contentUriString,
                    albumArtUriString = song.albumArtUriString,
                    duration = song.duration,
                    genre = YOUTUBE_GENRE,
                    trackNumber = 0,
                    year = 0,
                    dateAdded = System.currentTimeMillis(),
                    mimeType = "audio/opus",
                    sourceType = SourceType.YOUTUBE
                )
            }
            val artists = songs
                .distinctBy { it.artist }
                .map { song ->
                    ArtistEntity(
                        id = generateArtistIdLong(song.artist),
                        name = song.artist,
                        trackCount = 0
                    )
                }
            val albums = songs
                .distinctBy { it.albumId }
                .map { song ->
                    AlbumEntity(
                        id = generateAlbumIdLong(song.album),
                        title = song.album,
                        artistName = song.artist,
                        artistId = generateArtistIdLong(song.artist),
                        albumArtUriString = song.albumArtUriString,
                        songCount = 0,
                        dateAdded = System.currentTimeMillis(),
                        year = 0,
                        albumArtist = song.albumArtist ?: song.artist
                    )
                }
            musicDao.insertMusicData(entities, albums, artists)
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to save YouTube songs to library")
            Result.failure(e)
        }
    }

    private fun mapStreamInfoToSong(item: StreamInfoItem): Song {
        val videoId = item.url.substringAfter("v=").substringBefore("&")
        val songIdLong = generateSongIdLong(videoId)
        val artistName = item.uploaderName ?: "Unknown Artist"
        val albumName = "YouTube Music"

        return Song(
            id = songIdLong.toString(),
            title = item.name ?: "Unknown Title",
            artist = artistName,
            artistId = generateArtistIdLong(artistName),
            artists = listOf(ArtistRef(id = generateArtistIdLong(artistName), name = artistName, isPrimary = true)),
            album = albumName,
            albumId = generateAlbumIdLong(albumName),
            albumArtist = artistName,
            path = "youtube://$videoId",
            contentUriString = "youtube://$videoId",
            albumArtUriString = item.thumbnails.firstOrNull()?.url,
            duration = item.duration * 1000L,
            genre = YOUTUBE_GENRE,
            mimeType = "audio/opus",
            bitrate = 160,
            sampleRate = 48000,
            sourceType = SongSource.YOUTUBE
        )
    }

    private fun generateSongIdLong(videoId: String): Long = YouTubeIds.songId(videoId)

    private fun generateArtistIdLong(artistName: String): Long = YouTubeIds.artistId(artistName)

    private fun generateAlbumIdLong(albumName: String): Long = YouTubeIds.albumId(albumName)
}

/**
 * A YouTube playlist resolved into the queue shape the player already uses.
 */
/** An audio stream chosen for offline saving, with the container it is expected to arrive in. */
data class YouTubeDownloadStream(
    val url: String,
    val mimeType: String,
    val bitrateKbps: Int
)

data class YouTubePlaylist(
    val id: String,
    val title: String,
    val songs: List<Song>
)

/**
 * A playlist as it appears in search results, before its tracks are fetched.
 */
data class YouTubePlaylistSummary(
    val id: String,
    val title: String,
    val uploader: String?,
    val trackCount: Long?,
    val thumbnailUrl: String?
)

/**
 * Pull a playlist id out of anything a user is likely to paste.
 *
 * Handles `youtube.com/playlist?list=…`, `music.youtube.com/…`, watch links that carry
 * `&list=…` (a song opened inside a playlist), the `VL` prefix InnerTube uses for browse ids,
 * and a bare playlist id. Returns null for anything else, so a plain search still searches.
 */
internal fun extractYouTubePlaylistId(input: String): String? {
    val text = input.trim()
    if (text.isEmpty()) return null

    val fromQuery = text.substringAfter("list=", "").substringBefore('&').substringBefore('#')
    val candidate = (fromQuery.ifBlank { text }).trim().removePrefix("VL")
    if (candidate.isEmpty()) return null
    if (!candidate.matches(YOUTUBE_PLAYLIST_ID)) return null
    return candidate
}

/** Playlist ids are `PL…`, `OLAK5uy_…` (auto-generated album playlists) or `RD…` mixes. */
private val YOUTUBE_PLAYLIST_ID = Regex("^(PL|OLAK5uy_|RD|UU|LL|FL)[A-Za-z0-9_-]{10,60}$")

