package io.github.retribution.xposed.tweaks

import io.github.retribution.xposed.RetributionConstants
import io.github.retribution.xposed.ensureDir
import io.github.retribution.xposed.ensureFile
import io.github.retribution.xposed.tweak
import io.github.retribution.xposed.tweaks.base.InjectorScope
import io.github.retribution.xposed.tweaks.base.registerScriptInjector
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File

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
    val mainScript = File(cacheDir, RetributionConstants.MAIN_SCRIPT_FILE).apply { ensureFile() }

    registerScriptInjector { scope: InjectorScope ->
        runRetributionScripts(scope, preloadsDir, mainScript)
    }
}

private fun runRetributionScripts(scope: InjectorScope, preloadsDir: File, mainScript: File) {
    val log = scope.tweakLog
    log.i("Running Retribution custom scripts...")

    runBlocking {
        try {
            withTimeout(RetributionUpdater.TIMEOUT) { RetributionUpdater.downloadReady.await() }
        } catch (e: Throwable) {
            log.w("Bundle download did not complete", e)
        }
    }

    try {
        preloadsDir.walk().filter { it.isFile }.sorted().forEach { f ->
            log.d("Running preload: ${f.absolutePath}")
            scope.runFile(f.absolutePath)
        }

        if (mainScript.exists()) {
            log.i("Loading downloaded bundle: ${mainScript.absolutePath}")
            scope.runFile(mainScript.absolutePath)
        } else {
            log.i("Downloaded bundle missing; falling back to ${RetributionConstants.FALLBACK_BUNDLE_ASSET}")
            scope.runAsset(RetributionConstants.FALLBACK_BUNDLE_ASSET)
        }
    } catch (e: Throwable) {
        log.e("Unable to run Retribution scripts", e)
    }
}

