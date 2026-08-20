package io.github.retribution.xposed.tweaks.bridge

import io.github.retribution.bridge.asDelegate
import io.github.retribution.reloadApp
import io.github.retribution.xposed.openFileGuarded
import io.github.retribution.xposed.tweak
import java.io.File

/**
 * Validates that a file path is within allowed app directories.
 * Prevents path traversal and access to sensitive system files.
 * @throws SecurityException if the path is outside allowed directories
 */
private fun validateFilePath(path: String, allowedDirs: List<File>) {
    val file = File(path).canonicalFile
    val isAllowed = allowedDirs.any { allowedDir ->
        file.startsWith(allowedDir.canonicalFile)
    }
    if (!isAllowed) {
        throw SecurityException(
            "File access denied: path is outside allowed directories. Path: $path"
        )
    }
}

/**
 * `Retribution.fs.*` + `Retribution.app.*` bridge methods.
 */
val additionalBridgeMethods by tweak {
    with(RetributionBridgeRegistry) {
        registerMethod("Retribution.app.reload") {
            reloadApp()
        }

        withAppContext { ctx ->
            // Define allowed directories for file operations
            val allowedDirs = listOf(
                ctx.dataDir,
                ctx.filesDir,
                ctx.cacheDir,
            )

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
                validateFilePath(path, allowedDirs)
                val f = File(path)
                if (f.isDirectory) f.deleteRecursively() else f.delete()
            }

            registerMethod("Retribution.fs.exists") { args ->
                val argv = args.asDelegate()
                val path by argv.string()
                validateFilePath(path, allowedDirs)
                File(path).exists()
            }

            registerMethod("Retribution.fs.read") { args ->
                val argv = args.asDelegate()
                val path by argv.string()
                validateFilePath(path, allowedDirs)
                val file = File(path).also { it.openFileGuarded() }
                file.bufferedReader().use { it.readText() }
            }

            registerMethod("Retribution.fs.write") { args ->
                val argv = args.asDelegate()
                val path by argv.string()
                val contents by argv.string()
                validateFilePath(path, allowedDirs)
                File(path).apply {
                    if (isDirectory) throw Error("Path is a directory: $path")
                    parentFile?.mkdirs()
                    writeText(contents)
                }

                null
            }
        }
    }
}
