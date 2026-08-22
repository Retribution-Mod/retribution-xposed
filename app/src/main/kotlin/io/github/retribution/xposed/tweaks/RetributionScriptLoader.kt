package io.github.retribution.xposed.tweaks

import io.github.retribution.plugins.Version
import io.github.retribution.xposed.RetributionConstants
import io.github.retribution.xposed.ensureDir
import io.github.retribution.xposed.tweak
import io.github.retribution.xposed.tweaks.base.InjectorScope
import io.github.retribution.xposed.tweaks.base.registerScriptInjector
import io.github.retribution.xposed.tweaks.plugins.internal.DISCORD_VERSION
import io.github.retribution.xposed.tweaks.plugins.internal.isDiscordVersionSet
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Waits for script updates and loads the Retribution bundle. Depends on [io.github.retribution.xposed.tweaks.base.scriptLoader].
 *
 * 1. Awaits the bundle download from [RetributionUpdater].
 * 2. Runs every file under `files/pyoncord/preloads/`.
 * 3. Loads `cache/Retribution/bundle.js` (downloaded copy) if present.
 * 4. Falls back to the in-APK `assets://Retribution.bundle` asset shipped with this module.
 */
val RetributionScriptLoader by tweak {
    val dataDir = appInfo.dataDir
    val filesDir = File(dataDir, RetributionConstants.FILES_DIR).apply { ensureDir() }
    val cacheDir = File(dataDir, RetributionConstants.CACHE_DIR).apply { ensureDir() }
    val preloadsDir = File(filesDir, RetributionConstants.PRELOADS_DIR).apply { ensureDir() }
    val mainScript = File(cacheDir, RetributionConstants.MAIN_SCRIPT_FILE)

    registerScriptInjector { scope: InjectorScope ->
        // Pre-seed the bundle cache (public Manager folder or fallback asset) before loading.
        runCatching {
            runBlocking {
                withTimeout(10_000) {
                    RetributionUpdater.preSeedReady.await()
                }
            }
        }.onFailure { scope.tweakLog.w("Pre-seed timed out; loading bundle if already cached") }

        runRetributionScripts(scope, preloadsDir, mainScript)
    }
}

private val NEW_VERSION_THRESHOLD = Version.parse("341.0.0")
private val HERMES_MAGIC = byteArrayOf(0xc6.toByte(), 0x1f, 0xbc.toByte(), 0x03, 0xc1.toByte(), 0x03, 0x19, 0x1f)

private fun hermesBytecodeVersion(file: File): Int? {
    if (!file.isFile || file.length() < 12) return null
    val header = file.inputStream().use { it.readNBytes(12) }
    if (!header.copyOfRange(0, 8).contentEquals(HERMES_MAGIC)) return null
    return ByteBuffer.wrap(header, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
}

private fun runRetributionScripts(scope: InjectorScope, preloadsDir: File, mainScript: File) {
    val log = scope.tweakLog
    log.i("Running Retribution custom scripts...")

    val reactDevtools = File(preloadsDir, "reactDevtools.js")
    if (isDiscordVersionSet()) {
        val expectedBytecodeVersion = if (DISCORD_VERSION >= NEW_VERSION_THRESHOLD) 98 else 96
        val actualBytecodeVersion = hermesBytecodeVersion(reactDevtools)
        if (actualBytecodeVersion != null && actualBytecodeVersion != expectedBytecodeVersion) {
            log.w("Removing incompatible React DevTools preload: expected HBC $expectedBytecodeVersion, got $actualBytecodeVersion")
            reactDevtools.delete()
        }
    }

    try {
        preloadsDir.walk().filter { it.isFile }.sorted().forEach { f ->
            log.d("Running preload: ${f.absolutePath}")
            scope.runFile(f.absolutePath)
        }

        if (mainScript.isFile && mainScript.length() > 0) {
            log.i("Loading downloaded bundle: ${mainScript.absolutePath}")
            scope.runFile(mainScript.absolutePath)
        } else {
            val fallbackVariant = if (isDiscordVersionSet() && DISCORD_VERSION >= NEW_VERSION_THRESHOLD) "new" else "old"
            val fallbackAsset = RetributionConstants.fallbackBundleAsset(fallbackVariant)
            log.i("Downloaded bundle missing; falling back to $fallbackAsset")
            scope.runAsset(fallbackAsset)
        }
    } catch (e: Throwable) {
        log.e("Unable to run Retribution scripts", e)
    }
}

