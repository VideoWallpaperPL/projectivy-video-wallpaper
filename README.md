# Video Wallpaper for Projectivy Launcher

<img src="docs/app-icon.png" width="96" height="96" alt="App icon showing a mint landscape and a gold sun">

Play videos from your Android TV’s local storage as wallpapers in Projectivy Launcher. Import a compatible MP4, or request a short clip from a YouTube link you are permitted to download.

> This is an independently developed, unofficial plugin for Projectivy Launcher. It is not affiliated with or endorsed by the Projectivy Launcher developers, Google, or YouTube.

## Releases

GitHub releases include the source code for each version. The latest release also includes a signed APK for Android TV and Google TV.

- [Download the signed v0.6 APK](https://github.com/VideoWallpaperPL/projectivy-video-wallpaper/releases/download/v0.6/video-wallpaper-v0.6.apk)
- [v0.6 SHA-256 checksum](https://github.com/VideoWallpaperPL/projectivy-video-wallpaper/releases/download/v0.6/SHA256SUMS.txt)
- [Latest release notes](https://github.com/VideoWallpaperPL/projectivy-video-wallpaper/releases/latest)
- [All releases](https://github.com/VideoWallpaperPL/projectivy-video-wallpaper/releases)

Follow the [Android TV installation instructions](#install-on-android-tv) after downloading the APK.

## Screenshots

![Wallpaper library with audio and clip length controls, a preview, and three selected videos](docs/screenshots/library.png)

![Select wallpapers dialog with three videos checked for Projectivy to rotate through](docs/screenshots/selection.png)

<details>
<summary>Wallpaper actions, download notice, and progress</summary>

![Selected wallpaper with Set background and Delete actions](docs/screenshots/actions.png)

![First-use content rights notice before a download](docs/screenshots/download-notice.png)

![Animated progress bar during the search for a compatible 720p source](docs/screenshots/download-progress.png)

</details>

Captured from the development build using original sample artwork.

## Features

- **Local import.** Add a 720p or 1080p H.264 MP4. The app keeps its audio when present.
- **On-device downloads.** Paste or share a YouTube link or video ID. Pick a clip length from one to five minutes and turn audio on or off before choosing **Download**. The app copies compatible 720p H.264 video and optional AAC audio into an MP4 without re-encoding either track. It prefers 30 fps when available.
- **Download progress.** Follow source lookup, copying, and saving. The app selects and previews each finished clip. If multiple wallpapers are selected, the new clip joins them. YouTube clips have a size limit of 32 MiB per selected minute. Local MP4 imports keep the two-minute limit.
- **Wallpaper library.** Browse the 12 newest clips with your TV remote. Select a thumbnail to set it as the background or delete the app’s saved copy.
- **Multiple wallpapers.** Open **Select wallpapers**, check the clips you want, and choose **Apply selection**. Projectivy receives the individual videos and controls when it switches between them.
- **Local storage.** Clips and the library stay on the device. YouTube requests go from the TV to YouTube and its media hosts. The app uses no developer backend, analytics, or account credentials.

## Install on Android TV

You need Android TV or Google TV running Android 7.0 or newer, plus Projectivy Launcher with Premium enabled for external wallpaper providers. [Projectivy plugin guide](https://projectivylauncher.com/plugins.html), [Projectivy’s Overflight listing](https://play.google.com/store/apps/details?id=tv.projectivy.plugin.wallpaperprovider.overflight)

Download the signed APK from [Releases](#releases), then sideload it with Android Debug Bridge.

1. Install [Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools) on your computer.
2. Enable debugging on the TV and connect using the options shown by the device. Approve the computer’s debugging prompt on the TV.
3. Confirm that `adb devices` lists the TV, then install the downloaded APK. Use `-s <device-serial>` when more than one device is connected.

```sh
adb devices
adb install -r video-wallpaper-v0.6.apk
```

Android requires the same signing key for an in-place update. If you installed a debug build, back up any clips you want to keep before removing it to install the signed release. [Android app signing](https://developer.android.com/studio/publish/app-signing)

Open **Video Wallpaper for Projectivy Launcher**, add a clip, then choose the provider in **Projectivy Settings → Appearance → Wallpaper → Launcher wallpaper**. Set the video wallpaper volume in Projectivy to hear the clip. [Projectivy release notes](https://github.com/spocky/miproja1/releases/tag/4.60)

## Downloads and content rights

Downloads start only when you choose **Download**. A link shared to the app fills in the input field. The app supports public and unlisted prerecorded videos when compatible streams are available. Sign-in, access restrictions, and unavailable formats can prevent a download. If YouTube declines a media request or the video is unsupported, use a compatible local MP4 you have permission to use.

See [Disclaimer](DISCLAIMER.md) for content rights, platform terms, and hosting policies.

## Build and test

Build on macOS or Linux with JDK 17, Python 3.11, Bash, Make, a host C compiler, Android SDK Platform 35, Build Tools 35.0.0, and Android NDK 30.0.16248370. Configure the SDK through Android Studio, `ANDROID_HOME`, or a local `local.properties` file. To use a standalone installation of that NDK, set `PROJECTIVY_ANDROID_NDK` to its directory.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The tests cover YouTube link parsing and wallpaper history, including capacity, replacement, selection, and deletion. [GitHub Actions](.github/workflows/android.yml) runs the tests and lint and assembles an unsigned release APK. The Gradle distribution, yt-dlp zipapp, and QuickJS source archive have pinned checksums.

## License

Original code and artwork are licensed under [GPL-3.0-only](LICENSE). The vendored Projectivy provider API remains under [Apache-2.0](licenses/projectivy-api.txt). Dependency licenses and attribution are listed in [Third-party licenses](THIRD_PARTY_LICENSES.md).
