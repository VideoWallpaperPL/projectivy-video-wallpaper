import java.net.URI
import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application") version "8.9.2"
    id("org.jetbrains.kotlin.android") version "2.1.20"
    id("org.jetbrains.kotlin.plugin.parcelize") version "2.1.20"
}

val ytDlpVersion = "2026.10.26"
val prepareYtDlp by tasks.registering(PrepareYtDlp::class) {
    version.set(ytDlpVersion)
    sha256.set("e51e69e89489f199530f13d32a4342da3ddb2cf34842f364603a066db05afbf3")
    outputDirectory.set(layout.buildDirectory.dir("generated/yt-dlp-assets/$ytDlpVersion"))
}

abstract class PrepareYtDlp : DefaultTask() {
    @get:Input
    abstract val version: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        val release = version.get()
        val connection = URI("https://github.com/yt-dlp/yt-dlp/releases/download/$release/yt-dlp")
            .toURL().openConnection()
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        val bytes = connection.getInputStream().use { it.readBytes() }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        check(digest == sha256.get()) { "yt-dlp $release checksum mismatch" }
        outputDirectory.file("yt-dlp-$release").get().asFile.apply {
            parentFile.mkdirs()
            writeBytes(bytes)
        }
    }
}

android {
    namespace = "io.projectyt.spike"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.projectyt.spike"
        minSdk = 24
        targetSdk = 35
        versionCode = 5
        versionName = "0.5"
        buildConfigField("String", "YT_DLP_VERSION", "\"$ytDlpVersion\"")
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

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(prepareYtDlp, PrepareYtDlp::outputDirectory)
    }
}

kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.core:core:1.8.0")
    implementation("androidx.media3:media3-transformer:1.8.0")
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    testImplementation("junit:junit:4.13.2")
}
