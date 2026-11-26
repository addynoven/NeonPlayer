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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YouTubeDownloaderImpl @Inject constructor(
    private val okHttpClient: OkHttpClient
) : Downloader() {

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

        val okHttpResponse = okHttpClient.newCall(requestBuilder.build()).execute()
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
