package io.github.retribution.xposed

import android.content.Context
import android.os.Build
import kotlinx.serialization.json.Json
import java.io.File

fun File.ensureDir() {
    if (!isDirectory) delete()
    mkdirs()
}

fun File.ensureFile() {
    if (!isFile) deleteRecursively()
}

fun File.openFileGuarded() {
    if (!exists()) throw Error("Path does not exist: $path")
    if (!isFile) throw Error("Path is not a file: $path")
}

/**
 * Confirms this file path stays inside one of the allowed base directories.
 *
 * @param allowedBases Allowed base directories (e.g., dataDir, filesDir, cacheDir)
 * @throws SecurityException if the path escapes all allowed bases
 */
fun File.validatePathConfinement(allowedBases: List<File>) {
    val canonicalPath = try {
        this.canonicalFile
    } catch (e: Exception) {
        throw SecurityException("Cannot resolve path: ${this.path}", e)
    }

    val isConfined = allowedBases.any { base ->
        try {
            canonicalPath.startsWith(base.canonicalFile)
        } catch (e: Exception) {
            false
        }
    }

    if (!isConfined) {
        throw SecurityException(
            "Path access denied: ${this.path} is outside allowed directories. " +
            "Allowed: ${allowedBases.joinToString { it.absolutePath }}"
        )
    }
}

fun Context.versionName(): String {
    val pInfo = packageManager.getPackageInfo(packageName, 0)
    return pInfo.versionName ?: "unknown"
}

fun Context.versionCode(): Long {
    val pInfo = packageManager.getPackageInfo(packageName, 0)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pInfo.longVersionCode
    else @Suppress("DEPRECATION") pInfo.versionCode.toLong()
}

val RetributionJson: Json = Json { ignoreUnknownKeys = true }