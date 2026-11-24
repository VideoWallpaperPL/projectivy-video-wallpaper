package io.projectyt.spike

import org.junit.Assert.assertEquals
import org.junit.Test

class WallpaperDownloadTest {
    @Test
    fun sanitizesPathSeparatorsAndKeepsOneMp4Extension() {
        assertEquals("Sunrise_Mountains_Clouds.mp4", WallpaperDownload.fileName(" Sunrise/Mountains\\Clouds.MP4 "))
    }

    @Test
    fun suppliesANameForAnEmptyTitle() {
        assertEquals("Video wallpaper.mp4", WallpaperDownload.fileName(" ... "))
    }

    @Test
    fun doesNotSplitAnEmojiAtTheLengthLimit() {
        val prefix = "a".repeat(69)
        assertEquals("$prefix.mp4", WallpaperDownload.fileName("${prefix}🌄"))
    }
}
