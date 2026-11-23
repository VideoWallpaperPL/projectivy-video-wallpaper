package io.projectyt.spike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DownloadOptionsTest {
    @Test
    fun defaultMatchesExistingTwoMinuteDownloadsWithAudio() {
        assertEquals(DownloadOptions(2, true), DownloadOptions())
        assertEquals(120_000L, DownloadOptions().durationMs)
        assertEquals(64L * 1024 * 1024, DownloadOptions().maxBytes)
    }

    @Test
    fun selectedDurationControlsTimeAndStorageLimits() {
        assertEquals(60_000L, DownloadOptions(1, false).durationMs)
        assertEquals(300_000L, DownloadOptions(5, false).durationMs)
        assertEquals(160L * 1024 * 1024, DownloadOptions(5, false).maxBytes)
        assertThrows(IllegalArgumentException::class.java) { DownloadOptions(0) }
        assertThrows(IllegalArgumentException::class.java) { DownloadOptions(6) }
    }
}
