package io.projectyt.spike

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal const val HISTORY_LIMIT = 12
internal const val DOWNLOAD_DIRECTORY = "wallpaper"

internal data class SavedWallpaper(val id: String, val title: String, val watchUrl: String?)

internal data class WallpaperHistory(val entries: List<SavedWallpaper>, val activeId: String?) {
    val active: SavedWallpaper? get() = entries.firstOrNull { it.id == activeId }

    fun add(entry: SavedWallpaper): WallpaperHistory {
        val previousEntries = entries.filter { entry.watchUrl == null || it.watchUrl != entry.watchUrl }
        return WallpaperHistory((listOf(entry) + previousEntries).take(HISTORY_LIMIT), entry.id)
    }

    fun activate(id: String): WallpaperHistory {
        require(entries.any { it.id == id }) { "This video is no longer available." }
        return copy(activeId = id)
    }

    fun delete(id: String): WallpaperHistory {
        val remaining = entries.filterNot { it.id == id }
        val nextActive = if (activeId == id) remaining.firstOrNull()?.id else activeId
        return WallpaperHistory(remaining, nextActive)
    }
}

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
            entry(value.optString("id"), value.optString("title"), value.optString("watchUrl"))
        }
        val id = state.optString("active")
        WallpaperHistory(saved, id.takeIf { key -> saved.any { it.id == key } } ?: saved.firstOrNull()?.id)
    }

    fun file(entry: SavedWallpaper) = File(context.filesDir, "$DOWNLOAD_DIRECTORY/${entry.id}.mp4")

    fun uri(entry: SavedWallpaper): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.video", file(entry))

    fun add(output: File, title: String, watchUrl: String?): SavedWallpaper = synchronized(lock) {
        val old = history()
        val entry = SavedWallpaper(UUID.randomUUID().toString(), title, watchUrl)
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
        old.entries.filterNot { it.id in retainedIds }.forEach(::discard)
        entry
    }

    fun activate(id: String): SavedWallpaper = synchronized(lock) {
        val updated = history().activate(id)
        save(updated)
        checkNotNull(updated.active)
    }

    fun delete(id: String) = synchronized(lock) {
        val current = history()
        val entry = current.entries.firstOrNull { it.id == id } ?: return@synchronized
        save(current.delete(id))
        discard(entry)
    }

    private fun legacyDownload(): SavedWallpaper? {
        val raw = preferences.getString("candidate", null) ?: return null
        val old = JSONObject(raw)
        val uri = Uri.parse(old.optString("uri"))
        if (uri.scheme != "content" || uri.authority != "${context.packageName}.video") return null
        val name = uri.lastPathSegment?.takeIf { it.endsWith(".mp4") } ?: return null
        return entry(name.removeSuffix(".mp4"), old.optString("title"), old.optString("watchUrl"))
    }

    private fun entry(id: String, title: String, watchUrl: String): SavedWallpaper? {
        if (!fileId.matches(id)) return null
        val entry = SavedWallpaper(id, title.ifBlank { "Wallpaper" }, watchUrl.ifBlank { null })
        return entry.takeIf { file(it).isFile }
    }

    private fun save(history: WallpaperHistory) {
        val entries = JSONArray()
        history.entries.forEach { entry ->
            entries.put(JSONObject().put("id", entry.id).put("title", entry.title).put("watchUrl", entry.watchUrl))
        }
        val state = JSONObject().put("entries", entries).put("active", history.activeId)
        check(preferences.edit().putString("history", state.toString()).remove("candidate").commit()) {
            "Could not save the wallpaper library."
        }
    }

    private fun discard(entry: SavedWallpaper) {
        context.revokeUriPermission(uri(entry), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        file(entry).delete()
    }

    private companion object {
        val lock = Any()
        val fileId = Regex("[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}")
    }
}
