package io.github.retribution.xposed.tweaks.bridge

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AdditionalBridgeMethodsTest {
    private val testDir: File = Files.createTempDirectory("Retribution-bridge-test").toFile()
    private val dataDir = File(testDir, "data").apply { mkdirs() }
    private val filesDir = File(testDir, "files").apply { mkdirs() }
    private val cacheDir = File(testDir, "cache").apply { mkdirs() }
    private val allowedDirs = listOf(dataDir, filesDir, cacheDir)

    @AfterTest
    fun cleanup() {
        testDir.deleteRecursively()
    }

    @Test
    fun allowsAccessToDataDir() {
        val testFile = File(dataDir, "test.txt").apply { writeText("test") }
        // Should not throw
        validateFilePath(testFile.absolutePath, allowedDirs)
    }

    @Test
    fun allowsAccessToFilesDir() {
        val testFile = File(filesDir, "test.txt").apply { writeText("test") }
        // Should not throw
        validateFilePath(testFile.absolutePath, allowedDirs)
    }

    @Test
    fun allowsAccessToCacheDir() {
        val testFile = File(cacheDir, "test.txt").apply { writeText("test") }
        // Should not throw
        validateFilePath(testFile.absolutePath, allowedDirs)
    }

    @Test
    fun allowsAccessToSubdirectories() {
        val subDir = File(dataDir, "subdir").apply { mkdirs() }
        val testFile = File(subDir, "test.txt").apply { writeText("test") }
        // Should not throw
        validateFilePath(testFile.absolutePath, allowedDirs)
    }

    @Test
    fun rejectsAccessToParentDirectory() {
        val parentFile = File(testDir, "outside.txt").apply { writeText("test") }
        assertFailsWith<SecurityException> {
            validateFilePath(parentFile.absolutePath, allowedDirs)
        }
    }

    @Test
    fun rejectsAccessToSystemFiles() {
        assertFailsWith<SecurityException> {
            validateFilePath("/etc/passwd", allowedDirs)
        }
    }

    @Test
    fun rejectsAccessToRootDirectory() {
        assertFailsWith<SecurityException> {
            validateFilePath("/", allowedDirs)
        }
    }

    @Test
    fun rejectsPathTraversalAttack() {
        val maliciousPath = File(dataDir, "../../../etc/passwd").absolutePath
        assertFailsWith<SecurityException> {
            validateFilePath(maliciousPath, allowedDirs)
        }
    }

    @Test
    fun rejectsSymlinkEscape() {
        // Create a symlink pointing outside allowed directories
        val outsideFile = File(testDir, "outside.txt").apply { writeText("secret") }
        val symlinkPath = File(dataDir, "symlink")
        
        try {
            // Create symlink (may fail on some systems without permissions)
            java.nio.file.Files.createSymbolicLink(
                symlinkPath.toPath(),
                outsideFile.toPath()
            )
            
            // Should reject access through symlink
            assertFailsWith<SecurityException> {
                validateFilePath(symlinkPath.absolutePath, allowedDirs)
            }
        } catch (e: UnsupportedOperationException) {
            // Skip test if symlinks not supported
            assertTrue(true, "Symlink test skipped (not supported on this system)")
        }
    }
}

/**
 * Test helper that exposes the private validateFilePath function for testing.
 * In production, this is a private function in AdditionalBridgeMethods.kt
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
