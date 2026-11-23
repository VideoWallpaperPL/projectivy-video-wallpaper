package io.projectyt.spike

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Process
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
            val selected = Selection(this@WallpaperProviderService).selectedWallpapers()
            if (uid != Process.myUid()) {
                val identity = Binder.clearCallingIdentity()
                try {
                    selected.forEach {
                        grantUriPermission(PROJECTIVY_PACKAGE, it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity)
                }
            }
            return selected.map {
                Wallpaper(it.uri.toString(), WallpaperType.VIDEO, WallpaperDisplayMode.DEFAULT, it.title, it.watchUrl)
            }
        }

        override fun getPreferences() = "{}"

        override fun setPreferences(params: String?) = Unit
    }
}
