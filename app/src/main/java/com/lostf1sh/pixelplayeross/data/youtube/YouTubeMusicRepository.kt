package com.lostf1sh.pixelplayeross.data.youtube

import android.content.Context
import com.lostf1sh.pixelplayeross.data.database.AlbumEntity
import com.lostf1sh.pixelplayeross.data.database.ArtistEntity
import com.lostf1sh.pixelplayeross.data.database.MusicDao
import com.lostf1sh.pixelplayeross.data.database.SongEntity
import com.lostf1sh.pixelplayeross.data.database.SourceType
import com.lostf1sh.pixelplayeross.data.model.ArtistRef
import com.lostf1sh.pixelplayeross.data.model.Song
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import timber.log.Timber
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

    private val resolvedStreamCache = java.util.concurrent.ConcurrentHashMap<String, CachedStreamUrl>()

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
     * Resolve the direct audio stream URL for a given YouTube [videoId].
     *
     * Strategy: race all InnerTube clients in parallel (first success wins), then race every
     * fallback service in parallel. Resolved URLs are cached for [STREAM_CACHE_TTL_MS] so
     * replays and queue skips don't pay the resolver ladder again.
     */
    suspend fun getAudioStreamUrl(videoId: String): Result<String> = withContext(Dispatchers.IO) {
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
        val invidiousInstances = listOf(
            "https://yt.chocolatemoo53.com/api/v1/videos/$videoId?local=true",
            "https://inv.nadeko.net/api/v1/videos/$videoId?local=true",
            "https://invidious.nerdvpn.de/api/v1/videos/$videoId?local=true",
            "https://invidious.tiekoetter.com/api/v1/videos/$videoId?local=true"
        )
        for (apiUrl in invidiousInstances) {
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
        val head = probeStreamRange(streamUrl, "bytes=0-99999")
        if (head !in 200..206) {
            Timber.d("Stream probe rejected %s: head HTTP %s", describeStreamHost(streamUrl), head)
            return false
        }
        val pastFirstMegabyte = probeStreamRange(streamUrl, "bytes=1048576-1099999")
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

    /** Runs one bounded GET probe; returns the HTTP code, or 0 when the request failed. */
    private fun probeStreamRange(streamUrl: String, range: String): Int {
        return try {
            val request = Request.Builder()
                .url(streamUrl)
                .header("Range", range)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()
            okHttpClient.newCall(request).execute().use { response -> response.code }
        } catch (e: Exception) {
            Timber.w(e, "Stream probe failed for %s (%s)", describeStreamHost(streamUrl), range)
            0
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
            extractor.fetchPage()

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
            sampleRate = 48000
        )
    }

    private fun generateSongIdLong(videoId: String): Long {
        return YOUTUBE_SONG_ID_OFFSET + (videoId.hashCode().toLong().absoluteValue % 1_000_000_000_000L)
    }

    private fun generateArtistIdLong(artistName: String): Long {
        return 7_100_000_000_000L + (artistName.hashCode().toLong().absoluteValue % 1_000_000_000_000L)
    }

    private fun generateAlbumIdLong(albumName: String): Long {
        return 7_200_000_000_000L + (albumName.hashCode().toLong().absoluteValue % 1_000_000_000_000L)
    }
}
