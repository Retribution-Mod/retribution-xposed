package io.github.retribution.xposed.tweaks

import android.app.AlertDialog
import android.content.Context
import android.os.Environment
import android.util.AtomicFile
import android.widget.Toast
import androidx.core.util.writeBytes
import android.content.res.XModuleResources
import io.github.retribution.logger
import io.github.retribution.reloadApp
import io.github.retribution.xposed.RetributionJson
import io.github.retribution.plugins.Version
import io.github.retribution.xposed.*
import io.github.retribution.xposed.tweaks.base.withAppActivity
import io.github.retribution.xposed.tweaks.plugins.internal.DISCORD_VERSION
import io.github.retribution.xposed.tweaks.plugins.internal.isDiscordVersionSet
import io.github.retribution.xposed.tweaks.plugins.internal.showRecoveryAlert
import kotlinx.coroutines.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest
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

@Serializable
data class BundleEntry(
    val filename: String,
    val size: Long,
    val sha256: String,
    val etag: String? = null,
)

@Serializable
data class SharedManifest(
    val version: String,
    val new: BundleEntry,
    val old: BundleEntry,
)

@Serializable
data class BundleManifest(
    val version: String,
    val variant: String,
    val size: Long,
    val sha256: String,
    val etag: String? = null,
)

/**
 * Compares bundle version tags such as "v0.2.1", "0.2.0", or "v0.0.1.16".
 * Returns a positive number if [a] is newer, negative if [b] is newer, 0 if equal.
 */
@Serializable
private data class Release(
    @SerialName("tag_name") val tagName: String,
)

fun compareBundleVersions(a: String, b: String): Int {
    val pa = a.trimStart('v', 'V').split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
    val pb = b.trimStart('v', 'V').split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
    val max = maxOf(pa.size, pb.size)
    for (i in 0 until max) {
        val va = pa.getOrElse(i) { 0 }
        val vb = pb.getOrElse(i) { 0 }
        if (va != vb) return va - vb
    }
    return 0
}

/**
 * Updater for the JS bundle.
 *
 * Handles configuration, pre-seeding from a public shared cache or fallback assets, staged background
 * updates, and user-facing retry/recovery dialogs. The actual loading of the bundle is handled by
 * [RetributionScriptLoader].
 */
object RetributionUpdater {
    private val DOWNLOAD_TIMEOUT = 30.seconds
    private const val ETAG_PATH = "etag.txt"
    private const val VARIANT_PATH = "variant.txt"
    private const val CONFIG_PATH = "loader.json"
    private val NEW_VERSION_THRESHOLD = Version.parse("341.0.0")

    private const val BASE_BUNDLE_URL =
        "https://github.com/Retribution-Mod/retribution-bundle/releases/latest/download"
    private const val BASE_NEXT_BUNDLE_URL =
        "https://github.com/Retribution-Mod/retribution-bundle-next/releases/latest/download"

    private val log = logger("RetributionUpdater")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var config = LoaderConfig()
    private lateinit var bundle: File
    private lateinit var stagedBundle: File
    private lateinit var manifestFile: File
    private lateinit var stagedManifest: File
    private lateinit var etag: File
    private lateinit var variantFile: File
    private lateinit var configFile: File
    private lateinit var packageName: String
    private var appContext: Context? = null
    private var modulePath: String? = null

    private val _preSeedReady = CompletableDeferred<Unit>()

    /**
     * Completes once the bundle cache has been pre-seeded (from public Manager cache, fallback asset,
     * or failed gracefully). [RetributionScriptLoader] joins on this before loading the bundle.
     */
    val preSeedReady: Deferred<Unit> = _preSeedReady

    internal fun init(dataDir: String, pkg: String = "", modPath: String = "", context: Context? = null) {
        packageName = pkg
        modulePath = modPath
        appContext = context
        val cacheDir = File(dataDir, RetributionConstants.CACHE_DIR).apply { mkdirs() }
        val filesDir = File(dataDir, RetributionConstants.FILES_DIR).apply { mkdirs() }

        bundle = File(cacheDir, RetributionConstants.MAIN_SCRIPT_FILE)
        stagedBundle = File(cacheDir, RetributionConstants.STAGED_SCRIPT_FILE)
        manifestFile = File(cacheDir, "bundle.manifest")
        stagedManifest = File(cacheDir, "bundle.js.new.manifest")
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

    private fun bundleRepoFromUrl(url: String): String? = when {
        url.contains("/retribution-bundle-next/releases/") -> "Retribution-Mod/retribution-bundle-next"
        url.contains("/retribution-bundle/releases/") -> "Retribution-Mod/retribution-bundle"
        else -> null
    }

    private fun isBundleVersion(version: String?): Boolean {
        if (version.isNullOrBlank()) return false
        val start = version.trimStart('v', 'V')
        return start.firstOrNull()?.isDigit() == true
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
     *
     * In addition to the official Retribution bundle URLs, this private build also allows
     * bundle releases from the owner's private GitHub repositories.
     */
    private fun isValidBundleUrl(url: String): Boolean {
        // Debug builds are allowed to load bundles from localhost for development testing.
        if (BuildConfig.DEBUG && (url.startsWith("http://localhost") || url.startsWith("https://localhost"))) return true

        return runCatching {
            val parsed = java.net.URL(url)
            if (parsed.protocol != "https") return@runCatching false

            val host = parsed.host.lowercase()
            val path = parsed.path.lowercase()

            when (host) {
                "github.com" -> {
                    path.startsWith("/retribution-mod/retribution-bundle/releases/") ||
                    path.startsWith("/retribution-mod/retribution-bundle-next/releases/") ||
                    path.startsWith("/everestmcarthur/retribution-bundle-private/releases/")
                }
                "raw.githubusercontent.com" -> {
                    path.startsWith("/retribution-mod/retribution-bundle/") ||
                    path.startsWith("/retribution-mod/retribution-bundle-next/") ||
                    path.startsWith("/everestmcarthur/retribution-bundle-private/")
                }
                else -> false
            }
        }.getOrDefault(false)
    }

    /**
     * Pre-seed the bundle cache before React loads. Copies from the Manager's public shared cache if
     * available and valid, otherwise falls back to the bundle asset shipped in the Xposed module.
     * Also applies any staged update from a previous run.
     */
    suspend fun preSeedCache() {
        try {
            val version = withTimeoutOrNull(5.seconds) {
                while (!isDiscordVersionSet()) delay(50)
                DISCORD_VERSION
            }
            val variant = bundleVariant(version)

            // First, apply a staged update from a previous run if it matches the current variant.
            if (applyStagedUpdate(variant)) {
                log.i("Applied staged bundle update ($variant)")
            } else {
                // Then prefer a public shared cache from the Manager.
                if (copyFromPublicCache(variant)) {
                    log.i("Pre-seeded bundle from Manager shared cache ($variant)")
                } else {
                    // Fall back to the asset bundled inside the module.
                    copyFallbackAsset(variant)
                    log.i("Pre-seeded bundle from fallback asset ($variant)")
                }
            }
        } catch (e: Throwable) {
            log.e("Failed to pre-seed bundle; trying fallback asset", e)
            runCatching {
                copyFallbackAsset(bundleVariant(null))
            }.onFailure { log.e("Fallback asset copy also failed", it) }
        } finally {
            _preSeedReady.complete(Unit)
        }
    }

    /**
     * Check for a bundle update and, if one is found, stage it to a separate file. It is applied on
     * the next app restart. This does not show any dialogs by default.
     */
    fun downloadUpdate(showDialog: Boolean = false, userInitiated: Boolean = false): Job = scope.launch {
        try {
            val version = withTimeoutOrNull(2.seconds) {
                while (!isDiscordVersionSet()) delay(50)
                DISCORD_VERSION
            }
            val customUrl = config.customLoadUrl.takeIf { it.enabled }?.url
            val variant = customUrl?.let { "custom:$it" } ?: bundleVariant(version)
            val url = customUrl ?: bundleUrl(version)
            log.i("Checking for $variant JS bundle update at: $url")

            val currentVersion = runCatching {
                if (manifestFile.isFile) RetributionJson.decodeFromString<BundleManifest>(manifestFile.readText()).version else null
            }.getOrNull() ?: ""

            val repo = if (customUrl == null) bundleRepoFromUrl(url) else null
            val latestVersion = if (customUrl == null && repo != null) httpClient.getLatestReleaseTag(repo) else null

            latestVersion?.let { latest ->
                if (isBundleVersion(currentVersion) && isBundleVersion(latest)) {
                    val cmp = compareBundleVersions(latest, currentVersion)
                    if (cmp <= 0) {
                        log.i("Bundle is up-to-date (current $currentVersion >= latest $latest)")
                        return@launch
                    }
                }
            }

            val currentEtag = if (etag.isFile) etag.readText() else null
            val result = httpClient.getWithETag(
                url = url,
                etag = currentEtag,
                timeoutMillis = if (userInitiated) null else DOWNLOAD_TIMEOUT.inWholeMilliseconds,
            )

            when (result) {
                is ETagFetchResult.Fetched -> {
                    stagedBundle.parentFile?.mkdirs()
                    AtomicFile(stagedBundle).writeBytes(result.bytes)

                    val manifest = BundleManifest(
                        version = latestVersion ?: "?",
                        variant = variant,
                        size = result.bytes.size.toLong(),
                        sha256 = stagedBundle.sha256(),
                        etag = result.etag,
                    )
                    stagedManifest.writeText(RetributionJson.encodeToString(manifest))

                    log.i("Bundle update staged (${result.bytes.size} bytes); will apply on next restart")
                    if (showDialog && userInitiated) showSuccessDialog()
                }

                ETagFetchResult.NotModified -> log.i("Server responded with 304, no bundle update")
            }
        } catch (e: Throwable) {
            log.e("Failed to check for bundle update", e)
            if (showDialog && userInitiated) showErrorDialog(e)
        }
    }

    /**
     * Legacy entry point for user-initiated downloads (settings, retry, etc.).
     * This now uses [downloadUpdate] to stage the new bundle.
     */
    fun downloadScript(userInitiated: Boolean = false, showDialog: Boolean = true): Job =
        downloadUpdate(showDialog = showDialog, userInitiated = userInitiated)

    /**
     * Apply a staged update (bundle.js.new) to the live bundle file if it matches the requested [variant].
     * Returns true if a staged update was applied.
     */
    private fun applyStagedUpdate(variant: String): Boolean {
        if (!stagedBundle.isFile || !stagedManifest.isFile) return false

        return runCatching {
            val manifest = RetributionJson.decodeFromString<BundleManifest>(stagedManifest.readText())

            // Ignore staged bundles whose variant no longer matches the selected Discord version.
            if (manifest.variant != variant) {
                log.w("Discarding staged bundle: variant ${manifest.variant} != $variant")
                stagedBundle.delete()
                stagedManifest.delete()
                return@runCatching false
            }

            if (stagedBundle.length() != manifest.size || stagedBundle.sha256() != manifest.sha256) {
                throw IllegalStateException("Staged bundle hash/size mismatch")
            }

            if (!stagedBundle.renameTo(bundle)) {
                stagedBundle.copyTo(bundle, overwrite = true)
                stagedBundle.delete()
            }
            if (!stagedManifest.renameTo(manifestFile)) {
                stagedManifest.copyTo(manifestFile, overwrite = true)
                stagedManifest.delete()
            }

            manifest.etag?.let { etag.writeText(it) } ?: etag.delete()
            if (::variantFile.isInitialized) variantFile.writeText(manifest.variant)

            log.i("Applied staged bundle update: ${bundle.absolutePath}")
            true
        }.getOrElse {
            log.w("Failed to apply staged bundle", it)
            stagedBundle.delete()
            stagedManifest.delete()
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun copyFromPublicCache(variant: String): Boolean {
        return runCatching {
            val sharedDir = File(Environment.getExternalStorageDirectory(), RetributionConstants.SHARED_BUNDLE_DIR)
            val sharedManifestFile = File(sharedDir, "manifest.json")
            if (!sharedManifestFile.isFile) return@runCatching false

            val sharedManifest = RetributionJson.decodeFromString<SharedManifest>(sharedManifestFile.readText())
            val entry = when (variant) {
                "new", "next" -> sharedManifest.new
                else -> sharedManifest.old
            }

            val source = File(sharedDir, entry.filename)
            if (!source.isFile) return@runCatching false
            if (source.length() != entry.size || source.sha256() != entry.sha256) return@runCatching false

            val currentVersion = runCatching {
                if (manifestFile.isFile) RetributionJson.decodeFromString<BundleManifest>(manifestFile.readText()).version else null
            }.getOrNull() ?: ""

            if (isBundleVersion(currentVersion) && isBundleVersion(sharedManifest.version)) {
                val cmp = compareBundleVersions(sharedManifest.version, currentVersion)
                if (cmp <= 0) {
                    log.i("Public bundle cache ($sharedManifest.version) is not newer than current ($currentVersion), skipping copy")
                    return@runCatching true
                }
            }

            source.copyTo(bundle, overwrite = true)

            val manifest = BundleManifest(
                version = sharedManifest.version,
                variant = variant,
                size = entry.size,
                sha256 = entry.sha256,
                etag = entry.etag,
            )
            manifestFile.writeText(RetributionJson.encodeToString(manifest))

            entry.etag?.let { etag.writeText(it) } ?: etag.delete()
            if (::variantFile.isInitialized) variantFile.writeText(variant)

            true
        }.getOrDefault(false)
    }

    private fun copyFallbackAsset(variant: String) {
        val path = modulePath ?: throw IllegalStateException("Module path not initialized")
        val assetName = if (variant == "new") "retribution-new.bundle" else "retribution-old.bundle"

        val am = XModuleResources.createInstance(path, null).assets
        am.open(assetName).use { input ->
            bundle.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        val sha = bundle.sha256()
        val size = bundle.length()
        val manifest = BundleManifest(
            version = "fallback",
            variant = variant,
            size = size,
            sha256 = sha,
            etag = null,
        )
        manifestFile.writeText(RetributionJson.encodeToString(manifest))

        etag.delete()
        if (::variantFile.isInitialized) variantFile.writeText(variant)
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } > 0) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun showUpdateDialog() = withAppActivity { activity ->
        activity.runOnUiThread {
            AlertDialog.Builder(activity)
                .setTitle("Retribution Update Downloaded")
                .setMessage("A reload is required for changes to take effect.")
                .setPositiveButton("Reload") { d, _ -> reloadApp(); d.dismiss() }
                .setNegativeButton("Later") { d, _ -> d.dismiss() }
                .show()
        }
    }

    private fun showSuccessDialog() = withAppActivity { activity ->
        activity.runOnUiThread {
            AlertDialog.Builder(activity)
                .setTitle("Retribution Update Successful")
                .setMessage("A reload is required for changes to take effect.")
                .setPositiveButton("Reload") { d, _ -> reloadApp(); d.dismiss() }
                .setNegativeButton("Later") { d, _ -> d.dismiss() }
                .show()
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
                    downloadUpdate(userInitiated = true)
                    Toast.makeText(activity, "Retrying download in background...", Toast.LENGTH_SHORT).show()
                    d.dismiss()
                }
                .setNeutralButton("Recovery") { d, _ -> showRecoveryAlert(activity); d.dismiss() }
                .show()
        }
    }
}

/**
 * Wires [RetributionUpdater] into the lifecycle. Pre-seeds the cache once the target [android.content.Context]
 * is available, then schedules a background update check.
 */
val retributionUpdaterTweak by tweak {
    withAppContext { ctx ->
        RetributionUpdater.init(
            dataDir = ctx.dataDir.absolutePath,
            pkg = ctx.packageName,
            modPath = modulePath,
            context = ctx,
        )
        RetributionUpdater.scope.launch {
            RetributionUpdater.preSeedCache()
            delay(2_000)
            RetributionUpdater.downloadUpdate(showDialog = false, userInitiated = false)
        }
    }
}
