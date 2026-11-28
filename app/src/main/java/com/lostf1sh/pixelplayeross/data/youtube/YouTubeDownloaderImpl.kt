package com.lostf1sh.pixelplayeross.data.youtube

import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as ExtractorRequest
import org.schabi.newpipe.extractor.downloader.Response as ExtractorResponse
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YouTubeDownloaderImpl @Inject constructor(
    okHttpClient: OkHttpClient
) : Downloader() {

    /**
     * The extractor only ever reaches YouTube through this client, and the shared app client has
     * no call timeout: a stalled InnerTube or googlevideo socket could hold a stream resolution
     * open for minutes while the player sat at zero. One extractor request now has to finish
     * inside [EXTRACTOR_CALL_TIMEOUT_SECONDS], and a phase that stalls that long falls through to
     * the next strategy instead of parking the tap.
     */
    private val client = okHttpClient.newBuilder()
        .connectTimeout(EXTRACTOR_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(EXTRACTOR_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(EXTRACTOR_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(EXTRACTOR_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private companion object {
        const val EXTRACTOR_CONNECT_TIMEOUT_SECONDS = 8L
        const val EXTRACTOR_READ_TIMEOUT_SECONDS = 10L
        const val EXTRACTOR_CALL_TIMEOUT_SECONDS = 15L
    }

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: ExtractorRequest): ExtractorResponse {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headersMap = request.headers()
        val dataToSend = request.dataToSend()

        val headersBuilder = Headers.Builder()
        for ((key, values) in headersMap) {
            for (value in values) {
                headersBuilder.add(key, value)
            }
        }
        
        if (headersBuilder.get("User-Agent") != null) {
            timber.log.Timber.v("NewPipe provided UA: ${headersBuilder.get("User-Agent")}")
        }

        // Ensure YouTube requests have a valid User-Agent if NewPipe didn't provide one.
        // Some InnerTube API requests require a standard browser agent to avoid 403/ContentNotAvailable.
        if (headersBuilder.get("User-Agent").isNullOrBlank()) {
            headersBuilder.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
        }
        
        // Also ensure Accept-Language is present as it can affect extraction
        if (headersBuilder.get("Accept-Language").isNullOrBlank()) {
            headersBuilder.set("Accept-Language", "en-US,en;q=0.9")
        }

        val requestBuilder = Request.Builder()
            .url(url)
            .headers(headersBuilder.build())

        when (httpMethod) {
            "GET" -> requestBuilder.get()
            "POST" -> {
                val bodyBytes = dataToSend ?: ByteArray(0)
                requestBuilder.post(bodyBytes.toRequestBody(null))
            }
            "HEAD" -> requestBuilder.head()
            else -> requestBuilder.method(httpMethod, null)
        }

        val okHttpResponse = client.newCall(requestBuilder.build()).execute()
        val responseBody = okHttpResponse.body.string()

        val responseCode = okHttpResponse.code
        val responseMessage = okHttpResponse.message

        val responseHeadersMap = mutableMapOf<String, List<String>>()
        for (name in okHttpResponse.headers.names()) {
            responseHeadersMap[name] = okHttpResponse.headers.values(name)
        }

        return ExtractorResponse(responseCode, responseMessage, responseHeadersMap, responseBody, url)
    }

}
