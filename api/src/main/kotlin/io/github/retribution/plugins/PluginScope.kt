package io.github.retribution.plugins

import io.github.retribution.Logger
import io.github.retribution.xposed.api.HostScope
import kotlinx.coroutines.flow.MutableSharedFlow
import java.io.File

interface PluginScope : HostScope {
    /** Per-plugin logger, namespaced by the plugin ID. */
    val log: Logger

    val manifest: PluginManifest

    /**
     * This plugin's data directory (`files/Retribution/plugins/storage/<id>/`), created on first access.
     *
     * Preserved across updates and deleted on uninstall. Shared with the plugin's JS side
     * (`jsonStorage` keeps its documents here, with `storage.json` reserved as the default).
     * Store whatever you want in whatever format fits.
     */
    val storageDir: File

    /** Whether this plugin is currently enabled. */
    val enabled: Boolean

    /**
     * Whether this plugin was started after the initial load (e.g. user-toggled at runtime).
     * Plugins with early-only work should call [requireReload] when this is true.
     */
    val startedLate: Boolean

    /**
     * Errors this plugin hit during its lifecycle. JS errors are not included.
     *
     * Emit any [Throwable] to surface it in the UI. Emitted errors are non-fatal:
     * they won't stop or disable the plugin. Call [stop] or [disable] manually to halt it.
     */
    val errors: MutableSharedFlow<Throwable>

    /** Mark this plugin as requiring a host reload to (un)apply its changes. */
    fun requireReload()

    /** Stops this plugin. */
    fun stop()

    /** Disables this plugin. */
    fun disable()
}
