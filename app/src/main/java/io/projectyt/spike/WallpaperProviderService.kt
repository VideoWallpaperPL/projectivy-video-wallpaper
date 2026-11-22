package io.projectyt.spike

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Process
import android.util.Log
import tv.projectivy.plugin.wallpaperprovider.api.Event
import tv.projectivy.plugin.wallpaperprovider.api.IWallpaperProviderService
import tv.projectivy.plugin.wallpaperprovider.api.Wallpaper
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperDisplayMode
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperType

internal const val PROJECTIVY_PACKAGE = "com.spocky.projengmenu"

class WallpaperProviderService : Service() {
    override fun onBind(intent: Intent) = binder

    private val binder = object : IWallpaperProviderService.Stub() {
        override fun getWallpapers(event: Event?): List<Wallpaper> {
            val uid = Binder.getCallingUid()
            if (uid != Process.myUid() &&
                packageManager.getPackagesForUid(uid)?.contains(PROJECTIVY_PACKAGE) != true
            ) {
                return emptyList()
            }
            val selection = Selection(this@WallpaperProviderService)
            val entry = selection.history().active ?: return emptyList()
            val uri = selection.uri(entry)
            if (uid != Process.myUid()) {
                val identity = Binder.clearCallingIdentity()
                try {
                    grantUriPermission(PROJECTIVY_PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } finally {
                    Binder.restoreCallingIdentity(identity)
                }
            }
            Log.i("ProjectYT", "getWallpapers: one VIDEO")
            return listOf(Wallpaper(uri.toString(), WallpaperType.VIDEO, WallpaperDisplayMode.DEFAULT, entry.title, entry.watchUrl))
        }

        override fun getPreferences() = "{}"

        override fun setPreferences(params: String?) = Unit
    }
}
