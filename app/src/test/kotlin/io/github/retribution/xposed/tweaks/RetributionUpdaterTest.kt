package io.github.retribution.xposed.tweaks

import io.github.retribution.plugins.Version
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

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
    fun rejectsUntrustedBundleUrl() {
        RetributionUpdater.init(dataDir.absolutePath, "app.retribution")
        assertFailsWith<SecurityException> {
            RetributionUpdater.applyBundleUrl("https://evil.com/malicious.js")
        }
    }
    
    @Test
    fun acceptsTrustedBundleUrl() {
        RetributionUpdater.init(dataDir.absolutePath, "app.retribution")
        // Should not throw
        RetributionUpdater.applyBundleUrl("https://github.com/Retribution-Mod/retribution-bundle/releases/latest/download/retribution-new.min.js")
    }
    
    @Test
    fun rejectsArbitraryGitHubUrl() {
        RetributionUpdater.init(dataDir.absolutePath, "app.retribution")
        assertFailsWith<SecurityException> {
            RetributionUpdater.applyBundleUrl("https://github.com/attacker/malicious-repo/releases/download/bundle.js")
        }
    }
}
