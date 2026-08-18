package io.github.retribution.xposed.tweaks.plugins.internal

import io.github.retribution.plugins.API_VERSION
import io.github.retribution.plugins.PluginManifest
import io.github.retribution.xposed.api.registerNativeMethod
import io.github.retribution.xposed.tweaks.plugins.InternalPluginFlags

private val manifest = PluginManifest(
    id = "Retribution.example",
    name = "Example Plugin",
    description = "Example plugin.",
    author = "Retribution",
    version = API_VERSION,
)

internal val examplePlugin =
    internalPlugin(manifest, setOf(InternalPluginFlags.INTERNAL, InternalPluginFlags.ESSENTIAL)) {
        start {
            log.i("started in ${appInfo.packageName}")
            registerNativeMethod("Retribution.example.test") { args ->
                log.i("Retribution.example.test($args)")
                null
            }

            registerNativeMethod("Retribution.example.test.error") {
                errors.tryEmit(Exception("Test exception!"))
            }
        }
    }