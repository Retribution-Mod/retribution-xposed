package io.github.retribution.xposed.tweaks.bridge

import io.github.retribution.bridge.asDelegate
import io.github.retribution.reloadApp
import io.github.retribution.xposed.openFileGuarded
import io.github.retribution.xposed.validatePathConfinement
import io.github.retribution.xposed.tweak
import java.io.File

/**
 * `Retribution.fs.*` + `Retribution.app.*` bridge methods.
 */
val additionalBridgeMethods by tweak {
    with(RetributionBridgeRegistry) {
        registerMethod("Retribution.app.reload") {
            reloadApp()
        }

        withAppContext { ctx ->
            // Define allowed base directories for filesystem operations
            val allowedDirs = listOf(ctx.dataDir, ctx.filesDir, ctx.cacheDir)

            registerMethod("Retribution.fs.getConstants") {
                mapOf(
                    "data" to ctx.dataDir.absolutePath,
                    "files" to ctx.filesDir.absolutePath,
                    "cache" to ctx.cacheDir.absolutePath,
                )
            }

            registerMethod("Retribution.fs.delete") { args ->
                val argv = args.asDelegate()
                val path by argv.string()
                val f = File(path)
                // Validate path is within allowed directories before deletion
                f.validatePathConfinement(allowedDirs)
                if (f.isDirectory) f.deleteRecursively() else f.delete()
            }

            registerMethod("Retribution.fs.exists") { args ->
                val argv = args.asDelegate()
                val path by argv.string()
                val f = File(path)
                // Validate path is within allowed directories before checking existence
                f.validatePathConfinement(allowedDirs)
                f.exists()
            }

            registerMethod("Retribution.fs.read") { args ->
                val argv = args.asDelegate()
                val path by argv.string()
                val file = File(path)
                // Validate path is within allowed directories before reading
                file.validatePathConfinement(allowedDirs)
                file.openFileGuarded()
                file.bufferedReader().use { it.readText() }
            }

            registerMethod("Retribution.fs.write") { args ->
                val argv = args.asDelegate()
                val path by argv.string()
                val contents by argv.string()
                val file = File(path)
                // Validate path is within allowed directories before writing
                file.validatePathConfinement(allowedDirs)
                file.apply {
                    if (isDirectory) throw Error("Path is a directory: $path")
                    parentFile?.mkdirs()
                    writeText(contents)
                }

                null
            }
        }
    }
}
