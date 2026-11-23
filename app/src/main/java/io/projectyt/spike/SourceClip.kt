package io.projectyt.spike

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.AssetLoader
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExoPlayerAssetLoader
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

internal data class ClipSource(val uri: Uri, val headers: Map<String, String> = emptyMap())

@androidx.annotation.OptIn(UnstableApi::class)
internal object SourceClip {
    fun copy(
        context: Context, video: ClipSource, audio: ClipSource?, output: File,
        durationUs: Long, limitMs: Long, includeAudio: Boolean, onProgress: (Int?) -> Unit,
    ) {
        val videoItem = EditedMediaItem.Builder(mediaItem(video.uri, durationUs, limitMs))
            .setRemoveAudio(audio != null || !includeAudio)
            .build()
        val sequences = mutableListOf(EditedMediaItemSequence.Builder(listOf(videoItem)).build())
        if (audio != null) {
            val audioItem = EditedMediaItem.Builder(mediaItem(audio.uri, durationUs, limitMs)).setRemoveVideo(true).build()
            sequences.add(EditedMediaItemSequence.Builder(listOf(audioItem)).build())
        }
        val composition = Composition.Builder(sequences).setTransmuxAudio(true).setTransmuxVideo(true).build()
        onProgress(null)
        export(context, composition, listOfNotNull(video, audio), output, audio != null, onProgress)
    }

    private fun mediaItem(source: Uri, durationUs: Long, limitMs: Long) =
        MediaItem.Builder().setUri(source).setMimeType(MimeTypes.VIDEO_MP4).apply {
            if (durationUs <= 0 || durationUs > limitMs * 1000) {
                setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder().setEndPositionMs(limitMs).build(),
                )
            }
        }.build()

    private fun export(
        context: Context, composition: Composition, sources: List<ClipSource>, output: File,
        requireAudioCopy: Boolean, onProgress: (Int?) -> Unit,
    ) {
        val thread = HandlerThread("Wallpaper source copy").apply { start() }
        val handler = Handler(thread.looper)
        val finished = CompletableFuture<Unit>()
        var transformer: Transformer? = null
        handler.post {
            try {
                val builder = Transformer.Builder(context)
                    .setLooper(thread.looper)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (exportResult.videoConversionProcess == ExportResult.CONVERSION_PROCESS_TRANSMUXED &&
                                (!requireAudioCopy || exportResult.audioConversionProcess == ExportResult.CONVERSION_PROCESS_TRANSMUXED)
                            ) {
                                finished.complete(Unit)
                            } else {
                                finished.completeExceptionally(
                                    IllegalArgumentException("This clip could not be copied at its original quality."),
                                )
                            }
                        }

                        override fun onError(
                            composition: Composition, exportResult: ExportResult, exportException: ExportException,
                        ) {
                            val response = generateSequence<Throwable>(exportException) { it.cause }
                                .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                                .firstOrNull()
                            val error = if (response?.responseCode in listOf(401, 403)) {
                                IllegalArgumentException(
                                    "The source declined this media request. Try again later or import a local MP4.",
                                )
                            } else {
                                exportException
                            }
                            finished.completeExceptionally(error)
                        }
                    })
                if (sources.any { it.uri.scheme == "https" }) {
                    val decoder = DefaultDecoderFactory.Builder(context).build()
                    val loaders = sources.associateBy(ClipSource::uri) { source ->
                        if (source.uri.scheme == "https") {
                            val dataSource = DefaultHttpDataSource.Factory()
                                .setConnectTimeoutMs(10_000)
                                .setReadTimeoutMs(10_000)
                                .setDefaultRequestProperties(source.headers)
                            val mediaSource = DefaultMediaSourceFactory(context).setDataSourceFactory(dataSource)
                            ExoPlayerAssetLoader.Factory(context, decoder, Clock.DEFAULT, mediaSource)
                        } else {
                            ExoPlayerAssetLoader.Factory(context, decoder, Clock.DEFAULT)
                        }
                    }
                    builder.setAssetLoaderFactory(AssetLoader.Factory { item, looper, listener, settings ->
                        val uri = checkNotNull(item.mediaItem.localConfiguration).uri
                        loaders.getValue(uri).createAssetLoader(item, looper, listener, settings)
                    })
                }
                val export = builder.build()
                transformer = export
                export.start(composition, output.absolutePath)
                handler.post(object : Runnable {
                    private val progress = ProgressHolder()
                    private var lastReported = -1

                    override fun run() {
                        if (finished.isDone) return
                        if (export.getProgress(progress) == Transformer.PROGRESS_STATE_AVAILABLE &&
                            progress.progress != lastReported
                        ) {
                            lastReported = progress.progress
                            onProgress(lastReported)
                        }
                        handler.postDelayed(this, 500)
                    }
                })
            } catch (error: Exception) {
                finished.completeExceptionally(error)
            }
        }
        try {
            finished.get()
        } catch (error: ExecutionException) {
            throw (error.cause ?: error)
        } finally {
            handler.post {
                try {
                    if (!finished.isDone) transformer?.cancel()
                } finally {
                    thread.quitSafely()
                }
            }
            thread.join()
        }
    }
}
