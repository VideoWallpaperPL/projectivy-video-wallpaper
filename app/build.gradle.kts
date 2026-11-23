import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import javax.inject.Inject
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application") version "8.9.2"
    id("org.jetbrains.kotlin.android") version "2.1.20"
    id("org.jetbrains.kotlin.plugin.parcelize") version "2.1.20"
    id("com.chaquo.python") version "17.2.0"
}

val ytDlpVersion = "2026.10.26"
val quickJsVersion = "2026-06-04"
val releaseAbis = listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
val prepareYtDlp by tasks.registering(PinnedDownload::class) {
    url.set("https://github.com/yt-dlp/yt-dlp/releases/download/$ytDlpVersion/yt-dlp")
    sha256.set("e51e69e89489f199530f13d32a4342da3ddb2cf34842f364603a066db05afbf3")
    fileName.set("yt-dlp-$ytDlpVersion")
    outputDirectory.set(layout.buildDirectory.dir("generated/yt-dlp-assets/$ytDlpVersion"))
}
val prepareQuickJs by tasks.registering(PinnedDownload::class) {
    url.set("https://bellard.org/quickjs/quickjs-$quickJsVersion.tar.xz")
    sha256.set("b376e839b322978313d929fd20663b11ba58b75df5a46c126dd19ea2fa70ad2a")
    fileName.set("quickjs-$quickJsVersion.tar.xz")
    outputDirectory.set(layout.buildDirectory.dir("runtime/quickjs-source"))
}

abstract class PinnedDownload : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:Input
    abstract val fileName: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        val connection = URI(url.get()).toURL().openConnection()
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        val bytes = connection.getInputStream().use { it.readBytes() }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        check(digest == sha256.get()) { "${fileName.get()} checksum mismatch" }
        val directory = outputDirectory.get().asFile
        directory.deleteRecursively()
        directory.mkdirs()
        directory.resolve(fileName.get()).writeBytes(bytes)
    }
}

abstract class BuildQuickJs @Inject constructor(
    private val execOperations: org.gradle.process.ExecOperations,
) : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourceArchive: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val script: RegularFileProperty

    @get:Internal
    abstract val ndk: DirectoryProperty

    @get:Input
    abstract val ndkVersion: Property<String>

    @get:Input
    abstract val abis: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun compile() {
        val ndkRoot = ndk.get().asFile
        val revision = Properties().apply {
            ndkRoot.resolve("source.properties").inputStream().use(::load)
        }.getProperty("Pkg.Revision")
        check(revision == ndkVersion.get()) { "Use Android NDK ${ndkVersion.get()}, found $revision" }
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        execOperations.exec {
            commandLine(
                listOf(
                    "bash", script.get().asFile,
                    sourceArchive.get().asFile.listFiles()!!.single(),
                    ndkRoot, temporaryDir, output,
                ) + abis.get()
            )
        }
    }
}

android {
    namespace = "io.projectyt.spike"
    compileSdk = 35
    ndkVersion = "30.0.16248370"
    ndkPath = providers.environmentVariable("ANDROID_NDK_HOME").orNull

    defaultConfig {
        applicationId = "io.projectyt.spike"
        minSdk = 24
        targetSdk = 35
        versionCode = 6
        versionName = "0.6"
        buildConfigField("String", "YT_DLP_VERSION", "\"$ytDlpVersion\"")
        ndk { abiFilters += releaseAbis }
    }
    buildFeatures {
        aidl = true
        buildConfig = true
    }
    sourceSets.getByName("main").assets.srcDir(rootProject.file("licenses"))
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        jniLibs.useLegacyPackaging = true
        resources.excludes += "META-INF/DEPENDENCIES"
    }
}

chaquopy {
    defaultConfig {
        version = "3.11"
    }
}

androidComponents {
    val compileQuickJs by tasks.registering(BuildQuickJs::class) {
        sourceArchive.set(prepareQuickJs.flatMap { it.outputDirectory })
        script.set(rootProject.layout.projectDirectory.file("tools/build-quickjs.sh"))
        ndk.set(sdkComponents.ndkDirectory)
        ndkVersion.set("30.0.16248370")
        abis.set(releaseAbis)
        outputDirectory.set(layout.buildDirectory.dir("generated/quickjs-jni"))
    }
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(prepareYtDlp, PinnedDownload::outputDirectory)
        variant.sources.jniLibs?.addGeneratedSourceDirectory(compileQuickJs, BuildQuickJs::outputDirectory)
    }
}

kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.core:core:1.8.0")
    implementation("androidx.media3:media3-transformer:1.8.0")
    testImplementation("junit:junit:4.13.2")
}
