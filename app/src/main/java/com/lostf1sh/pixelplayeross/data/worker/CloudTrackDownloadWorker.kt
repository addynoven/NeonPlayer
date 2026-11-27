package com.lostf1sh.pixelplayeross.data.worker

import android.content.Context
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.database.OfflineTrackDao
import com.lostf1sh.pixelplayeross.data.database.OfflineTrackEntity
import com.lostf1sh.pixelplayeross.data.jellyfin.JellyfinRepository
import com.lostf1sh.pixelplayeross.data.navidrome.NavidromeRepository
import com.lostf1sh.pixelplayeross.data.navidrome.NavidromeSongUri
import com.lostf1sh.pixelplayeross.data.offline.CloudDownloadExports
import com.lostf1sh.pixelplayeross.data.offline.CloudDownloadNotifications
import com.lostf1sh.pixelplayeross.data.offline.CloudExportResult
import com.lostf1sh.pixelplayeross.data.offline.CloudOfflineRepository
import com.lostf1sh.pixelplayeross.data.offline.OfflineDownloadStatus
import com.lostf1sh.pixelplayeross.data.stream.CloudStreamSecurity
import com.lostf1sh.pixelplayeross.data.youtube.YouTubeMusicRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import timber.log.Timber
import kotlin.coroutines.coroutineContext

@HiltWorker
class CloudTrackDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val dao: OfflineTrackDao,
    private val navidromeRepository: NavidromeRepository,
    private val jellyfinRepository: JellyfinRepository,
    private val youTubeMusicRepository: YouTubeMusicRepository,
    private val exports: CloudDownloadExports,
    baseOkHttpClient: OkHttpClient
) : CoroutineWorker(appContext, workerParams) {
    private val client = baseOkHttpClient.newBuilder()
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val downloadId = inputData.getString(KEY_DOWNLOAD_ID)
            ?: return@withContext Result.failure()
        val attemptId = inputData.getString(KEY_ATTEMPT_ID)
            ?: return@withContext Result.failure()
        val sourceUri = inputData.getString(KEY_SOURCE_URI)
            ?: return@withContext Result.failure()
        val entity = dao.getByDownloadId(downloadId)
            ?: return@withContext Result.success()
        if (entity.attemptId != attemptId || entity.sourceUri != sourceUri) {
            return@withContext Result.success()
        }
        if (entity.state == OfflineDownloadStatus.COMPLETE.storageValue) {
            entity.localPath?.let(::File)?.takeIf { it.isFile && it.length() > 0L }?.let { file ->
                finishExport(entity, file, resumeInterruptedCopy = true)
            }
            return@withContext Result.success()
        }

        val now = System.currentTimeMillis()
        if (dao.updateState(
            downloadId = downloadId,
            attemptId = attemptId,
            state = OfflineDownloadStatus.DOWNLOADING.storageValue,
            bytesDownloaded = 0L,
            totalBytes = null,
            localPath = null,
            errorMessage = null,
            updatedAt = now
        ) == 0) return@withContext Result.success()

        val tempFile = CloudOfflineRepository.downloadDirectory(applicationContext)
            .resolve("${CloudOfflineRepository.attemptFileStem(downloadId, attemptId)}.part")
        tempFile.delete()
        var finalizedFile: File? = null
        var completedFile: File? = null

        val result = try {
            val source = resolveSource(sourceUri)
            val fetched = if (source.ranged) {
                downloadRanged(source, tempFile, downloadId, attemptId, entity.mimeType)
            } else {
                downloadDirect(source, tempFile, downloadId, attemptId, entity.mimeType)
            }

            coroutineContext.ensureActive()
            if (!dao.isCurrentAttempt(downloadId, attemptId)) {
                throw StaleDownloadAttemptException()
            }
            val finalFile = fetched.file
            finalizedFile = finalFile
            coroutineContext.ensureActive()
            withContext(NonCancellable) {
                val completed = dao.updateState(
                    downloadId = downloadId,
                    attemptId = attemptId,
                    state = OfflineDownloadStatus.COMPLETE.storageValue,
                    bytesDownloaded = fetched.bytes,
                    totalBytes = fetched.totalBytes ?: fetched.bytes,
                    localPath = finalFile.absolutePath,
                    errorMessage = null,
                    updatedAt = System.currentTimeMillis()
                )
                if (completed == 0) finalFile.delete() else completedFile = finalFile
                finalizedFile = null
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            finalizedFile?.delete()
            throw cancelled
        } catch (_: StaleDownloadAttemptException) {
            tempFile.delete()
            finalizedFile?.delete()
            Result.success()
        } catch (error: Throwable) {
            tempFile.delete()
            finalizedFile?.delete()
            val shouldRetry = runAttemptCount < MAX_RETRIES &&
                (error is IOException || (error is DownloadHttpException && error.code >= 500))
            Timber.tag(TAG).w(error, "Cloud track download failed for %s", sourceUri)
            val updated = dao.updateState(
                downloadId = downloadId,
                attemptId = attemptId,
                state = if (shouldRetry) {
                    OfflineDownloadStatus.QUEUED.storageValue
                } else {
                    OfflineDownloadStatus.FAILED.storageValue
                },
                bytesDownloaded = 0L,
                totalBytes = null,
                localPath = null,
                errorMessage = error.message ?: error.javaClass.simpleName,
                updatedAt = System.currentTimeMillis()
            )
            when {
                updated == 0 -> Result.success()
                shouldRetry -> Result.retry()
                else -> {
                    CloudDownloadNotifications.show(
                        applicationContext, entity.title, R.string.cloud_downloads_failed_fallback,
                        failed = true
                    )
                    Result.failure(workDataOf(KEY_ERROR to (error.message ?: "Download failed")))
                }
            }
        }
        completedFile?.let { file -> finishExport(entity, file, resumeInterruptedCopy = false) }
        result
    }

    private data class FetchedTrack(val file: File, val bytes: Long, val totalBytes: Long?)

    /**
     * One open-ended GET, for servers that serve the whole file that way. Safe for Navidrome and
     * Jellyfin; YouTube goes through [downloadRanged] instead.
     */
    private suspend fun downloadDirect(
        source: DownloadSource,
        tempFile: File,
        downloadId: String,
        attemptId: String,
        mimeType: String?
    ): FetchedTrack {
        val requestBuilder = Request.Builder().url(source.url)
        source.headers.forEach { (name, value) -> requestBuilder.header(name, value) }

        return client.newCall(requestBuilder.get().build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw DownloadHttpException(response.code)
            }
            if (!CloudStreamSecurity.isSupportedAudioContentType(response.header("Content-Type"))) {
                throw IOException("Server returned a non-audio response")
            }
            if (!CloudStreamSecurity.isAcceptableContentLength(response.header("Content-Length"))) {
                throw IOException("Audio file is too large")
            }

            val body = response.body
            val total = body.contentLength().takeIf { it >= 0L }
            val finalFile = CloudOfflineRepository.downloadDirectory(applicationContext).resolve(
                "${CloudOfflineRepository.attemptFileStem(downloadId, attemptId)}." +
                    extensionFor(response.header("Content-Type"), mimeType)
            )
            var copied = 0L
            var lastPublished = 0L

            body.byteStream().use { input ->
                tempFile.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        if (copied > CloudStreamSecurity.MAX_STREAM_CONTENT_LENGTH_BYTES) {
                            throw IOException("Audio file is too large")
                        }
                        if (copied - lastPublished >= PROGRESS_STEP_BYTES) {
                            publishProgress(downloadId, attemptId, copied, total)
                            lastPublished = copied
                        }
                    }
                }
            }

            if (copied <= 0L) throw IOException("Downloaded file is empty")
            if (total != null && copied != total) {
                throw IOException("Download ended early ($copied/$total bytes)")
            }
            renameIntoPlace(tempFile, finalFile)
            FetchedTrack(finalFile, copied, total)
        }
    }

    /**
     * YouTube audio arrives from googlevideo, which refuses an open-ended GET on most URLs and
     * only answers bounded byte ranges. The track is therefore pulled window by window and the
     * windows are appended to the same file, which produces the identical bytes in the end.
     */
    private suspend fun downloadRanged(
        source: DownloadSource,
        tempFile: File,
        downloadId: String,
        attemptId: String,
        mimeType: String?
    ): FetchedTrack {
        tempFile.delete()
        var copied = 0L
        var totalBytes: Long? = null
        var extension: String? = null
        var lastPublished = 0L

        tempFile.outputStream().buffered().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
            while (true) {
                coroutineContext.ensureActive()
                val requestBuilder = Request.Builder().url(source.url)
                source.headers.forEach { (name, value) -> requestBuilder.header(name, value) }
                requestBuilder.header(
                    "Range",
                    "bytes=$copied-${copied + YOUTUBE_RANGE_WINDOW_BYTES - 1}"
                )
                var windowBytes = 0L
                var finished = false

                client.newCall(requestBuilder.get().build()).execute().use { response ->
                    when {
                        // Asked past the end of the file: the previous window already completed it.
                        response.code == 416 -> finished = true
                        !response.isSuccessful -> throw DownloadHttpException(response.code)
                        else -> {
                            if (!CloudStreamSecurity.isSupportedAudioContentType(
                                    response.header("Content-Type")
                                )
                            ) {
                                throw IOException("Server returned a non-audio response")
                            }
                            if (!CloudStreamSecurity.isAcceptableContentLength(
                                    response.header("Content-Length")
                                )
                            ) {
                                throw IOException("Audio file is too large")
                            }
                            if (extension == null) {
                                extension = extensionFor(response.header("Content-Type"), mimeType)
                            }
                            totalBytesFromContentRange(response.header("Content-Range"))
                                ?.let { size -> totalBytes = size }

                            response.body.byteStream().use { input ->
                                while (true) {
                                    coroutineContext.ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                    copied += count
                                    windowBytes += count
                                    if (copied > CloudStreamSecurity.MAX_STREAM_CONTENT_LENGTH_BYTES) {
                                        throw IOException("Audio file is too large")
                                    }
                                    if (copied - lastPublished >= PROGRESS_STEP_BYTES) {
                                        publishProgress(downloadId, attemptId, copied, totalBytes)
                                        lastPublished = copied
                                    }
                                }
                            }
                        }
                    }
                }

                if (finished || windowBytes == 0L) break
                if (windowBytes < YOUTUBE_RANGE_WINDOW_BYTES) break
                val knownTotal = totalBytes
                if (knownTotal != null && copied >= knownTotal) break
            }
        }

        if (copied <= 0L) throw IOException("Downloaded file is empty")
        val knownTotal = totalBytes
        if (knownTotal != null && copied < knownTotal) {
            throw IOException("Download ended early ($copied/$knownTotal bytes)")
        }
        val finalFile = CloudOfflineRepository.downloadDirectory(applicationContext).resolve(
            "${CloudOfflineRepository.attemptFileStem(downloadId, attemptId)}.${extension ?: "webm"}"
        )
        renameIntoPlace(tempFile, finalFile)
        return FetchedTrack(finalFile, copied, knownTotal ?: copied)
    }

    private fun renameIntoPlace(tempFile: File, finalFile: File) {
        finalFile.delete()
        if (!tempFile.renameTo(finalFile)) {
            tempFile.copyTo(finalFile, overwrite = true)
            tempFile.delete()
        }
    }

    private suspend fun finishExport(
        entity: OfflineTrackEntity,
        file: File,
        resumeInterruptedCopy: Boolean
    ) {
        if (!dao.isCurrentAttempt(entity.downloadId, entity.attemptId)) return
        val export = exports.exportDownloadedTrack(file, entity.title, entity.attemptId, resumeInterruptedCopy)
        if (!dao.isCurrentAttempt(entity.downloadId, entity.attemptId)) return
        CloudDownloadNotifications.show(
            applicationContext,
            entity.title,
            when (export) {
                CloudExportResult.DISABLED -> R.string.cloud_download_saved_offline
                CloudExportResult.EXPORTED -> R.string.cloud_download_saved_and_exported
                CloudExportResult.FAILED -> R.string.cloud_download_saved_export_failed
            },
            failed = export == CloudExportResult.FAILED
        )
    }

    private suspend fun publishProgress(
        downloadId: String,
        attemptId: String,
        copied: Long,
        total: Long?
    ) {
        val updated = dao.updateState(
            downloadId = downloadId,
            attemptId = attemptId,
            state = OfflineDownloadStatus.DOWNLOADING.storageValue,
            bytesDownloaded = copied,
            totalBytes = total,
            localPath = null,
            errorMessage = null,
            updatedAt = System.currentTimeMillis()
        )
        if (updated == 0) throw StaleDownloadAttemptException()
        setProgress(workDataOf(KEY_BYTES to copied, KEY_TOTAL_BYTES to (total ?: -1L)))
    }

    private suspend fun resolveSource(sourceUri: String): DownloadSource {
        val parsed = sourceUri.toUri()
        val id = parsed.host ?: parsed.path?.removePrefix("/")
            ?: throw IOException("Cloud track identifier is missing")
        return when (parsed.scheme?.lowercase()) {
            "navidrome" -> {
                val songId = NavidromeSongUri.parseId(sourceUri)
                if (songId == null || !CloudStreamSecurity.validateNavidromeSongId(songId)) {
                    throw IOException("Invalid Navidrome track identifier")
                }
                DownloadSource(
                    url = navidromeRepository.getStreamUrl(songId),
                    allowedHost = navidromeRepository.serverUrl
                )
            }
            "jellyfin" -> {
                if (!CloudStreamSecurity.validateJellyfinItemId(id)) {
                    throw IOException("Invalid Jellyfin track identifier")
                }
                DownloadSource(
                    url = jellyfinRepository.getStreamUrl(id),
                    headers = jellyfinRepository.getAuthorizationHeader()
                        ?.let { mapOf("Authorization" to it) }
                        .orEmpty(),
                    allowedHost = jellyfinRepository.serverUrl
                )
            }
            "youtube" -> {
                if (!CloudStreamSecurity.validateYouTubeVideoId(id)) {
                    throw IOException("Invalid YouTube video identifier")
                }
                val streamUrl = youTubeMusicRepository.getAudioStreamUrl(id)
                    .getOrElse { error -> throw IOException("Could not resolve YouTube audio", error) }
                DownloadSource(
                    url = streamUrl,
                    headers = mapOf(
                        "User-Agent" to YOUTUBE_USER_AGENT,
                        "Referer" to "https://www.youtube.com/watch?v=$id"
                    ),
                    allowedHost = streamUrl,
                    // googlevideo answers bounded ranges only, so the whole track has to be
                    // pulled in windows instead of one open-ended GET.
                    ranged = true
                )
            }
            else -> throw IOException("Unsupported cloud provider")
        }.also { source ->
            val host = source.allowedHost
                ?.toHttpUrlOrNull()
                ?.host
                ?: throw IOException("Cloud account is not connected")
            if (!CloudStreamSecurity.isSafeRemoteStreamUrl(
                    url = source.url,
                    allowedHostSuffixes = setOf(host),
                    allowHttpForAllowedHosts = true
                )
            ) {
                throw IOException("Unsafe cloud download URL")
            }
        }
    }

    private data class DownloadSource(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        val allowedHost: String?,
        val ranged: Boolean = false
    )

    private class DownloadHttpException(val code: Int) : IOException("Server returned HTTP $code")
    private class StaleDownloadAttemptException : Exception()

    companion object {
        const val TAG = "cloud_track_download"

        /** googlevideo blocks the default okhttp agent and wants a youtube.com referer. */
        private const val YOUTUBE_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

        /** Window size for ranged YouTube downloads: bounded ranges are what googlevideo serves. */
        private const val YOUTUBE_RANGE_WINDOW_BYTES = 4L * 1024L * 1024L

        /**
         * Total size from a `Content-Range: bytes 0-99/1000` header: everything after the last
         * slash. Null when the server does not know the size.
         */
        internal fun totalBytesFromContentRange(header: String?): Long? =
            header?.substringAfterLast('/')?.trim()?.toLongOrNull()

        const val KEY_DOWNLOAD_ID = "download_id"
        const val KEY_ATTEMPT_ID = "attempt_id"
        const val KEY_SOURCE_URI = "source_uri"
        const val KEY_BYTES = "bytes"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_ERROR = "error"
        private const val PROGRESS_STEP_BYTES = 512L * 1024L
        private const val MAX_RETRIES = 3

        internal fun extensionFor(responseType: String?, fallbackType: String?): String {
            val type = responseType?.substringBefore(';')?.trim()?.lowercase()
                ?: fallbackType?.substringBefore(';')?.trim()?.lowercase()
            return when (type) {
                "audio/flac", "audio/x-flac", "application/x-flac" -> "flac"
                "audio/ogg", "application/ogg", "video/ogg" -> "ogg"
                "audio/opus" -> "opus"
                "audio/mp4", "audio/m4a", "audio/x-m4a", "application/mp4", "video/mp4" -> "m4a"
                "audio/aac", "audio/aacp" -> "aac"
                "audio/wav", "audio/x-wav" -> "wav"
                "audio/webm", "video/webm" -> "webm"
                "audio/x-matroska", "video/x-matroska" -> "mka"
                "audio/3gpp", "video/3gpp" -> "3gp"
                else -> "mp3"
            }
        }
    }
}
