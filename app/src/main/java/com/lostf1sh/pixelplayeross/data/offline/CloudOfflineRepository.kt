package com.lostf1sh.pixelplayeross.data.offline

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import com.lostf1sh.pixelplayeross.data.database.OfflineTrackDao
import com.lostf1sh.pixelplayeross.data.database.OfflineTrackEntity
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.worker.CloudTrackDownloadWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

enum class OfflineDownloadStatus(val storageValue: String) {
    QUEUED("queued"),
    DOWNLOADING("downloading"),
    COMPLETE("complete"),
    FAILED("failed");

    companion object {
        fun fromStorage(value: String): OfflineDownloadStatus =
            entries.firstOrNull { it.storageValue == value } ?: FAILED
    }
}

data class OfflineDownload(
    val downloadId: String,
    val sourceUri: String,
    val status: OfflineDownloadStatus,
    val bytesDownloaded: Long,
    val totalBytes: Long?,
    val localPath: String?,
    val errorMessage: String?,
    val title: String = "",
    val provider: String = "",
    val origin: OfflineTrackOrigin = OfflineTrackOrigin.USER
) {
    val progress: Float?
        get() = totalBytes?.takeIf { it > 0L }
            ?.let { (bytesDownloaded.toDouble() / it.toDouble()).coerceIn(0.0, 1.0).toFloat() }
}

@Singleton
class CloudOfflineRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: OfflineTrackDao,
    private val workManager: WorkManager,
    private val preferencesRepository: UserPreferencesRepository,
    /**
     * A track that is being saved offline belongs in the library too: it is about to live on the
     * phone, and the library is where the listener looks for the music they already have.
     */
    private val musicRepository: com.lostf1sh.pixelplayeross.data.repository.MusicRepository
) {
    private val mutationMutex = Mutex()

    fun observe(song: Song): Flow<OfflineDownload?> = observe(song.contentUriString)

    fun observe(sourceUri: String): Flow<OfflineDownload?> =
        dao.observeBySourceUri(sourceUri).map { it?.toModel() }

    fun observeCompleted(): Flow<List<OfflineDownload>> =
        dao.observeCompleted().map { rows -> rows.map(OfflineTrackEntity::toModel) }

    fun observeAll(): Flow<List<OfflineDownload>> =
        dao.observeAll().map { rows -> rows.map(OfflineTrackEntity::toModel) }

    suspend fun enqueue(song: Song, origin: OfflineTrackOrigin = OfflineTrackOrigin.USER) {
        enqueueAll(listOf(song), origin)
    }

    suspend fun enqueueAll(
        songs: Collection<Song>,
        origin: OfflineTrackOrigin = OfflineTrackOrigin.USER
    ): Int = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            var queued = 0
            val wifiOnly = preferencesRepository.downloadsWifiOnlyFlow.first()
            for (batch in downloadCandidates(songs).chunked(QUEUE_BATCH_SIZE)) {
                val existingByUri = dao.getBySourceUris(batch.map(Song::contentUriString))
                    .associateBy(OfflineTrackEntity::sourceUri)
                val now = System.currentTimeMillis()
                val attempts = batch.mapNotNull { song ->
                    val sourceUri = song.contentUriString
                    val provider = providerFor(sourceUri) ?: return@mapNotNull null
                    val existing = existingByUri[sourceUri]
                    val completedFileAvailable = existing?.localPath?.let(::File)
                        ?.let { it.isFile && it.length() > 0L } == true
                    if (!shouldStartNewAttempt(existing?.state, completedFileAvailable)) {
                        return@mapNotNull null
                    }
                    existing?.let {
                        it.localPath?.let(::File)?.delete()
                        deleteAttemptFiles(context, it.downloadId, it.attemptId)
                    }
                    OfflineTrackEntity(
                        downloadId = existing?.downloadId ?: downloadId(sourceUri),
                        attemptId = UUID.randomUUID().toString(),
                        songId = song.id,
                        sourceUri = sourceUri,
                        provider = provider,
                        title = song.title,
                        mimeType = song.mimeType,
                        localPath = null,
                        state = OfflineDownloadStatus.QUEUED.storageValue,
                        createdAt = existing?.createdAt ?: now,
                        updatedAt = now,
                        origin = origin.storageValue
                    )
                }
                if (attempts.isEmpty()) continue
                val songsByUri = batch.associateBy(Song::contentUriString)
                withContext(NonCancellable) {
                    attempts.forEach { attempt ->
                        val song = songsByUri[attempt.sourceUri] ?: return@forEach
                        runCatching { musicRepository.storeCloudSongInLibrary(song) }
                            .onFailure { Timber.w(it, "Could not add %s to the library", song.id) }
                    }
                    dao.upsertAll(attempts)
                    try {
                        workManager.enqueue(attempts.map { attempt ->
                            downloadRequest(
                                downloadId = attempt.downloadId,
                                attemptId = attempt.attemptId,
                                sourceUri = attempt.sourceUri,
                                song = songsByUri[attempt.sourceUri],
                                wifiOnly = wifiOnly
                            )
                        }).await()
                    } catch (error: Exception) {
                        attempts.forEach { attempt ->
                            dao.updateState(
                                downloadId = attempt.downloadId,
                                attemptId = attempt.attemptId,
                                state = OfflineDownloadStatus.FAILED.storageValue,
                                bytesDownloaded = 0L,
                                totalBytes = null,
                                localPath = null,
                                errorMessage = null,
                                updatedAt = System.currentTimeMillis()
                            )
                        }
                        throw error
                    }
                }
                queued += attempts.size
            }
            queued
        }
    }

    suspend fun retry(sourceUri: String) = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val existing = dao.getBySourceUri(sourceUri) ?: return@withLock
            if (existing.state != OfflineDownloadStatus.FAILED.storageValue) return@withLock

            existing.localPath?.let(::File)?.delete()
            deleteAttemptFiles(context, existing.downloadId, existing.attemptId)

            val attemptId = UUID.randomUUID().toString()
            val request = downloadRequest(
                downloadId = existing.downloadId,
                attemptId = attemptId,
                sourceUri = sourceUri,
                wifiOnly = preferencesRepository.downloadsWifiOnlyFlow.first()
            )
            dao.upsert(
                existing.copy(
                    attemptId = attemptId,
                    localPath = null,
                    state = OfflineDownloadStatus.QUEUED.storageValue,
                    bytesDownloaded = 0L,
                    totalBytes = null,
                    updatedAt = System.currentTimeMillis(),
                    errorMessage = null
                )
            )
            workManager.enqueueUniqueWork(
                workName(existing.downloadId),
                ExistingWorkPolicy.REPLACE,
                request
            ).await()
        }
    }

    suspend fun remove(song: Song) = remove(song.contentUriString)

    suspend fun remove(sourceUri: String) = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val entity = dao.getBySourceUri(sourceUri) ?: return@withLock
            // Remove ownership first. Any in-flight worker update after this point is a no-op.
            if (dao.deleteBySourceUriForAttempt(sourceUri, entity.attemptId) == 0) {
                return@withLock
            }

            try {
                workManager.cancelAllWorkByTag(workName(entity.downloadId)).await()
            } finally {
                entity.localPath?.let(::File)?.takeIf { it.exists() }?.delete()
                deleteAttemptFiles(context, entity.downloadId, entity.attemptId)
            }
        }
    }

    /**
     * Drops automatic copies until the cache fits [limitBytes] and [recentKeep] recent tracks.
     *
     * Explicit downloads are never candidates. Recent entries go first and favorites last, so a
     * hearted song survives a full budget for as long as anything can. A zero budget disables the
     * size check but still trims the recent list.
     */
    suspend fun trim(limitBytes: Long, recentKeep: Int): Int = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val completed = dao.getCompleted().sortedBy(OfflineTrackEntity::updatedAt)
            if (completed.isEmpty()) return@withLock 0

            val doomed = mutableListOf<OfflineTrackEntity>()
            // completed is sorted oldest-first, so the excess recent entries are the ones at the
            // front: keeping the newest is what makes the cache useful for the next commute.
            val recent = completed.filter { it.origin == OfflineTrackOrigin.RECENT.storageValue }
            val excessRecent = (recent.size - recentKeep.coerceAtLeast(0)).coerceAtLeast(0)
            doomed += recent.take(excessRecent)

            val survivors = completed.filterNot { it in doomed }
            if (limitBytes > 0L) {
                var total = survivors.sumOf { it.diskBytes() }
                val evictionOrder = survivors
                    .filter { it.origin == OfflineTrackOrigin.RECENT.storageValue } +
                    survivors.filter { it.origin == OfflineTrackOrigin.FAVORITE.storageValue }
                for (candidate in evictionOrder) {
                    if (total <= limitBytes) break
                    doomed += candidate
                    total -= candidate.diskBytes()
                }
            }

            for (entity in doomed) {
                if (dao.deleteBySourceUriForAttempt(entity.sourceUri, entity.attemptId) == 0) continue
                entity.localPath?.let(::File)?.takeIf { it.exists() }?.delete()
                deleteAttemptFiles(context, entity.downloadId, entity.attemptId)
            }
            doomed.size
        }
    }

    /** Called on ExoPlayer's loading thread; Room I/O is dispatched by the caller. */
    suspend fun resolveLocalUri(sourceUri: String): Uri? = withContext(Dispatchers.IO) {
        val entity = dao.getBySourceUri(sourceUri) ?: return@withContext null
        if (entity.state != OfflineDownloadStatus.COMPLETE.storageValue) return@withContext null
        val file = entity.localPath?.let(::File)
        if (file?.isFile == true && file.length() > 0L) {
            Uri.fromFile(file)
        } else {
            dao.deleteBySourceUri(sourceUri)
            null
        }
    }

    companion object {
        private const val QUEUE_BATCH_SIZE = 100

        fun isCloudSong(song: Song): Boolean = providerFor(song.contentUriString) != null

        fun downloadCandidates(songs: Iterable<Song>): List<Song> = songs.asSequence()
            .filter { isCloudSong(it) }
            .distinctBy(Song::contentUriString)
            .toList()

        fun providerFor(sourceUri: String): String? = when (
            sourceUri.substringBefore(':', "").lowercase(Locale.ROOT)
        ) {
            "navidrome" -> "navidrome"
            "jellyfin" -> "jellyfin"
            "youtube" -> "youtube"
            else -> null
        }

        fun downloadId(sourceUri: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(sourceUri.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        fun workName(downloadId: String): String = "cloud_track_download_$downloadId"

        /**
         * Album, playlist, and selection actions can all target the same track. Preserve an
         * in-flight attempt instead of replacing it and discarding already-downloaded bytes.
         */
        internal fun shouldStartNewAttempt(
            existingState: String?,
            completedFileAvailable: Boolean
        ): Boolean = when (existingState) {
            OfflineDownloadStatus.QUEUED.storageValue,
            OfflineDownloadStatus.DOWNLOADING.storageValue -> false
            OfflineDownloadStatus.COMPLETE.storageValue -> !completedFileAvailable
            else -> true
        }

        internal fun attemptFileStem(downloadId: String, attemptId: String): String =
            "$downloadId.$attemptId"

        internal fun deleteAttemptFiles(context: Context, downloadId: String, attemptId: String) {
            val prefix = "${attemptFileStem(downloadId, attemptId)}."
            downloadDirectory(context).listFiles()
                ?.asSequence()
                ?.filter { it.name.startsWith(prefix) }
                ?.forEach(File::delete)
        }

        fun downloadDirectory(context: Context): File =
            File(context.filesDir, "cloud_downloads").apply { mkdirs() }
    }

    private fun downloadRequest(
        downloadId: String,
        attemptId: String,
        sourceUri: String,
        song: Song? = null,
        wifiOnly: Boolean = false
    ): OneTimeWorkRequest {
        // The song's artist/album/cover travel with the job: the downloaded YouTube stream has
        // none of it, and a saved file should still look like the song it is.
        val input = Data.Builder()
            .putString(CloudTrackDownloadWorker.KEY_DOWNLOAD_ID, downloadId)
            .putString(CloudTrackDownloadWorker.KEY_ATTEMPT_ID, attemptId)
            .putString(CloudTrackDownloadWorker.KEY_SOURCE_URI, sourceUri)
        song?.artist?.takeIf(String::isNotBlank)
            ?.let { input.putString(CloudTrackDownloadWorker.KEY_ARTIST, it) }
        song?.album?.takeIf(String::isNotBlank)
            ?.let { input.putString(CloudTrackDownloadWorker.KEY_ALBUM, it) }
        song?.albumArtUriString?.takeIf(String::isNotBlank)
            ?.let { input.putString(CloudTrackDownloadWorker.KEY_COVER_URI, it) }
        return OneTimeWorkRequestBuilder<CloudTrackDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .setInputData(input.build())
            .addTag(CloudTrackDownloadWorker.TAG)
            .addTag(workName(downloadId))
            .build()
    }
}

private fun OfflineTrackEntity.toModel() = OfflineDownload(
    downloadId = downloadId,
    sourceUri = sourceUri,
    status = OfflineDownloadStatus.fromStorage(state),
    bytesDownloaded = bytesDownloaded,
    totalBytes = totalBytes,
    localPath = localPath,
    errorMessage = errorMessage,
    title = title,
    provider = provider,
    origin = OfflineTrackOrigin.fromStorage(origin)
)

private fun OfflineTrackEntity.diskBytes(): Long =
    localPath?.let(::File)?.takeIf { it.exists() }?.length() ?: 0L
