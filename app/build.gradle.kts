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
        versionCode = 1603
        versionName = "1.6.3"
    }

    sourceSets {
        named("main") {
            kotlin.directories += "src/main/kotlin"
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
