package io.github.retribution.xposed.tweaks

import android.content.res.Resources
import io.github.retribution.xposed.RetributionConstants
import io.github.retribution.xposed.hook
import io.github.retribution.xposed.method
import io.github.retribution.xposed.tweak

/**
 * Hooks [Resources.getIdentifier] to rewrite the package name to `com.discord` when the host app was repackaged.
 */
val fixResources by tweak {
    val hostPkg = appInfo.packageName
    if (hostPkg == RetributionConstants.TARGET_PACKAGE) return@tweak

    Resources::class.java.method(
        "getIdentifier",
        String::class.java,
        String::class.java,
        String::class.java,
    ).hook {
        before {
            if (args[2] == hostPkg) args[2] = RetributionConstants.TARGET_PACKAGE
        }
    }
}
