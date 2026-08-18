package io.github.retribution.xposed.tweaks.plugins

import io.github.retribution.Logger
import io.github.retribution.xposed.api.HostScope
import io.github.retribution.xposed.api.callJSMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal const val EVENT_DOWNLOAD_PROGRESS = "Retribution.plugins.events.downloadProgress"
internal const val EVENT_PLUGIN_INSTALL_READY = "Retribution.plugins.events.pluginInstallReady"
internal const val EVENT_PLUGIN_INSTALL_RESULT = "Retribution.plugins.events.pluginInstallResult"
internal const val EVENT_PLUGIN_UPDATED = "Retribution.plugins.events.pluginUpdated"
internal const val EVENT_PLUGIN_ERRORED = "Retribution.plugins.events.pluginErrored"
internal const val EVENT_REPO_STATE_UPDATE = "Retribution.plugins.events.repoStateUpdate"

/**
 * Fire-and-forget native-to-JS event. Failures are only logged.
 *
 * JS event handlers must resolve immediately (the callJSMethod reply queue is positional),
 * async answers must be through a separate bridge method instead.
 */
internal fun HostScope.emitPluginEvent(
    scope: CoroutineScope,
    log: Logger,
    name: String,
    payload: Map<String, Any?>,
) {
    scope.launch {
        runCatching { callJSMethod(name, listOf(payload)) }
            .onFailure { log.e("Failed to emit $name", it) }
    }
}
