package io.projectyt.spike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DownloadOptionsTest {
    @Test
    fun defaultMatchesExistingTwoMinuteDownloadsWithAudio() {
        assertEquals(DownloadOptions(2, true), DownloadOptions())
        assertEquals(120_000L, DownloadOptions().durationMs)
        assertEquals(720, DownloadOptions().height)
        assertEquals(ClipRange(0, 120_000_000), DownloadOptions().rangeFor(420_000_000))
    }

    @Test
    fun selectedDurationControlsSavedTime() {
        assertEquals(60_000L, DownloadOptions(1, false).durationMs)
        assertEquals(300_000L, DownloadOptions(5, false).durationMs)
        assertEquals(ClipRange(0, 70_000_000), DownloadOptions(5).rangeFor(70_000_000))
        assertThrows(IllegalArgumentException::class.java) { DownloadOptions(0) }
        assertThrows(IllegalArgumentException::class.java) { DownloadOptions(6) }
    }

    @Test
    fun trimAllowsAnOffsetAndClipsAtTheSourceEnd() {
        val options = DownloadOptions(height = 1080, range = ClipRange(90_000_000, 210_000_000))
        assertEquals(ClipRange(90_000_000, 150_000_000), options.rangeFor(150_000_000))
        assertThrows(IllegalArgumentException::class.java) { options.rangeFor(89_000_000) }
        assertThrows(IllegalArgumentException::class.java) { ClipRange(20_000_000, 321_000_000) }
        assertThrows(IllegalArgumentException::class.java) { ClipRange(90_000_000, 90_000_000) }
        assertThrows(IllegalArgumentException::class.java) { DownloadOptions(height = 2160) }
        assertEquals(90_000_000L, parseTrimTime("1:30"))
        assertThrows(IllegalArgumentException::class.java) { parseTrimTime("1:60") }
    }
}
