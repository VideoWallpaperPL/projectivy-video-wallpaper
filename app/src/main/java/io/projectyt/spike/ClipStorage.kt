package io.projectyt.spike

import android.system.ErrnoException
import android.system.OsConstants
import java.io.File

internal class ClipStorage(private val directory: File) {
    fun check(estimatedBytes: Long = 0) {
        val margin = minOf(directory.totalSpace / 20, 64L * 1024 * 1024)
        if (directory.usableSpace <= margin + estimatedBytes) throw LowClipStorage()
    }

    fun failure(error: Exception): LowClipStorage? {
        if (error is LowClipStorage) return error
        val noSpace = generateSequence<Throwable>(error) { it.cause }.any {
            (it is ErrnoException && it.errno == OsConstants.ENOSPC) ||
                it.message?.contains("No space left on device", ignoreCase = true) == true
        }
        return if (noSpace) LowClipStorage() else null
    }
}

internal class LowClipStorage : IllegalArgumentException(
    "Not enough free space for this clip. Free some space or choose a shorter clip.",
)
