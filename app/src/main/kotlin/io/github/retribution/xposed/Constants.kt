package io.github.retribution.xposed

object RetributionConstants {
    const val TARGET_PACKAGE = "com.discord"
    const val TARGET_ACTIVITY = "$TARGET_PACKAGE.react_activities.ReactActivity"

    // @TODO: Migration to Retribution named dir
    const val FILES_DIR = "files/pyoncord"
    const val CACHE_DIR = "cache/Retribution"
    const val MAIN_SCRIPT_FILE = "bundle.js"
    const val PRELOADS_DIR = "preloads"

    const val LOADER_NAME = "RetributionXposed"
    val LOADER_VERSION
        get() = BuildConfig.VERSION_NAME
    val USER_AGENT
        get() = "RetributionXposed/$LOADER_VERSION"

    /**
     * Fallback Hermes/JS bundle shipped inside this APK's `assets/` directory, variant-aware.
     * Loaded by [io.github.retribution.xposed.tweaks.RetributionScriptLoader] when the cached `bundle.js` isn't available.
     */
    fun fallbackBundleAsset(variant: String) = when (variant) {
        "new" -> "assets://retribution-new.bundle"
        else -> "assets://retribution-old.bundle"
    }

    /** Public shared folder where the Manager pre-caches bundle variants. */
    const val SHARED_BUNDLE_DIR = "Android/media/app.retribution.manager/Retribution"

    /** Staged update file; swapped to [MAIN_SCRIPT_FILE] on the next start. */
    const val STAGED_SCRIPT_FILE = "bundle.js.new"
}

/**
 * Hermes bytecode assets shipped inside the Xposed module APK.
 */
val scriptAssets = emptyList<String>()
