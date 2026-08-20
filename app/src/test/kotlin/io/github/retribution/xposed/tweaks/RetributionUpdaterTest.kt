package io.github.retribution.xposed.tweaks

import io.github.retribution.plugins.Version
import io.github.retribution.xposed.RetributionJson
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class RetributionUpdaterTest {
    private val dataDir: File = Files.createTempDirectory("Retribution-updater-test").toFile()

    @AfterTest
    fun cleanup() {
        dataDir.deleteRecursively()
    }

    @Test
    fun selectsOldBundleBeforeDiscord341() {
        RetributionUpdater.init(dataDir.absolutePath, "app.retribution")
        assertTrue(RetributionUpdater.bundleUrl(Version.parse("340.13.0")).endsWith("/retribution-old.min.js"))
    }

    @Test
    fun selectsNewBundleFromDiscord341() {
        RetributionUpdater.init(dataDir.absolutePath, "app.retribution")
        assertTrue(RetributionUpdater.bundleUrl(Version.parse("341.13.0")).endsWith("/retribution-new.min.js"))
    }

    @Test
    fun selectsNextBundleForNextPackage() {
        RetributionUpdater.init(dataDir.absolutePath, "app.retribution.next")
        assertTrue(RetributionUpdater.bundleUrl(Version.parse("341.13.0")).contains("retribution-bundle-next"))
    }

    @Test
    fun rejectsMaliciousBundleUrl() {
        RetributionUpdater.init(dataDir.absolutePath, "app.retribution")
        assertFailsWith<SecurityException> {
            RetributionUpdater.applyBundleUrl("https://evil.com/malicious.js")
        }
    }

    @Test
    fun rejectsArbitraryGitHubUrl() {
        RetributionUpdater.init(dataDir.absolutePath, "app.retribution")
        assertFailsWith<SecurityException> {
            RetributionUpdater.applyBundleUrl("https://github.com/attacker/malicious-repo/releases/bundle.js")
        }
    }

    @Test
    fun acceptsTrustedRetributionBundleUrl() {
        RetributionUpdater.init(dataDir.absolutePath, "app.retribution")
        // Should not throw
        RetributionUpdater.applyBundleUrl("https://github.com/Retribution-Mod/retribution-bundle/releases/v1.0.0/bundle.js")
    }

    @Test
    fun acceptsTrustedRetributionBundleNextUrl() {
        RetributionUpdater.init(dataDir.absolutePath, "app.retribution")
        // Should not throw
        RetributionUpdater.applyBundleUrl("https://github.com/Retribution-Mod/retribution-bundle-next/releases/latest/download/retribution.min.js")
    }

    @Test
    fun acceptsRawGitHubContentFromTrustedRepo() {
        RetributionUpdater.init(dataDir.absolutePath, "app.retribution")
        // Should not throw
        RetributionUpdater.applyBundleUrl("https://raw.githubusercontent.com/Retribution-Mod/retribution-bundle/main/bundle.js")
    }

    @Test
    fun rejectsExistingMaliciousConfigOnInit() {
        val filesDir = File(dataDir, "files/pyoncord").apply { mkdirs() }
        val configFile = File(filesDir, "loader.json")
        
        // Write a malicious config
        val maliciousConfig = LoaderConfig(
            customLoadUrl = CustomLoadUrl(enabled = true, url = "https://evil.com/malicious.js")
        )
        configFile.writeText(RetributionJson.encodeToString(maliciousConfig))
        
        // Init should detect and remove the malicious config
        RetributionUpdater.init(dataDir.absolutePath, "app.retribution")
        
        // Config file should be deleted
        assertFalse(configFile.exists(), "Malicious config should be deleted on init")
    }
}
