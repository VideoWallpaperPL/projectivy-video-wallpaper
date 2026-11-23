package io.projectyt.spike

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal const val HISTORY_LIMIT = 12
internal const val DOWNLOAD_DIRECTORY = "wallpaper"

internal data class SavedWallpaper(
    val id: String, val title: String, val watchUrl: String?,
    val options: DownloadOptions? = watchUrl?.let { DownloadOptions() },
)

internal data class WallpaperHistory(
    val entries: List<SavedWallpaper>, val activeId: String?, val selectedIds: Set<String> = setOfNotNull(activeId),
) {
    val active: SavedWallpaper? get() = entries.firstOrNull { it.id == activeId }
    val selected: List<SavedWallpaper> get() = entries.filter { it.id in selectedIds }

    fun add(entry: SavedWallpaper): WallpaperHistory {
        val previousEntries = entries.filter {
            entry.watchUrl == null || it.watchUrl != entry.watchUrl || it.options != entry.options
        }
        val updated = (listOf(entry) + previousEntries).take(HISTORY_LIMIT)
        val remainingSelection = if (selectedIds.size > 1) {
            updated.filter { it.id in selectedIds }.mapTo(mutableSetOf(), SavedWallpaper::id)
        } else {
            emptySet()
        }
        return WallpaperHistory(updated, entry.id, remainingSelection + entry.id)
    }

    fun activate(id: String, keepOthers: Boolean = false): WallpaperHistory {
        require(entries.any { it.id == id }) { "This video is no longer available." }
        val ids = if (keepOthers && selectedIds.size > 1) selectedIds + id else setOf(id)
        return copy(activeId = id, selectedIds = ids)
    }

    fun select(ids: Set<String>): WallpaperHistory {
        require(ids.isNotEmpty()) { "Select at least one video." }
        require(entries.map(SavedWallpaper::id).containsAll(ids)) { "A selected video is no longer available." }
        val preview = activeId?.takeIf { it in ids } ?: entries.first { it.id in ids }.id
        return copy(activeId = preview, selectedIds = ids.toSet())
    }

    fun delete(id: String): WallpaperHistory {
        val remaining = entries.filterNot { it.id == id }
        val ids = selectedIds - id
        val preview = activeId?.takeIf { it in ids }
            ?: remaining.firstOrNull { it.id in ids }?.id
            ?: remaining.firstOrNull()?.id
        return WallpaperHistory(remaining, preview, ids.ifEmpty { setOfNotNull(preview) })
    }
}

internal data class WallpaperPlayback(val id: String, val uri: Uri, val title: String, val watchUrl: String?)

internal class Selection(private val context: Context) {
    private val preferences = context.getSharedPreferences("spike", Context.MODE_PRIVATE)

    fun history(): WallpaperHistory = synchronized(lock) {
        val raw = preferences.getString("history", null)
        if (raw == null) {
            val old = legacyDownload()
            return@synchronized WallpaperHistory(listOfNotNull(old), old?.id)
        }
        val state = JSONObject(raw)
        val entries = state.getJSONArray("entries")
        val saved = (0 until entries.length()).mapNotNull { index ->
            val value = entries.getJSONObject(index)
            val url = value.optString("watchUrl").ifBlank { null }
            val options = url?.let {
                DownloadOptions(value.optInt("minutes", 2).coerceIn(1, 5), value.optBoolean("includeAudio", true))
            }
            entry(value.optString("id"), value.optString("title"), url, options)
        }
        val previousPlaylist = state.optJSONObject("playlist")
        val stored = state.optJSONArray("selected") ?: previousPlaylist?.optJSONArray("members")
        val requested = stored?.let { items -> (0 until items.length()).mapTo(mutableSetOf()) { items.optString(it) } }
            .orEmpty()
        val available = saved.mapTo(mutableSetOf(), SavedWallpaper::id)
        val ids = requested.intersect(available).ifEmpty {
            setOfNotNull(state.optString("active").takeIf { it in available } ?: saved.firstOrNull()?.id)
        }
        val active = state.optString("active").takeIf { it in ids } ?: saved.firstOrNull { it.id in ids }?.id
        val result = WallpaperHistory(saved, active, ids)
        if (previousPlaylist != null) {
            try {
                val oldId = previousPlaylist.optString("id")
                if (fileId.matches(oldId)) {
                    discard(File(context.filesDir, "$DOWNLOAD_DIRECTORY/playlist-$oldId.mp4"))
                }
                save(result)
            } catch (_: IllegalStateException) {
                Log.w("ProjectYT", "Could not finish migrating the previous wallpaper selection.")
            }
        }
        result
    }

    fun file(entry: SavedWallpaper) = File(context.filesDir, "$DOWNLOAD_DIRECTORY/${entry.id}.mp4")

    fun uri(entry: SavedWallpaper): Uri = uri(file(entry))

    fun playback(): WallpaperPlayback? = synchronized(lock) {
        history().active?.let(::playback)
    }

    fun selectedWallpapers(): List<WallpaperPlayback> = synchronized(lock) {
        history().selected.map(::playback)
    }

    private fun playback(entry: SavedWallpaper) = WallpaperPlayback(entry.id, uri(entry), entry.title, entry.watchUrl)

    fun add(
        output: File, title: String, watchUrl: String?, options: DownloadOptions?,
    ): SavedWallpaper = synchronized(lock) {
        val old = history()
        val entry = SavedWallpaper(UUID.randomUUID().toString(), title, watchUrl, options)
        val destination = file(entry)
        destination.parentFile?.mkdirs()
        check(output.renameTo(destination)) { "Could not save the video." }
        val updated = old.add(entry)
        try {
            save(updated)
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
        val retainedIds = updated.entries.mapTo(mutableSetOf()) { it.id }
        old.entries.filterNot { it.id in retainedIds }.forEach { discard(file(it)) }
        entry
    }

    fun activate(id: String, keepOthers: Boolean = false): SavedWallpaper = synchronized(lock) {
        val updated = history().activate(id, keepOthers)
        save(updated)
        checkNotNull(updated.active)
    }

    fun select(ids: Set<String>): SavedWallpaper = synchronized(lock) {
        val updated = history().select(ids)
        save(updated)
        checkNotNull(updated.active)
    }

    fun delete(id: String) = synchronized(lock) {
        val current = history()
        val entry = current.entries.firstOrNull { it.id == id } ?: return@synchronized
        discard(file(entry))
        save(current.delete(id))
    }

    private fun legacyDownload(): SavedWallpaper? {
        val raw = preferences.getString("candidate", null) ?: return null
        val old = JSONObject(raw)
        val uri = Uri.parse(old.optString("uri"))
        if (uri.scheme != "content" || uri.authority != "${context.packageName}.video") return null
        val name = uri.lastPathSegment?.takeIf { it.endsWith(".mp4") } ?: return null
        val url = old.optString("watchUrl").ifBlank { null }
        return entry(name.removeSuffix(".mp4"), old.optString("title"), url, url?.let { DownloadOptions() })
    }

    private fun entry(id: String, title: String, watchUrl: String?, options: DownloadOptions?): SavedWallpaper? {
        if (!fileId.matches(id)) return null
        val entry = SavedWallpaper(id, title.ifBlank { "Wallpaper" }, watchUrl, options)
        return entry.takeIf { file(it).isFile }
    }

    private fun save(history: WallpaperHistory) {
        val entries = JSONArray()
        history.entries.forEach { entry ->
            entries.put(JSONObject().put("id", entry.id).put("title", entry.title).put("watchUrl", entry.watchUrl)
                .put("minutes", entry.options?.minutes).put("includeAudio", entry.options?.includeAudio))
        }
        val state = JSONObject().put("entries", entries).put("active", history.activeId)
            .put("selected", JSONArray(history.selectedIds.toList()))
        check(preferences.edit().putString("history", state.toString()).remove("candidate").commit()) {
            "Could not save the wallpaper library."
        }
    }

    private fun uri(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.video", file)

    private fun discard(file: File) {
        val grantedUri = uri(file)
        check(file.delete() || !file.exists()) { "Could not delete the video file." }
        context.revokeUriPermission(grantedUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private companion object {
        val lock = Any()
        val fileId = Regex("[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}")
    }
}
