package com.lostf1sh.pixelplayeross.data.worker

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ContentRangeTest {

    @Test
    fun `a range response reports the size of the whole file`() {
        assertThat(CloudTrackDownloadWorker.totalBytesFromContentRange("bytes 0-99/1000"))
            .isEqualTo(1000L)
        assertThat(CloudTrackDownloadWorker.totalBytesFromContentRange("bytes 4096-8191/5000"))
            .isEqualTo(5000L)
    }

    @Test
    fun `an unknown total stays unknown`() {
        assertThat(CloudTrackDownloadWorker.totalBytesFromContentRange("bytes 0-99/*"))
            .isNull()
        assertThat(CloudTrackDownloadWorker.totalBytesFromContentRange(null)).isNull()
        assertThat(CloudTrackDownloadWorker.totalBytesFromContentRange("garbage")).isNull()
    }
}