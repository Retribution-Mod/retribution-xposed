package io.github.retribution.xposed

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.retribution.bridge.RetributionBridge
import io.github.retribution.xposed.api.HostScope
import io.github.retribution.xposed.tweaks.*
import io.github.retribution.xposed.tweaks.base.lifecycleSupport
import io.github.retribution.xposed.tweaks.base.scriptLoader
import io.github.retribution.xposed.tweaks.bridge.RetributionBridgeRegistry
import io.github.retribution.xposed.tweaks.bridge.additionalBridgeMethods
import io.github.retribution.xposed.tweaks.bridge.RetributionBridgeSupport
import io.github.retribution.xposed.tweaks.legacy.appearance.fonts
import io.github.retribution.xposed.tweaks.legacy.appearance.sysColors
import io.github.retribution.xposed.tweaks.legacy.appearance.themes
import io.github.retribution.xposed.tweaks.legacy.RetributionPayloadGlobal
import io.github.retribution.xposed.tweaks.plugins.discordVersionRetriever
import io.github.retribution.xposed.tweaks.plugins.pluginLoader
import io.github.retribution.xposed.tweaks.plugins.pluginStates
import io.github.retribution.xposed.tweaks.plugins.repos.pluginRepos

private lateinit var modulePath: String

@Suppress("UNUSED")
class Main : IXposedHookLoadPackage, IXposedHookZygoteInit {
    @Volatile
    private var hooked = false

    private val tweaks: List<TweakSpec> = listOf(
        // Framework
        lifecycleSupport,
        RetributionBridgeSupport,
        scriptLoader,
        discordVersionRetriever,

        // Static patches
        fixResources,

        // Persistence
        caches,
        pluginStates,
        pluginRepos,

        // Async updater
        retributionUpdaterTweak,

        // Deep link handling
        bundleDeepLinkTweak,

        // Consumers
        discordDevSupport,
        additionalBridgeMethods,
        fonts,
        themes,
        sysColors,
        pluginLoader,
        RetributionScriptLoader,
        RetributionPayloadGlobal,
    )

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        modulePath = startupParam.modulePath
    }

    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        // Only hook the main process.
        // Discord uses ProcessPhoenix to spawn a ":phoenix" process to restart the app. It will cause concurrency issues.
        if (param.processName != param.packageName) return

        if (hooked) return
        hooked = true

        val ctx = HostScopeImpl(
            modulePath = modulePath,
            appInfo = param.appInfo,
            classLoader = param.classLoader,
        )
        for (spec in tweaks) spec.applyTo(ctx)
    }
}

private class HostScopeImpl(
    override val modulePath: String,
    override val appInfo: ApplicationInfo,
    override val classLoader: ClassLoader,
) : HostScope {
    override val bridge: RetributionBridge get() = RetributionBridgeRegistry
    override fun withAppContext(block: (Context) -> Unit) = io.github.retribution.xposed.tweaks.base.withAppContext(block)
    override fun withAppActivity(block: (Activity) -> Unit) =
        io.github.retribution.xposed.tweaks.base.withAppActivity(block)
}