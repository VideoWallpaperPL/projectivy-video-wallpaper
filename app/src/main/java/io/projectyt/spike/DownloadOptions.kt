package io.projectyt.spike

internal const val MAX_CLIP_US = 300_000_000L
internal const val DEFAULT_HEIGHT = 720
internal const val HD_HEIGHT = 1080

internal data class ClipRange(val startUs: Long, val endUs: Long) {
    init {
        require(startUs >= 0 && endUs > startUs && endUs - startUs <= MAX_CLIP_US) {
            "Choose an end after the start and a clip of up to five minutes."
        }
    }

    val durationUs get() = endUs - startUs
}

internal data class DownloadOptions(
    val minutes: Int = 2,
    val includeAudio: Boolean = true,
    val height: Int = DEFAULT_HEIGHT,
    val range: ClipRange? = null,
) {
    init {
        require(minutes in 1..5)
        require(height == DEFAULT_HEIGHT || height == HD_HEIGHT)
    }

    val durationMs get() = minutes * 60_000L
    val requestedDurationUs get() = range?.durationUs ?: durationMs * 1000

    fun rangeFor(sourceDurationUs: Long): ClipRange {
        val requested = range ?: ClipRange(0, durationMs * 1000)
        if (sourceDurationUs <= 0 || sourceDurationUs >= requested.endUs) return requested
        require(requested.startUs < sourceDurationUs) { "Choose a start before the end of the video." }
        return ClipRange(requested.startUs, sourceDurationUs)
    }
}

internal sealed interface ClipProgress {
    data class Copying(val percent: Int?) : ClipProgress
    data object Saving : ClipProgress
}
