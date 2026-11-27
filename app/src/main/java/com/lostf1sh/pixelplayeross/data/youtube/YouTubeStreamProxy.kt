package com.lostf1sh.pixelplayeross.data.youtube

import android.net.Uri
import com.lostf1sh.pixelplayeross.data.stream.CloudStreamProxy
import com.lostf1sh.pixelplayeross.data.stream.CloudStreamSecurity
import okhttp3.OkHttpClient
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YouTubeStreamProxy @Inject constructor(
    private val repository: YouTubeMusicRepository,
    okHttpClient: OkHttpClient
) : CloudStreamProxy<String>(okHttpClient) {

    override val allowedHostSuffixes = setOf(
        "youtube.com",
        "googlevideo.com",
        "ytimg.com",
        "youtube-nocookie.com",
        // Invidious instances serving local=true proxied streams (their URLs point at
        // the instance host instead of googlevideo; see getAudioStreamViaInvidious).
        // Suffix match covers chocolatemoo's worker nodes (yt-comp1/2/5...).
        "chocolatemoo53.com",
        "inv.nadeko.net",
        "invidious.nerdvpn.de",
        "invidious.tiekoetter.com"
    )
    override val cacheExpirationMs = 3L * 60 * 60 * 1000 // 3 hours
    override val proxyTag = "YouTubeStreamProxy"

    /**
     * googlevideo sometimes stalls one range request for 8-11 s while the rest of the file
     * comes down in ~150 ms chunks. ExoPlayer abandons the connection after its own 8 s read
     * timeout, so the stall becomes an audible gap plus a reconnect; giving the chunk a
     * deadline instead lets the relay retry it on a freshly resolved URL.
     */
    override val chunkCallTimeoutSeconds: Long? = 4L
    override val routePath = "/youtube/{videoId}"
    override val routeParamName = "videoId"
    override val uriScheme = "youtube"
    override val routePrefix = "/youtube"

    override fun parseRouteParam(value: String): String? =
        value.takeIf { it.isNotBlank() }

    override fun validateId(id: String): Boolean =
        CloudStreamSecurity.validateYouTubeVideoId(id)

    override fun formatIdForUrl(id: String): String = id

    /**
     * Always relay YouTube streams through bounded upstream ranges.
     *
     * Two independent reasons:
     *
     * 1. Every googlevideo URL we can obtain is `rqh=1`, and the *unbounded* form of
     *    that request (no Range, or `bytes=0-`) is exactly the shape that misbehaves:
     *    the response comes back 200 with a valid `Content-Length`, then the body never
     *    arrives and the socket sits idle until the read timeout fires. Measured on the
     *    emulator: a plain GET through this proxy delivered 139 bytes of headers and then
     *    nothing for 40 s, while the same URL answered `Range: bytes=0-99999` in full.
     * 2. Bounded windows also survive the per-URL byte ceilings that YouTube applies to
     *    PoToken-less clients, so a capped URL degrades into smaller windows instead of
     *    a dead stream.
     *
     * The relay always issues an explicit `bytes=` range upstream, so playback never
     * depends on any of the open-ended behaviours above.
     */
    override fun isBoundedOnlyUrl(streamUrl: String): Boolean = true

    override suspend fun resolveStreamUrl(id: String): String? =
        repository.getAudioStreamUrl(id).getOrNull()

    override fun evictResolvedStream(id: String) {
        repository.evictStreamUrl(id)
    }

    // NOTE on upstream headers: request headers (UA/Referer) are NOT what googlevideo
    // 403s on — the deciding factor is the Range form: rqh=1 URLs answer bounded
    // ranges (bytes=0-1 -> 206) but 403 every unbounded GET (bytes=0-, no Range),
    // verified empirically. Those URLs are relayed in bounded chunks instead
    // (isBoundedOnlyUrl above), which is why the generic desktop-Chrome UA +
    // youtube.com Referer stay safe here.

    override fun upstreamHeaders(id: String): Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
        "Referer" to "https://www.youtube.com/watch?v=$id"
    )

    override fun extractIdFromUri(uri: Uri): String? {
        val host = uri.host
        if (host != null && CloudStreamSecurity.validateYouTubeVideoId(host)) return host
        
        // Handle URIs like youtube:///videoId or youtube:videoId
        val pathId = uri.path?.removePrefix("/")
        if (pathId != null && CloudStreamSecurity.validateYouTubeVideoId(pathId)) return pathId
        
        val ssp = uri.schemeSpecificPart?.removePrefix("//")?.removePrefix("/")
        if (ssp != null && CloudStreamSecurity.validateYouTubeVideoId(ssp)) return ssp
        
        return null
    }

    fun resolveYouTubeUri(uriString: String): String? = resolveUri(uriString)

    suspend fun warmUpStreamUrl(uriString: String) {
        val uri = Uri.parse(uriString)
        if (uri.scheme != "youtube") return
        val videoId = extractIdFromUri(uri) ?: return
        if (!CloudStreamSecurity.validateYouTubeVideoId(videoId)) return
        try {
            getOrFetchStreamUrl(videoId)
        } catch (e: Exception) {
            Timber.w(e, "warmUpStreamUrl failed for YouTube videoId: $videoId")
        }
    }
}
