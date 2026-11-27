package com.lostf1sh.pixelplayeross.data.offline

import android.content.Context
import androidx.work.WorkManager
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.database.OfflineTrackDao
import com.lostf1sh.pixelplayeross.data.database.OfflineTrackEntity
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CloudOfflineRepositoryTrimTest {

    @TempDir
    lateinit var tempDir: File

    private val dao: OfflineTrackDao = mockk(relaxed = true)
    private val workManager: WorkManager = mockk(relaxed = true)
    private val preferences: UserPreferencesRepository = mockk(relaxed = true)

    private fun repository(): CloudOfflineRepository {
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns tempDir
        return CloudOfflineRepository(context, dao, workManager, preferences)
    }

    private fun row(
        id: String,
        origin: OfflineTrackOrigin,
        updatedAt: Long,
        sizeBytes: Long = 0L
    ): OfflineTrackEntity {
        val file = if (sizeBytes > 0L) {
            File(tempDir, "$id.m4a").apply { writeBytes(ByteArray(sizeBytes.toInt())) }
        } else {
            null
        }
        return OfflineTrackEntity(
            downloadId = id,
            attemptId = "attempt-$id",
            songId = id,
            sourceUri = "youtube://$id",
            provider = "youtube",
            title = id,
            mimeType = "audio/mp4",
            localPath = file?.absolutePath,
            state = OfflineDownloadStatus.COMPLETE.storageValue,
            bytesDownloaded = sizeBytes,
            totalBytes = sizeBytes,
            createdAt = updatedAt,
            updatedAt = updatedAt,
            origin = origin.storageValue
        )
    }

    @Test
    fun `keeps only the newest recent tracks`() = runTest {
        val rows = listOf(
            row("oldest", OfflineTrackOrigin.RECENT, updatedAt = 1L),
            row("middle", OfflineTrackOrigin.RECENT, updatedAt = 2L),
            row("newest", OfflineTrackOrigin.RECENT, updatedAt = 3L),
            row("manual", OfflineTrackOrigin.USER, updatedAt = 0L)
        )
        coEvery { dao.getCompleted() } returns rows
        coEvery { dao.deleteBySourceUriForAttempt(any(), any()) } returns 1

        val removed = repository().trim(limitBytes = 0L, recentKeep = 1)

        assertThat(removed).isEqualTo(2)
        coVerify { dao.deleteBySourceUriForAttempt("youtube://oldest", "attempt-oldest") }
        coVerify { dao.deleteBySourceUriForAttempt("youtube://middle", "attempt-middle") }
        coVerify(exactly = 0) { dao.deleteBySourceUriForAttempt("youtube://newest", any()) }
        coVerify(exactly = 0) { dao.deleteBySourceUriForAttempt("youtube://manual", any()) }
    }

    @Test
    fun `evicts recent tracks before favorites to fit the budget`() = runTest {
        val rows = listOf(
            row("recent", OfflineTrackOrigin.RECENT, updatedAt = 1L, sizeBytes = 100L),
            row("heart", OfflineTrackOrigin.FAVORITE, updatedAt = 2L, sizeBytes = 100L),
            row("manual", OfflineTrackOrigin.USER, updatedAt = 3L, sizeBytes = 100L)
        )
        coEvery { dao.getCompleted() } returns rows
        coEvery { dao.deleteBySourceUriForAttempt(any(), any()) } returns 1

        val removed = repository().trim(limitBytes = 250L, recentKeep = 10)

        assertThat(removed).isEqualTo(1)
        coVerify { dao.deleteBySourceUriForAttempt("youtube://recent", "attempt-recent") }
        coVerify(exactly = 0) { dao.deleteBySourceUriForAttempt("youtube://manual", any()) }
        assertThat(File(tempDir, "manual.m4a").exists()).isTrue()
    }

    @Test
    fun `never evicts explicit downloads to reach the budget`() = runTest {
        val rows = listOf(
            row("one", OfflineTrackOrigin.USER, updatedAt = 1L, sizeBytes = 100L),
            row("two", OfflineTrackOrigin.USER, updatedAt = 2L, sizeBytes = 100L)
        )
        coEvery { dao.getCompleted() } returns rows

        val removed = repository().trim(limitBytes = 10L, recentKeep = 10)

        assertThat(removed).isEqualTo(0)
        assertThat(File(tempDir, "one.m4a").exists()).isTrue()
        assertThat(File(tempDir, "two.m4a").exists()).isTrue()
    }
}