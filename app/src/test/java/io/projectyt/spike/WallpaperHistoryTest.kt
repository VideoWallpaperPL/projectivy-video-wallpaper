package io.projectyt.spike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class WallpaperHistoryTest {
    private val older = SavedWallpaper("older", "Older", "https://www.youtube.com/watch?v=a1234_-ABCD")
    private val newer = SavedWallpaper("newer", "Newer", null)
    private val history = WallpaperHistory(listOf(newer, older), newer.id)

    @Test
    fun newWallpaperBecomesActiveAndEvictsTheThirteenthEntry() {
        val entries = (1..12).map { SavedWallpaper("entry-$it", "Entry $it", null) }
        val added = WallpaperHistory(entries, entries.first().id).add(newer)
        assertEquals(12, added.entries.size)
        assertEquals(listOf(newer) + entries.take(11), added.entries)
        assertEquals(newer, added.active)
    }

    @Test
    fun replacingAYouTubeDownloadKeepsOneEntryForTheLink() {
        val replacement = older.copy(id = "replacement")
        val updated = history.add(replacement)
        assertEquals(listOf(replacement, newer), updated.entries)
        assertEquals(replacement, updated.active)
    }

    @Test
    fun respectingRecencyWhenActivatingOldWallpaper() {
        val updated = history.activate(older.id)
        assertEquals(history.entries, updated.entries)
        assertEquals(older, updated.active)
        assertThrows(IllegalArgumentException::class.java) { history.activate("missing") }
    }

    @Test
    fun deletingTheActiveWallpaperSelectsTheNewestRemainingEntry() {
        val updated = history.delete(newer.id)
        assertEquals(listOf(older), updated.entries)
        assertEquals(older, updated.active)
        assertNull(updated.delete(older.id).active)
    }

    @Test
    fun deletingAnInactiveWallpaperKeepsTheCurrentSelection() {
        assertEquals(newer, history.delete(older.id).active)
        assertEquals(history, history.delete("missing"))
    }
}
