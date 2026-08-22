import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.retribution.xposed"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.retribution.xposed"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1700
        versionName = "1.7.0"
    }

    sourceSets {
        named("main") {
            kotlin.directories += "src/main/kotlin"
            assets.srcDirs(layout.buildDirectory.dir("generated/retribution/assets"))
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
        }
        release {
            isDebuggable = false
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.javaVersion.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.javaVersion.get())
    }

    kotlin {
        jvmToolchain(libs.versions.javaVersion.get().toInt())
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        // Logger wraps android.util.Log; return default values so unit tests can run.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    compileOnly(libs.xposed.api)

    implementation(project(":api"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)

    testImplementation(libs.kotlin.test)
}

configurations.configureEach {
    if (name == "compileClasspath" || name == "runtimeClasspath"
            || name.endsWith("CompileClasspath") || name.endsWith("RuntimeClasspath")) {
        resolutionStrategy.activateDependencyLocking()
    }
}

// Download the latest Retribution bundle variants so the module always has a fallback asset.
tasks.register<Task>("downloadBundleAssets") {
    val baseUrl = providers.gradleProperty("bundleBaseUrl")
        .orElse("https://github.com/Retribution-Mod/retribution-bundle/releases/latest/download")

    inputs.property("bundleBaseUrl", baseUrl)

    val outDir = layout.buildDirectory.dir("generated/retribution/assets")
    outputs.dir(outDir)

    doLast {
        val outputDir = outDir.get().asFile.apply { mkdirs() }
        val pairs = listOf(
            "retribution-new.min.js" to "retribution-new.bundle",
            "retribution-old.min.js" to "retribution-old.bundle",
        )

        for ((remoteName, assetName) in pairs) {
            val dest = File(outputDir, assetName)
            if (dest.exists() && dest.length() > 0 && !project.hasProperty("forceDownloadBundle")) {
                continue
            }

            val url = "${baseUrl.get()}/$remoteName"
            val connection = URI.create(url).toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "RetributionXposed-Build")

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.use { input ->
                    dest.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } else {
                if (!dest.exists()) {
                    throw IOException("Failed to download $url: ${connection.responseCode}")
                }
            }
        }
    }
}

// Make sure the fallback assets are always present before assets are merged or packaged,
// whether the build is an APK (assemble), app bundle, or universal APK (CI workflow).
val assetPackagingTasks = listOf(
    "mergeReleaseAssets", "mergeDebugAssets",
    "buildReleasePreBundle", "buildDebugPreBundle",
    "packageReleaseBundle", "packageDebugBundle",
    "packageReleaseUniversalApk", "packageDebugUniversalApk",
    "assembleRelease", "assembleDebug",
)

tasks.matching { it.name in assetPackagingTasks }.configureEach {
    dependsOn("downloadBundleAssets")
}

afterEvaluate {
    tasks.configureEach {
        if (name.contains("lint", ignoreCase = true) || name.contains("Lint")) {
            dependsOn("downloadBundleAssets")
        }
    }
}
