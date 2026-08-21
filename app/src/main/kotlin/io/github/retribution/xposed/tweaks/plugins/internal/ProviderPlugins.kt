package io.github.retribution.xposed.tweaks.plugins.internal

import io.github.retribution.plugins.*
import io.github.retribution.xposed.tweaks.plugins.InternalPluginFlags

/**
 * Reserved dependency IDs supplied by internal provider plugins.
 * Injected as [VersionRange.ANY] into every internal plugin and required for external plugins.
 */
internal val RESERVED_DEPENDENCY_IDS: Set<String> = setOf(API_DEPENDENCY_ID, DISCORD_DEPENDENCY_ID)

/** The version of the host Discord app. */
internal lateinit var DISCORD_VERSION: Version

/** Check whether [DISCORD_VERSION] has already been set. */
internal fun isDiscordVersionSet(): Boolean = ::DISCORD_VERSION.isInitialized

/**
 * Internal provider for the Retribution API. Exposes the module class loader external native plugins link
 * against and tracks the API version so plugin compatibility is re-checked after updates.
 *
 * > `CompositeClassLoader`'s parent already handles the lookup, so the class-loader part here is redundant.
 *
 * The JS API is split into `Retribution.api.*` sub-plugins run at different stages; this entry point has no JS body.
 */
internal val apiProviderPlugin = internalPlugin(
    PluginManifest(
        id = API_DEPENDENCY_ID,
        name = "Retribution Plugin API",
        description = "Provides the Retribution plugin API.",
        author = "Retribution",
        version = API_VERSION,
    ),
    setOf(InternalPluginFlags.INTERNAL, InternalPluginFlags.ESSENTIAL, InternalPluginFlags.API),
) {}

/**
 * Internal provider for the host Discord app.
 *
 * Tracks the Discord version so plugin compatibility is re-checked after a Discord update.
 */
internal val discordProviderPlugin by lazy {
    internalPlugin(
        PluginManifest(
            id = DISCORD_DEPENDENCY_ID,
            name = "Discord",
            description = "Provides the host Discord app version.",
            author = "Discord",
            version = DISCORD_VERSION,
        ),
        setOf(InternalPluginFlags.INTERNAL, InternalPluginFlags.ESSENTIAL, InternalPluginFlags.API),
    ) {}
}
