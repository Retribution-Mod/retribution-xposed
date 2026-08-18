package io.github.retribution.xposed.tweaks

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import io.github.retribution.logger
import io.github.retribution.reloadApp
import io.github.retribution.xposed.RetributionConstants
import io.github.retribution.xposed.RetributionJson
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
 * Handles retribution:// deep links by writing the appropriate config files
 * and restarting Discord so the XPosed tweaks / bundle handle them on next launch.
 */
val bundleDeepLinkTweak by tweak {
    withAppActivity { activity ->
        val intent = activity.intent
        val data = intent?.data
        val url = when {
            data?.host == "bundle" || data?.host == "font" -> data.getQueryParameter("url")
            intent?.hasExtra("retribution_bundle_url") == true ->
                intent.getStringExtra("retribution_bundle_url")
            else -> null
        }

        if (url == null) return@withAppActivity

        when (data?.host ?: "bundle") {
            "bundle" -> {
                RetributionUpdater.applyBundleUrl(url)
                reloadApp()
            }
            "font" -> {
                installFontFromUrl(activity, url)
            }
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
