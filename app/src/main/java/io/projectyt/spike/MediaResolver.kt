package io.projectyt.spike

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Log
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val AUDIO_FORMAT = "ba[ext=m4a][acodec^=mp4a][protocol=https]"

internal data class PreparedVideo(
    val watchUrl: String, val title: String, val durationUs: Long, val height: Int,
    val video: ClipSource, val audio: ClipSource?,
) {
    private val preparedAt = SystemClock.elapsedRealtime()
    val isFresh get() = SystemClock.elapsedRealtime() - preparedAt < 5 * 60_000L
}

internal class ResolutionFallback(val source: PreparedVideo) : IllegalArgumentException()

internal object MediaResolver {
    private val headerNames = listOf("User-Agent", "Accept", "Accept-Language", "Referer", "Origin", "Sec-Fetch-Mode")
    private val importLimits = DownloadOptions()

    @Synchronized
    fun download(
        context: Context, watchUrl: String, options: DownloadOptions, source: PreparedVideo? = null,
        onProgress: (ClipProgress) -> Unit,
    ): SavedWallpaper {
        check(DownloadNotice(context).acknowledged()) { "Acknowledge the content-use notice before downloading." }
        val library = Selection(context)
        val existing = library.history().entries.firstOrNull { it.watchUrl == watchUrl && it.options == options }
        if (existing != null) {
            val file = library.file(existing)
            val details = runCatching { inspect(context, Uri.fromFile(file)) }.getOrNull()
            if (details?.height == options.height && details.hasAudio == options.includeAudio &&
                details.canCopy(options.requestedDurationUs)
            ) {
                return library.activate(existing.id, keepOthers = true)
            }
        }
        val resolved = source?.takeIf { it.isFresh && it.watchUrl == watchUrl &&
            (it.audio != null) == options.includeAudio } ?: prepare(context, watchUrl, options)
        if (resolved.height != options.height) throw ResolutionFallback(resolved)
        val range = options.rangeFor(resolved.durationUs)
        val actualOptions = if (options.range != null && source !== resolved) {
            options.copy(range = SourceClip.alignStart(resolved.video, range))
        } else {
            options
        }
        return saveClip(
            context, resolved.video, resolved.title, watchUrl, resolved.durationUs, actualOptions, onProgress,
            audio = resolved.audio, includeAudio = options.includeAudio,
        )
    }

    fun prepare(context: Context, watchUrl: String, options: DownloadOptions): PreparedVideo {
        check(DownloadNotice(context).acknowledged()) { "Acknowledge the content-use notice before downloading." }
        val resolved = resolve(context, watchUrl, options)
        checkInterrupted()
        val formats = resolved.optJSONArray("requested_formats")
        val video = formats?.optJSONObject(0) ?: resolved
        val height = video.optInt("height")
        require(height == options.height || (options.height == HD_HEIGHT && height == DEFAULT_HEIGHT)) {
            if (options.height == HD_HEIGHT) "This video has no compatible 1080p or 720p H.264 stream."
            else "This video has no compatible 720p H.264 stream."
        }
        require(video.optString("vcodec").startsWith("avc1") &&
            video.optString("ext") == "mp4" && video.optString("protocol") == "https") {
            "This video has no compatible H.264 MP4 stream."
        }
        val audio = formats?.optJSONObject(1)?.takeIf { options.includeAudio }
        require(!options.includeAudio || audio != null) {
            "This video has no compatible AAC audio track. Turn audio off and try again."
        }
        val durationUs = (resolved.optDouble("duration", 0.0) * 1_000_000).toLong()
        return PreparedVideo(
            watchUrl, resolved.optString("title", "YouTube video"), durationUs, height,
            clipSource(video, durationUs), audio?.let { clipSource(it, durationUs) },
        )
    }

    private fun clipSource(format: JSONObject, durationUs: Long): ClipSource {
        val source = Uri.parse(format.getString("url"))
        require(source.scheme == "https" && source.host?.endsWith(".googlevideo.com") == true) {
            "YouTube returned an unsupported media source."
        }
        val headers = format.optJSONObject("http_headers")
        val requestHeaders = headerNames.mapNotNull { name ->
            headers?.optString(name)?.takeIf(String::isNotBlank)?.let { name to it }
        }.toMap()
        val knownSize = format.optLong("filesize").takeIf { it > 0 }
            ?: format.optLong("filesize_approx").takeIf { it > 0 }
        val bitsPerSecond = if (knownSize != null && durationUs > 0) {
            (knownSize * 8_000_000.0 / durationUs).toLong()
        } else {
            (format.optDouble("tbr", 0.0) * 1000).toLong()
        }
        return ClipSource(source, requestHeaders, bitsPerSecond.takeIf { it in 1..200_000_000 })
    }

    @Synchronized
    fun import(context: Context, uri: Uri, onProgress: (ClipProgress) -> Unit): SavedWallpaper {
        val details = inspect(context, uri)
        val title = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?.removeSuffix(".mp4")?.takeIf(String::isNotBlank) ?: "Imported video"
        val length = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        return saveClip(
            context, ClipSource(uri), title, null, details.durationUs, importLimits, onProgress,
            copyBytes = length.takeIf { it > 0 && details.canCopy(importLimits.requestedDurationUs) },
            includeAudio = details.hasAudio,
        )
    }

    private fun saveClip(
        context: Context, video: ClipSource, title: String, watchUrl: String?, durationUs: Long,
        limits: DownloadOptions, onProgress: (ClipProgress) -> Unit,
        includeAudio: Boolean, audio: ClipSource? = null, copyBytes: Long? = null,
    ): SavedWallpaper {
        val output = File(context.cacheDir, "wallpaper-pending.mp4")
        val storage = ClipStorage(context.cacheDir)
        val range = limits.rangeFor(durationUs)
        val requestedSources = listOfNotNull(video, audio)
        val estimatedBytes = if (requestedSources.all { it.bitsPerSecond != null }) {
            requestedSources.sumOf { checkNotNull(it.bitsPerSecond) } * range.durationUs / 8_000_000
        } else 0L
        output.delete()
        try {
            checkInterrupted()
            storage.check(copyBytes ?: estimatedBytes)
            if (copyBytes != null) {
                val input = context.contentResolver.openInputStream(video.uri)
                    ?: throw IllegalArgumentException("Could not open the selected file.")
                input.use { from ->
                    output.outputStream().use { to ->
                        val buffer = ByteArray(64 * 1024)
                        var remaining = copyBytes
                        while (true) {
                            checkInterrupted()
                            val read = from.read(buffer)
                            if (read < 0) break
                            remaining -= read
                            if (remaining < 0) storage.check(read.toLong())
                            to.write(buffer, 0, read)
                        }
                    }
                }
            } else {
                try {
                    val clip = range.takeUnless { range.startUs == 0L && durationUs == range.endUs }
                    SourceClip.copy(context, video, audio, output, clip, includeAudio, storage) {
                        onProgress(ClipProgress.Copying(it))
                    }
                } catch (error: InterruptedException) {
                    throw error
                } catch (error: IllegalArgumentException) {
                    throw error
                } catch (error: Exception) {
                    storage.failure(error)?.let { throw it }
                    // The exception can include the signed source URL.
                    Log.w("ProjectYT", "Video preparation failed: ${error.javaClass.simpleName}")
                    throw IllegalArgumentException("Could not copy this video. Try another source.")
                }
            }
            val result = inspect(context, Uri.fromFile(output))
            require(!result.hasOtherTracks && (watchUrl == null || result.height == limits.height)) {
                "The saved clip did not match the requested video format."
            }
            require(result.durationUs in 1..(range.durationUs + CLIP_DURATION_TOLERANCE_US)) {
                "The saved clip exceeded the selected time range. Try adjusting the trim points."
            }
            require(result.hasAudio == includeAudio) { "The saved clip did not match the requested audio setting." }
            storage.check()
            checkInterrupted()
            onProgress(ClipProgress.Saving)
            return Selection(context).add(output, title, watchUrl, limits.takeIf { watchUrl != null })
        } catch (error: Exception) {
            throw storage.failure(error) ?: error
        } finally {
            output.delete()
        }
    }

    private data class ClipDetails(
        val height: Int, val durationUs: Long, val hasAudio: Boolean, val hasOtherTracks: Boolean,
    ) {
        fun canCopy(limitUs: Long) =
            !hasOtherTracks && durationUs in 1..(limitUs + CLIP_DURATION_TOLERANCE_US)
    }

    private fun inspect(context: Context, uri: Uri): ClipDetails {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val tracks = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            val track = tracks.firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            require(track?.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_AVC &&
                track.getInteger(MediaFormat.KEY_HEIGHT) in listOf(DEFAULT_HEIGHT, HD_HEIGHT)
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

    private fun resolve(context: Context, watchUrl: String, options: DownloadOptions): JSONObject {
        val archive = prepareRuntime(context)
        val quickJs = File(context.applicationInfo.nativeLibraryDir, "libprojectyt-qjs.so")
        fun videoFormat(height: Int): String {
            val selector = "bv[ext=mp4][vcodec^=avc1][height=$height][protocol=https]"
            return "$selector[fps<=30]/$selector"
        }
        val video = if (options.height == HD_HEIGHT) {
            "(${videoFormat(HD_HEIGHT)}/${videoFormat(DEFAULT_HEIGHT)})"
        } else {
            "(${videoFormat(DEFAULT_HEIGHT)})"
        }
        val format = if (options.includeAudio) {
            "$video+($AUDIO_FORMAT[acodec=mp4a.40.2]/$AUDIO_FORMAT)/$video"
        } else video
        val arguments = listOf(
            "--ignore-config",
            "--no-playlist",
            "--ignore-no-formats-error",
            "--no-js-runtimes", "--js-runtimes", "quickjs:${quickJs.absolutePath}",
            "--no-remote-components",
            "--socket-timeout", "10",
            "--retries", "0",
            "--extractor-retries", "0",
            "--format", format,
            "--format-sort", "+fps,+filesize",
            "--format-sort-force",
            "--simulate",
            "--print", "%(.{title,requested_formats,duration,url,http_headers,live_status,availability,height,vcodec,ext,protocol,filesize,filesize_approx,tbr})j",
            "--", watchUrl,
        )
        val response = try {
            val downloader = Python.getInstance().getModule("wallpaper_downloader")
            JSONArray(downloader.callAttr("resolve", archive.absolutePath, JSONArray(arguments).toString()).toString())
        } catch (_: PyException) {
            throw IllegalArgumentException("Could not open this YouTube video.")
        }
        checkInterrupted()
        if (response.getInt(0) != 0) {
            val message = response.getString(2)
            throw IllegalArgumentException(when {
                message.contains("Requested format is not available", ignoreCase = true) ->
                    if (options.includeAudio) "This video needs a 720p H.264 stream and an AAC audio track."
                    else "This video needs a 720p H.264 stream."
                message.contains("Sign in to confirm", ignoreCase = true) &&
                    message.contains("bot", ignoreCase = true) ->
                    "YouTube asked for verification. Try this link again later."
                message.contains("Sign in", ignoreCase = true) ||
                    message.contains("Private video", ignoreCase = true) ||
                    message.contains("members-only", ignoreCase = true) ||
                    message.contains("DRM", ignoreCase = true) ->
                    "This video requires access the app does not request. Choose a public or unlisted video."
                message.contains("timed out", ignoreCase = true) ->
                    "YouTube timed out. Check the connection and try again."
                listOf("unable to download", "network is unreachable", "name resolution").any {
                    message.contains(it, ignoreCase = true)
                } -> "Could not reach YouTube. Check the connection and try again."
                else -> "Could not open this YouTube video."
            })
        }
        val json = response.getString(1).lineSequence().map(String::trim).lastOrNull { it.startsWith("{") }
        require(json != null) { "Choose a prerecorded YouTube video with compatible video and audio streams." }
        val result = JSONObject(json)
        when (result.optString("live_status")) {
            "is_live" -> throw IllegalArgumentException("This video is live now. Try a prerecorded video or wait for the replay.")
            "is_upcoming" -> throw IllegalArgumentException("This stream has not started yet. Try a prerecorded video.")
            "post_live" -> throw IllegalArgumentException("The stream has ended. Try again when the replay is available.")
        }
        require(result.optString("availability") in listOf("public", "unlisted")) {
            "This video requires access the app does not request. Choose a public or unlisted video."
        }
        return result
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

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
    }
}

private const val CLIP_DURATION_TOLERANCE_US = 250_000L
