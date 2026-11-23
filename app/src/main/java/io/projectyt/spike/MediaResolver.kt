package io.projectyt.spike

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import android.util.Log
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal const val CLIP_DURATION_MS = 120_000L
private const val MAX_DURATION_US = CLIP_DURATION_MS * 1000 + 100_000
private const val MAX_OUTPUT_BYTES = 64L * 1024 * 1024
private const val DOWNLOAD_HEIGHT = 720
private const val DOWNLOAD_FORMAT = "bv[ext=mp4][vcodec^=avc1][height=$DOWNLOAD_HEIGHT][protocol=https]"
private const val AUDIO_FORMAT = "ba[ext=m4a][acodec^=mp4a][protocol=https]"

internal object MediaResolver {
    private val headerNames = listOf("User-Agent", "Accept", "Accept-Language", "Referer", "Origin", "Sec-Fetch-Mode")

    @Synchronized
    fun download(context: Context, watchUrl: String, onProgress: (Int?) -> Unit): SavedWallpaper {
        check(DownloadNotice(context).acknowledged()) { "Acknowledge the content-use notice before downloading." }
        val library = Selection(context)
        val existing = library.history().entries.firstOrNull { it.watchUrl == watchUrl }
        if (existing != null) {
            val file = library.file(existing)
            val details = runCatching { inspect(context, Uri.fromFile(file)) }.getOrNull()
            if (details?.height == DOWNLOAD_HEIGHT && details.hasAudio && details.canCopy(file.length())) {
                return library.activate(existing.id)
            }
        }
        val resolved = resolve(context, watchUrl)
        val formats = resolved.getJSONArray("requested_formats")
        require(formats.length() == 2) { "YouTube did not provide both tracks." }
        val durationUs = (resolved.optDouble("duration", 0.0) * 1_000_000).toLong()
        return saveClip(
            context, clipSource(formats.getJSONObject(0)), resolved.optString("title", "YouTube video"),
            watchUrl, durationUs, onProgress, audio = clipSource(formats.getJSONObject(1)), requireAudio = true,
        )
    }

    private fun clipSource(format: JSONObject): ClipSource {
        val source = Uri.parse(format.getString("url"))
        require(source.scheme == "https" && source.host?.endsWith(".googlevideo.com") == true) {
            "YouTube returned an unsupported media source."
        }
        val headers = format.optJSONObject("http_headers")
        val requestHeaders = headerNames.mapNotNull { name ->
            headers?.optString(name)?.takeIf(String::isNotBlank)?.let { name to it }
        }.toMap()
        return ClipSource(source, requestHeaders)
    }

    @Synchronized
    fun import(context: Context, uri: Uri, onProgress: (Int?) -> Unit): SavedWallpaper {
        val details = inspect(context, uri)
        val title = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?.removeSuffix(".mp4")?.takeIf(String::isNotBlank) ?: "Imported video"
        val length = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        return saveClip(
            context, ClipSource(uri), title, null, details.durationUs, onProgress,
            copyOnly = details.canCopy(length), requireAudio = details.hasAudio,
        )
    }

    private fun saveClip(
        context: Context, video: ClipSource, title: String, watchUrl: String?, durationUs: Long,
        onProgress: (Int?) -> Unit, requireAudio: Boolean, audio: ClipSource? = null, copyOnly: Boolean = false,
    ): SavedWallpaper {
        val output = File(context.cacheDir, "wallpaper-pending.mp4")
        output.delete()
        try {
            if (copyOnly) {
                val input = context.contentResolver.openInputStream(video.uri)
                    ?: throw IllegalArgumentException("Could not open the selected file.")
                input.use { from ->
                    output.outputStream().use { to ->
                        val buffer = ByteArray(64 * 1024)
                        var remaining = MAX_OUTPUT_BYTES
                        while (true) {
                            if (Thread.currentThread().isInterrupted) throw InterruptedException()
                            val read = from.read(buffer)
                            if (read < 0) break
                            remaining -= read
                            require(remaining >= 0) { "The selected file changed while it was being imported." }
                            to.write(buffer, 0, read)
                        }
                    }
                }
            } else {
                try {
                    SourceClip.copy(context, video, audio, output, durationUs, onProgress)
                } catch (error: InterruptedException) {
                    throw error
                } catch (error: IllegalArgumentException) {
                    throw error
                } catch (error: Exception) {
                    // The exception can include the signed source URL.
                    Log.w("ProjectYT", "Video preparation failed: ${error.javaClass.simpleName}")
                    throw IllegalArgumentException("Could not copy this video. Try another source.")
                }
            }
            val result = inspect(context, Uri.fromFile(output))
            require(!result.hasOtherTracks && result.durationUs in 1..MAX_DURATION_US &&
                (watchUrl == null || result.height == DOWNLOAD_HEIGHT)
            ) { "The saved video did not meet the two-minute format limit." }
            require(!requireAudio || result.hasAudio) { "The requested audio track was missing from the saved clip." }
            require(output.length() in 1..MAX_OUTPUT_BYTES) { "The two-minute clip exceeded the 64 MiB limit." }
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            return Selection(context).add(output, title, watchUrl)
        } finally {
            output.delete()
        }
    }

    private data class ClipDetails(
        val height: Int, val durationUs: Long, val hasAudio: Boolean, val hasOtherTracks: Boolean,
    ) {
        fun canCopy(bytes: Long) =
            !hasOtherTracks && durationUs in 1..MAX_DURATION_US && bytes in 1..MAX_OUTPUT_BYTES
    }

    private fun inspect(context: Context, uri: Uri): ClipDetails {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val tracks = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            val track = tracks.firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            require(track?.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_AVC &&
                track.getInteger(MediaFormat.KEY_HEIGHT) in listOf(DOWNLOAD_HEIGHT, 1080)
            ) { "Choose a 720p or 1080p H.264 MP4 file." }
            val audio = tracks.firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            val duration = listOfNotNull(track, audio).maxOf {
                if (it.containsKey(MediaFormat.KEY_DURATION)) it.getLong(MediaFormat.KEY_DURATION) else 0L
            }
            val expectedTracks = if (audio == null) 1 else 2
            return ClipDetails(
                track.getInteger(MediaFormat.KEY_HEIGHT), duration, audio != null, tracks.size > expectedTracks,
            )
        } finally {
            extractor.release()
        }
    }

    private fun resolve(context: Context, watchUrl: String): JSONObject {
        val archive = prepareRuntime(context)
        val quickJs = File(context.applicationInfo.nativeLibraryDir, "libprojectyt-qjs.so")
        val arguments = listOf(
            "--ignore-config",
            "--no-playlist",
            "--match-filter", "!is_live & !is_upcoming & availability~='^(public|unlisted)$'",
            "--no-js-runtimes", "--js-runtimes", "quickjs:${quickJs.absolutePath}",
            "--no-remote-components",
            "--socket-timeout", "10",
            "--retries", "0",
            "--extractor-retries", "0",
            "--format", "($DOWNLOAD_FORMAT[fps<=30]/$DOWNLOAD_FORMAT)+($AUDIO_FORMAT[acodec=mp4a.40.2]/$AUDIO_FORMAT)",
            "--format-sort", "+fps,+filesize",
            "--format-sort-force",
            "--simulate",
            "--print", "%(.{title,requested_formats,duration})j",
            "--", watchUrl,
        )
        val response = try {
            val downloader = Python.getInstance().getModule("wallpaper_downloader")
            JSONArray(downloader.callAttr("resolve", archive.absolutePath, JSONArray(arguments).toString()).toString())
        } catch (_: PyException) {
            throw IllegalArgumentException("Could not open this YouTube video.")
        }
        if (response.getInt(0) != 0) {
            val message = response.getString(2)
            throw IllegalArgumentException(when {
                message.contains("Requested format is not available", ignoreCase = true) ->
                    "This video needs a 720p H.264 stream and an AAC audio track."
                message.contains("Sign in", ignoreCase = true) ||
                    message.contains("Private video", ignoreCase = true) ||
                    message.contains("members-only", ignoreCase = true) ||
                    message.contains("DRM", ignoreCase = true) ->
                    "This video requires access the app does not request. Choose a public or unlisted video."
                message.contains("timed out", ignoreCase = true) ->
                    "YouTube timed out. Check the connection and try again."
                else -> "Could not open this YouTube video."
            })
        }
        val json = response.getString(1).lineSequence().map(String::trim).lastOrNull { it.startsWith("{") }
        require(json != null) { "Choose a prerecorded YouTube video with compatible video and audio streams." }
        return JSONObject(json)
    }

    private fun prepareRuntime(context: Context): File {
        val name = "yt-dlp-${BuildConfig.YT_DLP_VERSION}"
        val archive = File(context.noBackupFilesDir, name)
        if (!archive.isFile) {
            val binary = AtomicFile(archive)
            val output = binary.startWrite()
            try {
                context.assets.open(name).use { it.copyTo(output) }
                binary.finishWrite(output)
            } catch (error: Exception) {
                binary.failWrite(output)
                throw error
            }
        }
        if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
        return archive
    }
}
