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

    @Test
    fun differentDownloadOptionsKeepSeparateClipsForTheSameLink() {
        val newVersion = older.copy(id = "silent", options = DownloadOptions(5, false))
        assertEquals(listOf(newVersion, newer, older), history.add(newVersion).entries)
    }

    @Test
    fun selectionSavesMembershipIndependentlyOfTheOrderYouClick() {
        assertEquals(
            history.select(setOf(older.id, newer.id)),
            history.select(setOf(newer.id, older.id)),
        )
        val selected = history.select(setOf(older.id, newer.id))
        assertEquals(setOf(older, newer), selected.selected.toSet())
        assertEquals(newer, selected.active)
        assertThrows(IllegalArgumentException::class.java) { history.select(emptySet()) }
        assertThrows(IllegalArgumentException::class.java) { history.select(setOf(older.id, "missing")) }
    }

    @Test
    fun choosingOneWallpaperOrDeletingASelectionMaintainsAValidPreview() {
        val selected = history.select(setOf(newer.id, older.id))
        assertEquals(listOf(older), selected.activate(older.id).selected)
        assertEquals(listOf(older), selected.delete(newer.id).selected)
        assertEquals(older, selected.delete(newer.id).active)
        assertEquals(selected, selected.delete("missing"))
    }

    @Test
    fun newAndCachedDownloadsJoinAnExistingMultiSelection() {
        val selected = history.select(setOf(newer.id, older.id))
        val latest = SavedWallpaper("latest", "Latest", null)
        val updated = selected.add(latest)
        assertEquals(setOf(latest, newer, older), updated.selected.toSet())
        assertEquals(latest, updated.active)
        val cached = updated.activate(older.id, keepOthers = true)
        assertEquals(updated.selectedIds, cached.selectedIds)
        assertEquals(older, cached.active)
    }

    @Test
    fun replacingOrEvictingAVideoPrunesItsSelection() {
        val entries = history.entries + (3..12).map { SavedWallpaper("entry-$it", "Entry $it", null) }
        val last = entries.last()
        val source = WallpaperHistory(entries, newer.id).select(setOf(older.id, newer.id, last.id))
        val replacement = older.copy(id = "replacement")
        val updated = source.add(replacement)
        assertEquals(setOf(newer, last, replacement), updated.selected.toSet())
        val latest = SavedWallpaper("latest", "Latest", null)
        assertEquals(setOf(newer, replacement, latest), updated.add(latest).selected.toSet())
    }
}
