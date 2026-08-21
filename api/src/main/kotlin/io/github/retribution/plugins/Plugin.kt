package io.github.retribution.plugins

import io.github.retribution.api.BuildConfig

/**
 * A Retribution plugin. Use the [plugin] builder to create one.
 *
 * Plugins are loaded once, then [start] runs with [PluginScope].
 * To access [android.content.Context], use `ctx.withAppContext { ... }` inside [start].
 * Override only what you need; both lifecycle hooks are no-ops by default.
 */
abstract class Plugin internal constructor(val manifest: PluginManifest) {
    /** Called once when the plugin is loaded. Register bridge methods and install hooks here. */
    open fun start(ctx: PluginScope) {}

    /** Called when the plugin is torn down. */
    open fun stop(ctx: PluginScope) {}
}

data class PluginManifest(
    val id: String,
    val name: String,
    val description: String,
    val author: String,
    val icon: String? = null,
    /** Dependencies keyed by plugin ID. */
    val dependencies: Map<String, PluginDependency> = emptyMap(),
    val version: Version,
)

/**
 * A plugin dependency specification.
 * The plugin ID is the key in [PluginManifest.dependencies].
 */
data class PluginDependency(
    /**
     * Version range the dependency must satisfy.
     *
     * Defaults to [VersionRange.ANY].
     */
    val version: VersionRange = VersionRange.ANY,
    /**
     * When `true`, the dependent isn't blocked if this dependency is missing, version-unsatisfied, or failed to load.
     *
     * When available, it loads first and its class loader is chained,
     * so you can check availability with `Class.forName(name, false, javaClass.classLoader)`.
     */
    val optional: Boolean = false,
)

/** The current Retribution plugin API version. */
val API_VERSION: Version = Version.parse(BuildConfig.API_VERSION)

/**
 * Reserved dependency ID resolving to [API_VERSION].
 *
 * External plugins **MUST** declare this dependency or they won't be loaded.
 */
const val API_DEPENDENCY_ID: String = "Retribution.api"

/**
 * Reserved dependency ID resolving to the host Discord app's version.
 *
 * The version is determined at runtime from the app the module is loaded into,
 * so it lives in the loader, not here. This library only defines the contract.
 *
 * External plugins **MUST** declare this dependency or they won't be loaded.
 */
const val DISCORD_DEPENDENCY_ID: String = "discord"
