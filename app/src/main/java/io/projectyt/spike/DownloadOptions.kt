package io.projectyt.spike

internal data class DownloadOptions(val minutes: Int = 2, val includeAudio: Boolean = true) {
    init {
        require(minutes in 1..5)
    }

    val durationMs get() = minutes * 60_000L
    val maxBytes get() = minutes * 32L * 1024 * 1024
}

internal sealed interface ClipProgress {
    data class Copying(val percent: Int?) : ClipProgress
    data object Saving : ClipProgress
}
