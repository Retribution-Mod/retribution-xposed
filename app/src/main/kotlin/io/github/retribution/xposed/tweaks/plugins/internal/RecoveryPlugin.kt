package io.github.retribution.xposed.tweaks.plugins.internal

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import io.github.retribution.plugins.API_VERSION
import io.github.retribution.plugins.PluginManifest
import io.github.retribution.reloadApp
import io.github.retribution.xposed.RetributionConstants
import io.github.retribution.xposed.api.registerNativeMethod
import io.github.retribution.xposed.tweaks.RetributionUpdater
import io.github.retribution.xposed.tweaks.plugins.InternalPluginFlags
import io.github.retribution.xposed.tweaks.plugins.PluginStatesStore
import io.github.retribution.xposed.versionCode
import io.github.retribution.xposed.versionName
import java.io.File

private val manifest = PluginManifest(
    id = "Retribution.recovery",
    name = "Recovery",
    description = "Handles errors and provides troubleshooting options for Retribution.",
    author = "Retribution",
    icon = "ShieldIcon",
    version = API_VERSION,
)

internal val recoveryPlugin =
    internalPlugin(manifest, setOf(InternalPluginFlags.INTERNAL, InternalPluginFlags.ESSENTIAL)) {
        start {
            withAppActivity { act ->
                registerNativeMethod("Retribution.showRecoveryAlert") {
                    showRecoveryAlert(act)
                    null
                }

                registerNativeMethod("Retribution.alertError") {
                    val (error, version) = it
                    val errorString = "$error"

                    val clipboard = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Stack Trace", errorString)

                    AlertDialog.Builder(act)
                        .setTitle("Retribution Error")
                        .setMessage(
                            """
                        Retribution: $version
                        Discord: ${act.versionName()} (${act.versionCode()})
                        Device: ${Build.MANUFACTURER} ${Build.MODEL}
                        
                        
                    """.trimIndent() + errorString
                        )
                        .setPositiveButton(android.R.string.ok) { dialog, _ -> dialog.dismiss() }
                        .setNeutralButton(android.R.string.copy) { dialog, _ ->
                            @Suppress("UsePropertyAccessSyntax")
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(act, "Copied stack trace", Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
                        }
                        .setNegativeButton("Recovery") { dialog, _ ->
                            showRecoveryAlert(act)
                            dialog.dismiss()
                        }
                        .show()

                    null
                }
            }
        }
    }

/**
 * For the actual shake-gesture hook, you should be looking at [io.github.retribution.xposed.tweaks.discordDevSupport].
 */
fun showRecoveryAlert(context: Context) {
    AlertDialog.Builder(context)
        .setTitle("Retribution Recovery Options")
        .setItems(
            arrayOf("Reload", "Enter Recovery Mode", "Delete Script", "Reset Loader Config"),
        ) { _, which ->
            when (which) {
                0 -> reloadApp()

                1 -> {
                    PluginStatesStore.requestDefaultsOnlyBoot(context.dataDir.absolutePath)
                    reloadApp()
                }

                2 -> {
                    val bundleFile = File(
                        context.dataDir,
                        "${RetributionConstants.CACHE_DIR}/${RetributionConstants.MAIN_SCRIPT_FILE}",
                    )
                    if (bundleFile.exists()) bundleFile.delete()
                    reloadApp()
                }

                3 -> {
                    RetributionUpdater.resetLoaderConfig()
                    reloadApp()
                }
            }
        }
        .show()
}