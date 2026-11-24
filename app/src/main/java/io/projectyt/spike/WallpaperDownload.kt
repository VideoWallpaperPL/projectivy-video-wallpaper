package io.projectyt.spike

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException

internal object WallpaperDownload {
    const val MIME_TYPE = "video/mp4"
    private val invalidFileNameChars = Regex("""[\\/:*?"<>|\p{Cntrl}]+""")

    fun fileName(title: String): String {
        val sanitized = title.replace(invalidFileNameChars, "_").trim(' ', '.')
        val name = if (sanitized.endsWith(".mp4", ignoreCase = true)) sanitized.dropLast(4) else sanitized
        val shortened = name.take(70)
        val basename = shortened.dropLastWhile(Char::isHighSurrogate).trimEnd(' ', '.')
        return "${basename.ifBlank { "Video wallpaper" }}.mp4"
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun toDownloads(resolver: ContentResolver, source: File, title: String) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName(title))
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create the download.")
        try {
            copy(resolver, source, uri)
            val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            if (resolver.update(uri, published, null, null) != 1) throw IOException("Could not publish the download.")
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    fun toDocument(resolver: ContentResolver, source: File?, uri: Uri) {
        try {
            copy(resolver, source ?: throw IOException("The saved wallpaper is missing."), uri)
        } catch (error: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            throw error
        }
    }

    private fun copy(resolver: ContentResolver, source: File, uri: Uri) {
        source.inputStream().use { input ->
            val output = resolver.openOutputStream(uri, "w") ?: throw IOException("Could not open the destination.")
            output.use {
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
        }
    }
}
