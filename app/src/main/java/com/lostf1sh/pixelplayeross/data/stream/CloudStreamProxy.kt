package com.lostf1sh.pixelplayeross.data.stream

import android.net.Uri
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import timber.log.Timber
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Abstract base class for local HTTP proxy servers that stream cloud music audio.
 *
 * Subclasses define the route, ID type, validation, allowed hosts, and URL resolution.
 * The base class handles the full Ktor CIO server lifecycle, URL caching, and OkHttp
 * proxying with security checks via [CloudStreamSecurity].
 *
 * @param K The service-specific song identifier type.
 */
abstract class CloudStreamProxy<K : Any>(
    private val okHttpClient: OkHttpClient
) {

    protected abstract val allowedHostSuffixes: Set<String>
    protected abstract val cacheExpirationMs: Long
    protected abstract val proxyTag: String

    /** Route path registered with Ktor, e.g. "/navidrome/{songId}" */
    protected abstract val routePath: String
    /** The parameter name inside the route path, e.g. "songId" */
    protected abstract val routeParamName: String
    /** URI scheme this proxy handles, e.g. "navidrome" or "jellyfin" */
    protected abstract val uriScheme: String
    /** URL path prefix for proxy URLs, e.g. "/navidrome" or "/jellyfin" */
    protected abstract val routePrefix: String

    /** Parse the raw route parameter string into the typed ID, or null if invalid */
    protected abstract fun parseRouteParam(value: String): K?
    /** Validate whether the given ID is acceptable */
    protected abstract fun validateId(id: K): Boolean
    /** Convert the ID to a string for use in URLs */
    protected abstract fun formatIdForUrl(id: K): String
    /** Resolve the actual streaming URL for the given song ID */
    protected abstract suspend fun resolveStreamUrl(id: K): String?

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var actualPort: Int = 0
    private val proxyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startJob: Job? = null

    private val streamingClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .readTimeout(STREAM_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Deadline for fetching one bounded-range chunk, or null to let a chunk run as long as the
     * upstream needs. googlevideo nodes do occasionally stall a single range request for
     * 8-11 s; ExoPlayer gives up on the whole connection after its own 8 s read timeout, so the
     * listener hears a gap and the player re-opens the stream at a new offset. Ending the
     * stalled request early lets [fetchAndRelayChunk] evict that URL and retry on a freshly
     * resolved one. Left null for servers where a slow chunk is normal (a home NAS, a cloud
     * host on mobile data) — cutting those off would only cause churn.
     */
    protected open val chunkCallTimeoutSeconds: Long? = null

    private val chunkClient: OkHttpClient by lazy {
        streamingClient.newBuilder().apply {
            chunkCallTimeoutSeconds?.let { callTimeout(it, TimeUnit.SECONDS) }
        }.build()
    }

    @Volatile
    private var sessionToken: String = ""

    private val urlCache = ConcurrentHashMap<K, CachedUrl>()

    private data class CachedUrl(val url: String, val timestamp: Long, val expirationMs: Long) {
        fun isExpired(): Boolean = System.currentTimeMillis() - timestamp > expirationMs
    }

    private companion object {
        const val STREAM_READ_TIMEOUT_SECONDS = 60L

        /**
         * Window size for bounded-range relay of upstreams that refuse open-ended GETs.
         *
         * googlevideo (rqh=1) and the Invidious worker nodes that proxy it both cap the
         * *span* of a single Range request: 512 KB is answered with 206, anything at or
         * above ~1 MB is answered with 403. Measured against a live worker:
         * bytes=0-524287 -> 206, bytes=0-1048575 -> 403, bytes=0-1999999 -> 403,
         * bytes=1500000-2500000 -> 403, bytes=0- (open ended) -> 403.
         *
         * Keep this comfortably under the smallest observed cap; [MIN_BOUNDED_CHUNK_BYTES]
         * and [MAX_CHUNK_SHRINKS] below let the relay halve the window if a node ever
         * enforces something tighter, instead of failing the whole stream.
         */
        const val BOUNDED_CHUNK_BYTES = 512_000L
        const val MIN_BOUNDED_CHUNK_BYTES = 128_000L
        const val MAX_CHUNK_SHRINKS = 3
    }

    fun isReady(): Boolean = actualPort > 0

    fun startIfNeeded() {
        if (isReady() || startJob?.isActive == true) return
        start()
    }

    suspend fun awaitReady(timeoutMs: Long = 10_000L): Boolean {
        if (isReady()) return true
        val stepMs = 50L
        var elapsed = 0L
        while (elapsed < timeoutMs) {
            if (isReady()) return true
            delay(stepMs)
            elapsed += stepMs
        }
        return false
    }

    suspend fun ensureReady(timeoutMs: Long = 10_000L): Boolean {
        startIfNeeded()
        return awaitReady(timeoutMs)
    }

    /**
     * Local proxy URL for [id], or null when the server is not listening yet or the id is
     * invalid. Callers get a null instead of an empty string so a Uri built from it fails
     * loudly at the call site rather than turning into a silent 404.
     */
    fun getProxyUrl(id: K): String? {
        if (actualPort == 0) return null
        if (!validateId(id)) return null
        return "http://127.0.0.1:$actualPort$routePrefix/${formatIdForUrl(id)}?t=$sessionToken"
    }

    /**
     * Parse a cloud URI (e.g. "navidrome://song-id" or "jellyfin://item-id") and return
     * the local proxy URL. Returns null if the URI doesn't match this proxy's scheme.
     */
    fun resolveUri(uriString: String): String? {
        val uri = Uri.parse(uriString)
        if (uri.scheme != uriScheme) return null
        val rawId = extractIdFromUri(uri) ?: return null
        val id = parseRouteParam(rawId) ?: return null
        if (!validateId(id)) return null
        return getProxyUrl(id)
    }

    fun start() {
        startJob?.cancel()
        startJob = proxyScope.launch {
            try {
                sessionToken = generateSessionToken()
                val createdServer = createServer(0)
                createdServer.start(wait = false)
                server = createdServer
                actualPort = createdServer.engine.resolvedConnectors().first().port
                Timber.d("$proxyTag started on port $actualPort")
            } catch (_: CancellationException) {
                Timber.d("$proxyTag start cancelled")
            } catch (e: Exception) {
                Timber.e(e, "Failed to start $proxyTag")
            }
        }
    }

    fun stop() {
        startJob?.cancel()
        startJob = null
        proxyScope.coroutineContext.cancelChildren()
        server?.stop(1000, 2000)
        server = null
        actualPort = 0
        sessionToken = ""
        urlCache.clear()
        Timber.d("$proxyTag stopped")
    }

    /** Extract the raw ID string from a parsed URI. Override for custom URI layouts. */
    protected open fun extractIdFromUri(uri: Uri): String? = uri.host

    /**
     * Extra headers for the upstream stream request. Lets subclasses send auth tokens as
     * headers instead of baking them into the cached stream URL (where they'd end up in
     * URL caches and server access logs).
     */
    protected open fun upstreamHeaders(id: K): Map<String, String> = emptyMap()

    /**
     * Upstream headers for a specific resolved stream URL. Resolved URLs (e.g. googlevideo)
     * can carry the generating client's identity, so subclasses may tailor headers per URL.
     * Defaults to [upstreamHeaders] for proxies whose upstream has no per-URL identity.
     */
    protected open fun upstreamHeadersForUrl(id: K, streamUrl: String): Map<String, String> =
        upstreamHeaders(id)

    /**
     * Drop a cached stream URL before a retry, e.g. after the upstream returned 403/410
     * for an expired or IP-bound URL. Subclasses evict their own resolver cache; the
     * proxy's URL cache is evicted by the self-heal path itself.
     */
    protected open fun evictResolvedStream(id: K) {}

    private fun generateSessionToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        val hex = "0123456789abcdef"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(hex[v ushr 4]).append(hex[v and 0x0F])
        }
        return sb.toString()
    }

    /** Constant-time check of the per-session token supplied in the request. */
    private fun isAuthorized(provided: String?): Boolean {
        val expected = sessionToken
        if (expected.isEmpty() || provided.isNullOrEmpty()) return false
        return MessageDigest.isEqual(
            provided.toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8)
        )
    }

    protected suspend fun getOrFetchStreamUrl(id: K): String? {
        urlCache[id]?.let { cached ->
            if (!cached.isExpired()) return cached.url
        }
        return resolveStreamUrl(id)?.also { url ->
            urlCache[id] = CachedUrl(url, System.currentTimeMillis(), cacheExpirationMs)
        }
    }

    private fun createServer(port: Int): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> {
        return embeddedServer(CIO, port = port, host = "127.0.0.1") {
            routing {
                get(routePath) {
                    if (!isAuthorized(call.request.queryParameters["t"])) {
                        call.respond(HttpStatusCode.NotFound, "Not found")
                        return@get
                    }
                    val rawParam = call.parameters[routeParamName]
                    val id = rawParam?.let { parseRouteParam(it) }
                    if (id == null || !validateId(id)) {
                        call.respond(HttpStatusCode.BadRequest, "Invalid ID")
                        return@get
                    }

                    try {
                        val rangeValidation = CloudStreamSecurity.validateRangeHeader(
                            call.request.headers["Range"]
                        )
                        if (!rangeValidation.isValid) {
                            call.respond(
                                HttpStatusCode(416, "Range Not Satisfiable"),
                                "Invalid range header"
                            )
                            return@get
                        }

                        val streamUrl = getOrFetchStreamUrl(id)
                        if (streamUrl.isNullOrBlank()) {
                            call.respond(HttpStatusCode.NotFound, "No stream URL available")
                            return@get
                        }
                        if (!CloudStreamSecurity.isSafeRemoteStreamUrl(
                                url = streamUrl,
                                allowedHostSuffixes = allowedHostSuffixes,
                                allowHttpForAllowedHosts = true
                            )
                        ) {
                            call.respond(HttpStatusCode.BadGateway, "Rejected upstream stream URL")
                            return@get
                        }

                        // Some upstreams (googlevideo rqh=1 URLs) only serve bounded ranges:
                        // every unbounded GET the player makes would 403. Relay those in
                        // bounded chunks instead of proxying the player's request verbatim.
                        if (isBoundedOnlyUrl(streamUrl)) {
                            serveBoundedRangeRelay(call, id, streamUrl, rangeValidation.normalizedHeader)
                            return@get
                        }

                        val requestBuilder = Request.Builder().url(streamUrl)
                        rangeValidation.normalizedHeader?.let {
                            requestBuilder.header("Range", it)
                        }
                        upstreamHeadersForUrl(id, streamUrl).forEach { (name, value) ->
                            requestBuilder.header(name, value)
                        }

                        var response: Response? = withContext(Dispatchers.IO) {
                            streamingClient.newCall(requestBuilder.build()).execute()
                        }

                        // Self-heal: expired or IP-bound upstream URLs (googlevideo does this
                        // after ~6h) die with 403/410. Evict both caches, resolve a fresh URL
                        // and retry once before surfacing the failure to the player.
                        if (response?.code == 403 || response?.code == 410) {
                            response?.close()
                            Timber.w("Upstream %s for %s (videoId %s) — evicting caches and re-resolving once", response?.code, proxyTag, id)
                            evictResolvedStream(id)
                            urlCache.remove(id)
                            val retryUrl = getOrFetchStreamUrl(id)
                            response = retryUrl
                                ?.takeIf { it != streamUrl }
                                ?.takeIf {
                                    CloudStreamSecurity.isSafeRemoteStreamUrl(
                                        url = it,
                                        allowedHostSuffixes = allowedHostSuffixes,
                                        allowHttpForAllowedHosts = true
                                    )
                                }
                                ?.let { url ->
                                    val retryBuilder = Request.Builder().url(url)
                                    rangeValidation.normalizedHeader?.let {
                                        retryBuilder.header("Range", it)
                                    }
                                    upstreamHeadersForUrl(id, url).forEach { (name, value) ->
                                        retryBuilder.header(name, value)
                                    }
                                    withContext(Dispatchers.IO) {
                                        streamingClient.newCall(retryBuilder.build()).execute()
                                    }
                                }
                        }

                        if (response == null) {
                            call.respond(HttpStatusCode.BadGateway, "Upstream stream request failed")
                            return@get
                        }

                        response.use { upstream ->
                            if (upstream.code != 200 && upstream.code != 206) {
                                call.respond(
                                    CloudStreamSecurity.mapUpstreamStatusToProxyStatus(upstream.code),
                                    "Upstream stream request failed"
                                )
                                return@get
                            }

                            val body = upstream.body
                            val contentTypeHeader = upstream.header("Content-Type")

                            if (!CloudStreamSecurity.isSupportedAudioContentType(contentTypeHeader)) {
                                call.respond(
                                    HttpStatusCode.BadGateway,
                                    "Unsupported stream content type"
                                )
                                return@get
                            }

                            val contentLength = upstream.header("Content-Length")
                            if (!CloudStreamSecurity.isAcceptableContentLength(contentLength)) {
                                call.respond(
                                    HttpStatusCode(413, "Payload Too Large"),
                                    "Stream content too large"
                                )
                                return@get
                            }

                            val contentRange = upstream.header("Content-Range")
                            val responseContentType = contentTypeHeader
                                ?.substringBefore(';')
                                ?.trim()
                                ?.let { raw ->
                                    runCatching { ContentType.parse(raw) }.getOrNull()
                                }
                                ?: ContentType.Audio.Any

                            Timber.d(
                                "%s serving %s: upstream=%s upstreamLen=%s playerRange=%s",
                                proxyTag,
                                id,
                                upstream.code,
                                contentLength,
                                rangeValidation.normalizedHeader ?: "none"
                            )
                            respondWithStream(
                                call = call,
                                status = if (upstream.code == 206) {
                                    HttpStatusCode.PartialContent
                                } else {
                                    HttpStatusCode.OK
                                },
                                contentType = responseContentType,
                                contentLength = contentLength?.toLongOrNull()
                                    ?: body.contentLength().takeIf { it >= 0 },
                                contentRange = contentRange
                            ) {
                                withContext(Dispatchers.IO) {
                                    body.byteStream().use { input ->
                                        val buffer = ByteArray(64 * 1024)
                                        var bytesRead: Int
                                        while (input.read(buffer)
                                                .also { bytesRead = it } != -1
                                        ) {
                                            writeFully(buffer, 0, bytesRead)
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        val msg = e.toString()
                        if (msg.contains("ChannelWriteException") ||
                            msg.contains("ClosedChannelException") ||
                            msg.contains("Broken pipe") ||
                            msg.contains("JobCancellationException")
                        ) {
                        } else {
                            Timber.w(e, "$proxyTag stream failed")
                        }
                    }
                }
            }
        }
    }

    /**
     * Responds with a streamed body whose byte length is known up front.
     *
     * Ktor only sends `Content-Length` — and only skips chunked transfer encoding — when
     * the content object declares its length. Setting the header by hand while writing
     * through `respondBytesWriter` produced a response carrying BOTH `Content-Length`
     * and `Transfer-Encoding: chunked`, and ExoPlayer/OkHttp then hung with headers
     * received and no body (reproduced with a raw socket: 139 bytes for the whole
     * response, then an 8 s read timeout on the player side).
     */
    private suspend fun respondWithStream(
        call: ApplicationCall,
        status: HttpStatusCode,
        contentType: ContentType,
        contentLength: Long?,
        contentRange: String?,
        write: suspend ByteWriteChannel.() -> Unit
    ) {
        call.respond(object : OutgoingContent.WriteChannelContent() {
            override val status: HttpStatusCode = status
            override val contentType: ContentType = contentType
            override val contentLength: Long? = contentLength
            override val headers: Headers = Headers.build {
                append(HttpHeaders.AcceptRanges, "bytes")
                contentRange?.let { append(HttpHeaders.ContentRange, it) }
            }

            override suspend fun writeTo(channel: ByteWriteChannel) {
                channel.write()
            }
        })
    }

    /**
     * Whether [streamUrl] is restricted by the upstream to bounded-range requests
     * only. Subclasses whose upstreams emit such URLs (googlevideo rqh=1) must
     * override this so [serveBoundedRangeRelay] kicks in; those URLs 403 on every
     * unbounded GET the player makes (verified: bytes=0-1 -> 206, bytes=0- and
     * no-Range -> 403).
     */
    protected open fun isBoundedOnlyUrl(streamUrl: String): Boolean = false

    /**
     * Serves [id] by relaying an rqh=1-style upstream URL in bounded chunks.
     *
     * The player's request is answered with a synthetic full/partial response
     * covering the remaining byte span; the upstream is then fetched in
     * [BOUNDED_CHUNK_BYTES]-sized bounded ranges and stitched into one stream.
     */
    private suspend fun serveBoundedRangeRelay(
        call: ApplicationCall,
        id: K,
        streamUrl: String,
        playerRange: String?
    ) {
        val totalLookupStartedAtMs = System.currentTimeMillis()
        val total = extractUrlParam(streamUrl, "clen")?.toLongOrNull()
            ?: fetchTotalLength(id, streamUrl)
        if (total == null || total <= 0L) {
            call.respond(HttpStatusCode.BadGateway, "Cannot determine upstream length")
            return
        }
        Timber.d(
            "%s bounded relay for %s: total=%s found in %d ms, playerRange=%s",
            proxyTag, id, total, System.currentTimeMillis() - totalLookupStartedAtMs,
            playerRange ?: "none"
        )

        // Player asked for bytes=N-M or bytes=N- (open ended). Default to the full file.
        val (start, playerEnd) = parsePlayerRange(playerRange, total)
        val end = playerEnd

        try {
            // Content-Type comes from the URL's mime= param (googlevideo embeds it).
            val contentType = extractUrlParam(streamUrl, "mime")?.let { raw ->
                runCatching { ContentType.parse(raw) }.getOrNull()
            } ?: ContentType.Audio.Any

            respondWithStream(
                call = call,
                status = if (playerRange != null) {
                    HttpStatusCode.PartialContent
                } else {
                    HttpStatusCode.OK
                },
                contentType = contentType,
                contentLength = end - start + 1,
                contentRange = if (playerRange != null) "bytes $start-$end/$total" else null
            ) {
                var pos = start
                var chunkSize = BOUNDED_CHUNK_BYTES
                var shrinks = 0
                while (pos <= end) {
                    val chunkEnd = minOf(pos + chunkSize - 1, end)
                    val chunkStartedAtMs = System.currentTimeMillis()
                    val chunkServed = fetchAndRelayChunk(id, pos, chunkEnd, this)
                    // Startup latency lives here: each bounded window is a separate upstream
                    // request, so a slow node shows up as a few seconds per 512 KB chunk.
                    Timber.d(
                        "%s chunk %s-%s %s in %d ms",
                        proxyTag, pos, chunkEnd,
                        if (chunkServed) "served" else "failed",
                        System.currentTimeMillis() - chunkStartedAtMs
                    )
                    if (chunkServed) {
                        pos = chunkEnd + 1
                        continue
                    }
                    // The upstream refused this window (its per-request range cap can be
                    // tighter than ours). Halve the window and retry the same offset
                    // before giving up on the stream.
                    if (chunkSize > MIN_BOUNDED_CHUNK_BYTES && shrinks < MAX_CHUNK_SHRINKS) {
                        chunkSize = maxOf(chunkSize / 2, MIN_BOUNDED_CHUNK_BYTES)
                        shrinks++
                        Timber.w(
                            "%s chunk %s-%s refused upstream; retrying with a %s-byte window",
                            proxyTag, pos, chunkEnd, chunkSize
                        )
                        continue
                    }
                    // Upstream chunk failed even after the self-heal retry; abort. The
                    // player will reconnect and the next request re-resolves.
                    break
                }
            }
        } catch (e: Exception) {
            val msg = e.toString()
            if (msg.contains("ChannelWriteException") ||
                msg.contains("ClosedChannelException") ||
                msg.contains("Broken pipe") ||
                msg.contains("JobCancellationException") ||
                msg.contains("CancellationException")
            ) {
            } else {
                Timber.w(e, "$proxyTag bounded relay failed (videoId $id)")
            }
        }
    }

    /**
     * Fetches upstream bytes [start]..[end] as one bounded range and writes them
     * into the response writer. On 403/410 evicts the caches, re-resolves once
     * and retries the chunk.
     */
    private suspend fun fetchAndRelayChunk(
        id: K,
        start: Long,
        end: Long,
        writer: ByteWriteChannel
    ): Boolean {
        var url = getOrFetchStreamUrl(id) ?: return false
        // Two tries under the per-chunk deadline, then one last try with the plain client:
        // a stalled request is worth abandoning early, but a slow chunk that does arrive
        // is always better than dropping the stream and skipping the track.
        val clients = if (chunkCallTimeoutSeconds == null) {
            listOf(streamingClient, streamingClient)
        } else {
            listOf(chunkClient, chunkClient, streamingClient)
        }
        for (client in clients) {
            val request = Request.Builder().url(url)
                .header("Range", "bytes=$start-$end")
            upstreamHeadersForUrl(id, url).forEach { (name, value) ->
                request.header(name, value)
            }
            // A slow or flapping upstream node surfaces as an IOException here (timeouts
            // were killing playback mid-song); treat it like a 403: evict, re-resolve,
            // retry once. Returning false lets the caller shrink the window as well.
            val response = try {
                withContext(Dispatchers.IO) {
                    client.newCall(request.build()).execute()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val stalledFor = chunkCallTimeoutSeconds
                val stalled = e is java.io.InterruptedIOException && stalledFor != null
                Timber.w(
                    e,
                    "%s upstream chunk %s-%s failed before response" + (if (stalled) " (stalled past " + stalledFor + "s)" else ""),
                    proxyTag, start, end
                )
                null
            }
            if (response == null) {
                evictResolvedStream(id)
                urlCache.remove(id)
                url = getOrFetchStreamUrl(id) ?: return false
                continue
            }
            response.use { upstream ->
                when (upstream.code) {
                    200, 206 -> {
                        // Cap the copy: a 200 (full body) response would over-relay.
                        val expected = end - start + 1
                        upstream.body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            var remaining = if (upstream.code == 200) expected else Long.MAX_VALUE
                            withContext(Dispatchers.IO) {
                                var bytesRead = 0
                                while (remaining > 0) {
                                    val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                                    bytesRead = input.read(buffer, 0, toRead)
                                    if (bytesRead == -1) break
                                    writer.writeFully(buffer, 0, bytesRead)
                                    remaining -= bytesRead
                                }
                            }
                        }
                        return true
                    }
                    403, 410 -> {
                        Timber.w(
                            "Upstream %s for %s (videoId %s) chunk %s-%s — evicting and re-resolving once",
                            upstream.code, proxyTag, id, start, end
                        )
                        evictResolvedStream(id)
                        urlCache.remove(id)
                        url = getOrFetchStreamUrl(id) ?: return false
                    }
                    else -> {
                        Timber.w(
                            "%s upstream chunk %s: HTTP %s",
                            proxyTag, start, upstream.code
                        )
                        return false
                    }
                }
            }
        }
        return false
    }

    /** Returns the URL's `clen=` via a bounded probe when the param is missing. */
    private suspend fun fetchTotalLength(id: K, streamUrl: String): Long? {
        val request = Request.Builder().url(streamUrl)
            .header("Range", "bytes=0-0")
        upstreamHeadersForUrl(id, streamUrl).forEach { (name, value) ->
            request.header(name, value)
        }
        val response = withContext(Dispatchers.IO) {
            streamingClient.newCall(request.build()).execute()
        }
        response.use { upstream ->
            val contentRange = upstream.header("Content-Range") ?: return null
            return Regex("bytes 0-0/(\\d+)").find(contentRange)?.groupValues?.get(1)?.toLongOrNull()
        }
    }

    private fun extractUrlParam(url: String, key: String): String? {
        val match = Regex("[?&]$key=([^&]+)").find(url) ?: return null
        return runCatching {
            java.net.URLDecoder.decode(match.groupValues[1], "UTF-8")
        }.getOrDefault(match.groupValues[1])
    }

    private fun parsePlayerRange(playerRange: String?, total: Long): Pair<Long, Long> {
        if (playerRange.isNullOrBlank()) return 0L to (total - 1)
        val match = Regex("bytes=(\\d+)-(\\d*)").find(playerRange) ?: return 0L to (total - 1)
        val start = match.groupValues[1].toLongOrNull()?.coerceIn(0L, total - 1) ?: 0L
        val end = match.groupValues[2].takeIf { it.isNotBlank() }
            ?.toLongOrNull()?.coerceAtMost(total - 1) ?: (total - 1)
        return start to end
    }
}
