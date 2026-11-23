# Third-party licenses

Original project code and artwork use GPL-3.0-only. The vendored Projectivy provider API retains its Apache-2.0 license. Apache-2.0 code can be incorporated into a GPLv3 project while retaining its notices. [Apache Software Foundation compatibility guidance](https://www.apache.org/licenses/GPL-compatibility.html)

## App dependencies

| Component | License and source |
| --- | --- |
| Projectivy wallpaper provider API v1 | Apache-2.0. The Kotlin and AIDL contract files are from the [Projectivy template at `60ec7b4`](https://github.com/spocky/projectivy-plugin-wallpaper-provider/tree/60ec7b4306ab16dac83ea10ed9b81f5670b51ee3). See the [retained license text](licenses/projectivy-api.txt). |
| yt-dlp 2026.10.26 | Unlicense for the core. The [upstream release](https://github.com/yt-dlp/yt-dlp/releases/tag/2026.10.26) provides the zipapp and the [matching source](https://github.com/yt-dlp/yt-dlp/tree/2026.10.26). The app verifies the zipapp’s SHA-256 at build time. See the [retained license text](licenses/yt-dlp.txt). |
| yt-dlp-ejs 0.8.0 | Unlicense. The solver bundle in the zipapp includes MIT-licensed astring and ISC-licensed Meriyah. See the [solver source](https://github.com/yt-dlp/ejs/tree/0.8.0) and [retained upstream notices](licenses/upstream/). |
| QuickJS 2026-06-04 | MIT. The app compiles the command-line tool for each supported Android ABI at build time. See the [upstream source archive](https://bellard.org/quickjs/quickjs-2026-06-04.tar.xz), its pinned digest in the [Gradle build](app/build.gradle.kts), the [compiler script](tools/build-quickjs.sh), and the [license text](licenses/upstream/quickjs.txt). |
| Chaquopy 17.2.0 | MIT. Provides the Java/Python bridge and runtime assets. See the [exact release source and build instructions](https://github.com/chaquo/chaquopy/tree/1f63c93d8beaf7062fbe51888ab9235475df3bb8/product), [Maven release](https://repo.maven.apache.org/maven2/com/chaquo/python/gradle/17.2.0/), and [retained license](licenses/upstream/chaquopy-17.2.0.txt). |
| AndroidX, Media3 1.8.0, Kotlin 2.1.20, JetBrains annotations, and Guava 33.3.1-android | Apache-2.0. See the [resolved Maven runtime coordinates](licenses/maven-runtime.tsv), [Maven notice index](licenses/maven-notice-index.tsv), and [Guava license](licenses/upstream/guava-33.3.1.txt). |
| JUnit 4.13.2 and Hamcrest Core 1.3 | Test dependencies. JUnit uses [EPL-1.0](https://github.com/junit-team/junit4/tree/r4.13.2) and Hamcrest Core uses [BSD-3-Clause](https://hamcrest.org/JavaHamcrest/license). |

## Python runtime and build inputs

Chaquopy resolves [`com.chaquo.python:target:3.11.17-0`](https://repo.maven.apache.org/maven2/com/chaquo/python/target/3.11.17-0/). The publisher provides the [Python build and packaging instructions](https://github.com/chaquo/chaquopy/tree/75197ed5c8280c38881af0e03fede591f1abc11e/target), [CPython recipe](https://github.com/chaquo/chaquopy/blob/75197ed5c8280c38881af0e03fede591f1abc11e/target/python/build.sh), and [patches](https://github.com/chaquo/chaquopy/tree/75197ed5c8280c38881af0e03fede591f1abc11e/target/python/patches) at the release that introduced this artifact. [APK native runtime inventory](licenses/native-runtime.tsv), [Maven runtime artifact checksums](licenses/python-artifacts.tsv)

| Component | License and source |
| --- | --- |
| CPython 3.11.17 | PSF License Agreement and bundled notices. [CPython release source](https://github.com/python/cpython/tree/v3.11.17), [retained license](licenses/upstream/cpython-3.11.17.txt) |
| OpenSSL 3.5.9 | Apache-2.0. [Source](https://github.com/openssl/openssl/tree/openssl-3.5.9), [Android recipe](https://github.com/beeware/cpython-android-source-deps/tree/openssl-3.5.9-0), [retained license](licenses/upstream/openssl-3.5.9.txt) |
| SQLite 3.53.4 | Public domain. [Copyright and source](https://sqlite.org/copyright.html), [Android recipe](https://github.com/beeware/cpython-android-source-deps/tree/sqlite-3.53.4-0) |
| bzip2 1.0.8 | bzip2 license. [Source archive](https://sourceware.org/pub/bzip2/bzip2-1.0.8.tar.gz), [Android recipe](https://github.com/beeware/cpython-android-source-deps/tree/bzip2-1.0.8-3), [retained license](licenses/upstream/bzip2-1.0.8.txt) |
| libffi 3.4.4 | MIT. [Source](https://github.com/libffi/libffi/tree/v3.4.4), [Android recipe](https://github.com/beeware/cpython-android-source-deps/tree/libffi-3.4.4-3), [retained license](licenses/upstream/libffi-3.4.4.txt) |
| XZ Utils 5.4.6 liblzma | Public domain. [Source](https://github.com/tukaani-project/xz/tree/v5.4.6), [Android recipe](https://github.com/beeware/cpython-android-source-deps/tree/xz-5.4.6-1), [retained notices](licenses/upstream/xz-5.4.6.txt) |
| Certifi 2026.10.23 certificate bundle | MPL-2.0. [Published source](https://github.com/certifi/python-certifi/tree/2026.10.23), [retained notice](licenses/upstream/certifi-2026.10.23.txt), [MPL-2.0 text](licenses/upstream/MPL-2.0.txt) |

The release APK includes the `licenses/` directory as assets, including the project’s GPLv3 text. [Build and test](README.md#build-and-test) describes how to rebuild the tagged source. [GitHub Releases](https://github.com/VideoWallpaperPL/projectivy-video-wallpaper/releases) provides the tagged source and signed APK.
