package com.lostf1sh.pixelplayeross.data.worker

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import org.junit.jupiter.api.Test

class CloudDownloadRetryTest {
    @Test
    fun `client errors are not retried`() {
        assertThat(isRetryableDownloadFailure(CloudTrackDownloadWorker.DownloadHttpException(403)))
            .isFalse()
        assertThat(isRetryableDownloadFailure(CloudTrackDownloadWorker.DownloadHttpException(404)))
            .isFalse()
        assertThat(isRetryableDownloadFailure(CloudTrackDownloadWorker.DownloadHttpException(416)))
            .isFalse()
    }

    @Test
    fun `server errors and io failures are retried`() {
        assertThat(isRetryableDownloadFailure(CloudTrackDownloadWorker.DownloadHttpException(500)))
            .isTrue()
        assertThat(isRetryableDownloadFailure(CloudTrackDownloadWorker.DownloadHttpException(503)))
            .isTrue()
        assertThat(isRetryableDownloadFailure(IOException("socket reset"))).isTrue()
    }

    @Test
    fun `unrelated failures are not retried`() {
        assertThat(isRetryableDownloadFailure(IllegalStateException("bad state"))).isFalse()
    }
}