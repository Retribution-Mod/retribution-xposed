package io.github.retribution.xposed.tweaks

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import io.github.retribution.logger
import io.github.retribution.reloadApp
import io.github.retribution.xposed.RetributionConstants
import io.github.retribution.xposed.RetributionJson
import io.github.retribution.xposed.tweaks.legacy.appearance.Theme
import io.github.retribution.xposed.tweaks.legacy.appearance.ThemeData
import io.github.retribution.xposed.httpClient
import io.github.retribution.xposed.tweak
import io.github.retribution.xposed.tweaks.base.withAppActivity
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Handles manager://, retribution://, plugin://, theme:// and font:// deep links
 * by writing the appropriate config files and restarting Discord so the bundle
 * or XPosed tweaks can apply them on next launch.
 */
val bundleDeepLinkTweak by tweak {
    withAppActivity { activity ->
        val intent = activity.intent
        val data = intent?.data
        val type = deepLinkType(data)
        val url = resolveInstallUrl(data) ?: intent?.getStringExtra("retribution_bundle_url")

        if (type == null || url == null) return@withAppActivity

        when (type) {
            "bundle" -> {
                try {
                    RetributionUpdater.applyBundleUrl(url)
                    reloadApp()
                } catch (e: SecurityException) {
                    val log = logger("BundleDeepLinkTweak")
                    log.e("Bundle URL validation failed", e)
                    activity.runOnUiThread {
                        Toast.makeText(
                            activity,
                            "Security Error: Bundle URL not from trusted source",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            "font", "theme", "plugin" -> stageDeepLink(activity, type, url)
        }
    }
}

@Serializable
data class FontManifest(
    val name: String,
    val description: String? = null,
    val spec: Int? = null,
    val main: Map<String, String>
)

private fun deepLinkType(data: Uri?): String? {
    return when (data?.scheme) {
        "bundle", "manager" -> data.host?.takeIf { it == "bundle" }?.let { "bundle" }
        "font" -> "font"
        "theme" -> "theme"
        "plugin" -> "plugin"
        "retribution" -> data.host?.takeIf { it in setOf("bundle", "font", "theme", "plugin") }
        else -> null
    }
}

private fun resolveInstallUrl(data: Uri?): String? {
    data ?: return null

    data.getQueryParameter("url")?.let { return it }

    val host = data.host ?: return null
    val path = data.path?.trim('/') ?: return null
    if (path.isBlank()) return null

    val base = if ("." in host) "https://$host" else "https://$host.github.io"
    val query = data.query?.let { "?$it" } ?: ""

    return when (data.scheme) {
        "plugin" -> "$base/$path/"
        "theme" -> {
            val suffix = if (path.endsWith(".json")) "" else ".json"
            "$base/$path$suffix"
        }
        "font" -> "$base/$path$query"
        "retribution" -> when (data.host) {
            "bundle", "font", "theme", "plugin" -> "$base/$path$query".let {
                when (data.host) {
                    "plugin" -> "$it/"
                    "theme" -> if (path.endsWith(".json")) it else "$it.json"
                    else -> it
                }
            }
            else -> null
        }
        else -> null
    }
}

private fun stageDeepLink(activity: Activity, type: String, url: String) {
    val log = logger("BundleDeepLinkTweak")
    val filesDir = File(activity.applicationContext.dataDir, RetributionConstants.FILES_DIR).apply { mkdirs() }

    // Stage the deeplink for the bundle. The bundle reads this on launch and can trigger
    // its own plugin installer, which is the correct place to parse Vendetta manifests.
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val deeplinkFile = File(filesDir, "deeplink.json")
            deeplinkFile.writeText(
                RetributionJson.encodeToString(
                    DeepLinkPayload(type = type, url = url)
                )
            )
            log.i("$type deep link staged: $url")
            reloadApp()
        } catch (e: Throwable) {
            log.e("Failed to stage $type deep link", e)
        }
    }
}

@Serializable
private data class DeepLinkPayload(
    val type: String,
    val url: String,
)

private fun installThemeFromUrl(activity: Activity, url: String) {
    val log = logger("BundleDeepLinkTweak")
    val filesDir = File(activity.applicationContext.dataDir, RetributionConstants.FILES_DIR).apply { mkdirs() }

    CoroutineScope(Dispatchers.IO).launch {
        try {
            val response = httpClient.get(url)
            if (!response.status.isSuccess()) throw Error("HTTP ${response.status}")

            val data = response.body<String>()
            val themeData = RetributionJson.decodeFromString<ThemeData>(data)
            val theme = Theme(
                id = themeData.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim { it == '-' },
                selected = true,
                data = themeData,
            )

            File(filesDir, "current-theme.json").writeText(RetributionJson.encodeToString(theme))
            log.i("Theme installed: ${themeData.name}")
            reloadApp()
        } catch (e: Throwable) {
            log.e("Failed to install theme from $url", e)
        }
    }
}

private fun installFontFromUrl(activity: Activity, url: String) {
    val log = logger("BundleDeepLinkTweak")
    val dataDir = activity.applicationContext.dataDir
    val filesDir = File(dataDir, RetributionConstants.FILES_DIR).apply { mkdirs() }
    val downloadsDir = File(filesDir, "downloads/fonts").apply { mkdirs() }

    activity.runOnUiThread {
        Toast.makeText(activity, "Downloading font...", Toast.LENGTH_SHORT).show()
    }

    CoroutineScope(Dispatchers.IO).launch {
        try {
            val response = httpClient.get(url)
            if (!response.status.isSuccess()) throw Error("HTTP ${response.status}")

            val manifest = RetributionJson.decodeFromString<FontManifest>(response.body<String>())
            val setDir = File(downloadsDir, manifest.name).apply { mkdirs() }

            manifest.main.entries.forEach { (name, fontUrl) ->
                val ext = listOf(".ttf", ".otf").firstOrNull { fontUrl.endsWith(it) } ?: ".ttf"
                val fontFile = File(setDir, "$name$ext")
                if (!fontFile.exists()) {
                    val fontResponse = httpClient.get(fontUrl)
                    if (fontResponse.status.isSuccess()) {
                        fontFile.writeBytes(fontResponse.body())
                    }
                }
            }

            File(filesDir, "fonts.json").writeText(RetributionJson.encodeToString(manifest))
            log.i("Font installed: ${manifest.name}")
            reloadApp()
        } catch (e: Throwable) {
            log.e("Failed to install font from $url", e)
        }
    }
}
