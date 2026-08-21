package io.github.retribution.xposed.tweaks

import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import android.widget.Toast
import androidx.core.util.writeBytes
import io.github.retribution.logger
import io.github.retribution.xposed.RetributionJson
import io.github.retribution.plugins.Version
import io.github.retribution.xposed.*
import io.github.retribution.xposed.tweaks.base.withAppActivity
import io.github.retribution.xposed.tweaks.base.withAppContext
import io.github.retribution.xposed.tweaks.plugins.internal.DISCORD_VERSION
import io.github.retribution.xposed.tweaks.plugins.internal.isDiscordVersionSet
import io.github.retribution.xposed.tweaks.plugins.internal.showRecoveryAlert
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import java.io.File
import kotlin.time.Duration.Companion.seconds

@Serializable
data class CustomLoadUrl(
    val enabled: Boolean = false,
    val url: String = "",
)

@Serializable
data class LoaderConfig(
    val customLoadUrl: CustomLoadUrl = CustomLoadUrl(),
)

/**
 * Updater for the JS bundle.
 *
 * Handles configuration, downloading, caching, and user-facing retry/recovery dialogs.
 * The actual loading of the bundle is handled by [RetributionScriptLoader].
 */
object RetributionUpdater {
    internal val TIMEOUT = 30.seconds
    private val TIMEOUT_CACHED = 15.seconds
    private const val ETAG_PATH = "etag.txt"
    private const val VARIANT_PATH = "variant.txt"
    private const val CONFIG_PATH = "loader.json"
    private val NEW_VERSION_THRESHOLD = Version.parse("341.0.0")

    private const val BASE_BUNDLE_URL =
        "https://github.com/Retribution-Mod/retribution-bundle/releases/latest/download"
    private const val BASE_NEXT_BUNDLE_URL =
        "https://github.com/Retribution-Mod/retribution-bundle-next/releases/latest/download"

    private val log = logger("RetributionUpdater")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var config = LoaderConfig()
    private lateinit var bundle: File
    private lateinit var etag: File
    private lateinit var variantFile: File
    private lateinit var configFile: File
    private lateinit var packageName: String

    internal fun init(dataDir: String, pkg: String = "") {
        packageName = pkg
        val cacheDir = File(dataDir, RetributionConstants.CACHE_DIR).apply { mkdirs() }
        val filesDir = File(dataDir, RetributionConstants.FILES_DIR).apply { mkdirs() }

        bundle = File(cacheDir, RetributionConstants.MAIN_SCRIPT_FILE)
        etag = File(cacheDir, ETAG_PATH)
        variantFile = File(cacheDir, VARIANT_PATH)
        configFile = File(filesDir, CONFIG_PATH)

        config = runCatching {
            if (configFile.exists()) {
                val loadedConfig = RetributionJson.decodeFromString<LoaderConfig>(configFile.readText())
                // Security: Validate custom URL on load to prevent use of persisted malicious URLs
                if (loadedConfig.customLoadUrl.enabled) {
                    val customUrl = loadedConfig.customLoadUrl.url
                    if (!isValidBundleUrl(customUrl)) {
                        log.w("Loaded config contains invalid custom URL, resetting: $customUrl")
                        configFile.delete()
                        LoaderConfig()
                    } else {
                        loadedConfig
                    }
                } else {
                    loadedConfig
                }
            } else {
                LoaderConfig()
            }
        }.getOrDefault(LoaderConfig())
    }

    fun resetLoaderConfig() {
        if (::configFile.isInitialized && configFile.exists()) configFile.delete()
    }

    /**
     * Picks the bundle variant:
     * - Next package names use retribution-bundle-next.
     * - Versions >= 341.0.0 use the new (RN 0.86+) classic bundle.
     * - Everything else uses the old classic bundle.
     */
    private fun bundleVariant(version: Version?): String = when {
        ::packageName.isInitialized && packageName.contains("next", ignoreCase = true) -> "next"
        version != null && version >= NEW_VERSION_THRESHOLD -> "new"
        else -> "old"
    }

    internal fun bundleUrl(version: Version?): String = when (bundleVariant(version)) {
        "next" -> "$BASE_NEXT_BUNDLE_URL/retribution.min.js"
        "new" -> "$BASE_BUNDLE_URL/retribution-new.min.js"
        else -> "$BASE_BUNDLE_URL/retribution-old.min.js"
    }

    private fun selectBundleVariant(variant: String) {
        if (variantFile.takeIf(File::exists)?.readText() == variant) return
        bundle.delete()
        etag.delete()
        variantFile.writeText(variant)
    }

    fun applyBundleUrl(url: String) {
        // Security: Validate URL before persisting
        if (!isValidBundleUrl(url)) {
            log.e("Rejected invalid bundle URL: $url")
            throw SecurityException("Bundle URL validation failed: URL must be from a trusted source")
        }

        log.i("Applying custom bundle URL: $url")
        val newConfig = LoaderConfig(customLoadUrl = CustomLoadUrl(enabled = true, url = url))
        if (::configFile.isInitialized) {
            config = newConfig
            configFile.writeText(RetributionJson.encodeToString(newConfig))
        } else {
            config = newConfig
        }
    }

    /**
     * Validates that a bundle URL is from a trusted source.
     * This provides defense-in-depth against malicious bundle URLs.
     */
    private fun isValidBundleUrl(url: String): Boolean {
        // Allow official Retribution bundle URLs from GitHub
        val allowedPrefixes = listOf(
            "https://github.com/Retribution-Mod/retribution-bundle/releases/",
            "https://github.com/Retribution-Mod/retribution-bundle-next/releases/",
            "https://raw.githubusercontent.com/Retribution-Mod/retribution-bundle/",
            "https://raw.githubusercontent.com/Retribution-Mod/retribution-bundle-next/"
        )

        return allowedPrefixes.any { url.startsWith(it, ignoreCase = true) }
    }

    /**
     * Trigger a download. If [userInitiated] is true (retry from the error dialog), the timeout
     * is disabled and a success dialog is shown on the next available activity.
     * For automatic downloads, the cached bundle is loaded immediately and a reload prompt is shown
     * when the new bundle finishes downloading in the background.
     */
    fun downloadScript(userInitiated: Boolean = false): Job = scope.launch {
        try {
            val version = withTimeoutOrNull(2.seconds) {
                while (!isDiscordVersionSet()) delay(50)
                DISCORD_VERSION
            }
            val customUrl = config.customLoadUrl.takeIf { it.enabled }?.url
            val variant = customUrl?.let { "custom:$it" } ?: bundleVariant(version)
            selectBundleVariant(variant)
            val url = customUrl ?: bundleUrl(version)
            log.i("Fetching $variant JS bundle from: $url")

            val result = httpClient.getWithETag(
                url = url,
                etag = if (etag.exists() && bundle.exists()) etag.readText() else null,
                timeoutMillis = if (userInitiated) null
                else if (bundle.exists()) TIMEOUT_CACHED.inWholeMilliseconds else TIMEOUT.inWholeMilliseconds,
            )

            when (result) {
                is ETagFetchResult.Fetched -> {
                    AtomicFile(bundle).writeBytes(result.bytes)

                    result.etag?.let(etag::writeText) ?: etag.delete()

                    log.i("Bundle updated (${result.bytes.size} bytes)")
                    if (userInitiated) showSuccessDialog() else showUpdateDialog()
                }

                ETagFetchResult.NotModified -> log.i("Server responded with 304, no changes")
            }
        } catch (e: Throwable) {
            log.e("Failed to download script", e)
            if (userInitiated) showErrorDialog(e)
        }
    }

    private fun showUpdateDialog() = withAppContext { ctx ->
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(ctx, "Retribution update downloaded. Restart Discord to apply.", Toast.LENGTH_LONG).show()
        }
    }

    private fun showSuccessDialog() = withAppContext { ctx ->
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(ctx, "Retribution update downloaded. Restart Discord to apply.", Toast.LENGTH_LONG).show()
        }
    }

    private fun showErrorDialog(e: Throwable) = withAppActivity { activity ->
        activity.runOnUiThread {
            AlertDialog.Builder(activity)
                .setTitle("Retribution Update Failed")
                .setMessage(
                    """
                    Unable to download the latest version of Retribution.
                    This is usually caused by bad network connection.

                    Error: ${e.message ?: e.stackTraceToString()}
                    """.trimIndent()
                )
                .setNegativeButton("Dismiss") { d, _ -> d.dismiss() }
                .setPositiveButton("Retry Update") { d, _ ->
                    downloadScript(userInitiated = true)
                    Toast.makeText(activity, "Retrying download in background...", Toast.LENGTH_SHORT).show()
                    d.dismiss()
                }
                .setNeutralButton("Recovery") { d, _ -> showRecoveryAlert(activity); d.dismiss() }
                .show()
        }
    }
}

/**
 * Wires [RetributionUpdater] into the lifecycle. Loads the loader config once the target [android.content.Context]
 * is available, then kicks off the first download.
 */
val retributionUpdaterTweak by tweak {
    withAppContext { ctx ->
        RetributionUpdater.init(ctx.dataDir.absolutePath, ctx.packageName)
        RetributionUpdater.downloadScript(userInitiated = false)
    }
}
