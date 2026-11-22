package io.projectyt.spike

import android.content.Context

internal class DownloadNotice(context: Context) {
    private val preferences = context.getSharedPreferences("download_notice", Context.MODE_PRIVATE)

    fun acknowledged(): Boolean = preferences.getBoolean("content_rights_v1", false)

    fun acknowledge() {
        preferences.edit().putBoolean("content_rights_v1", true).apply()
    }
}
