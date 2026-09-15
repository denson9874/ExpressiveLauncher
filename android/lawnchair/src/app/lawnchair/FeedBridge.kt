/*
 * Copyright 2021, Lawnchair
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.lawnchair

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE
import android.content.pm.ApplicationInfo.FLAG_SYSTEM
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.util.Log
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.util.SingletonHolder
import app.lawnchair.util.ensureOnMainThread
import app.lawnchair.util.getSignatureHash
import app.lawnchair.util.useApplicationContext
import com.android.launcher3.BuildConfig
import com.android.launcher3.R
import com.android.launcher3.Utilities
import com.kieronquinn.app.smartspacer.sdk.SmartspacerConstants

class FeedBridge(private val context: Context) {

    private val requiresBridge = context.applicationInfo.flags and (FLAG_DEBUGGABLE or FLAG_SYSTEM) == 0
    private val prefs by lazy { PreferenceManager.getInstance(context) }
    private val bridgePackages: List<BridgeInfo> by lazy {
        if (BuildConfig.IS_EXPRESSIVE_PRODUCT) {
            // Lawnfeed's bridge rejects application IDs outside its built-in Lawnchair allowlist.
            // Expressive therefore uses its own same-signed companion instead of advertising an
            // installed-but-unusable third-party bridge as a working feed.
            listOf(SameSignatureBridgeInfo(FIRST_PARTY_FEED_PACKAGE))
        } else {
            listOf(
                PixelBridgeInfo("com.google.android.apps.nexuslauncher", R.integer.bridge_signature_hash),
                BridgeInfo("app.lawnchair.lawnfeed", R.integer.lawnfeed_signature_hash),
            )
        }
    }

    @JvmOverloads
    fun resolveBridge(customPackage: String = prefs.feedProvider.get()): BridgeInfo? {
        val customBridge = customBridgeOrNull(customPackage)
        return when {
            customBridge != null -> customBridge
            !requiresBridge && !BuildConfig.IS_EXPRESSIVE_PRODUCT -> null
            else -> bridgePackages.firstOrNull { it.isAvailable() }
        }
    }

    private fun customBridgeOrNull(customPackage: String = prefs.feedProvider.get()): CustomBridgeInfo? {
        if (BuildConfig.IS_EXPRESSIVE_PRODUCT && customPackage in expressiveIncompatibleProviders) {
            // These providers are valid for their own launcher identities but reject Expressive.
            // Ignore a stale selection so upgrading users can move to the same-signed companion.
            return null
        }
        return if (customPackage.isNotBlank()) {
            val bridge = CustomBridgeInfo(customPackage)
            if (bridge.isAvailable()) bridge else null
        } else {
            null
        }
    }

    fun isInstalled(): Boolean {
        return resolveConnection() != null
    }

    /**
     * Resolves the complete bind decision in one pass so the service connection cannot choose a
     * bridge and then accidentally fall back to Google when that bridge disappears mid-reconnect.
     */
    fun resolveConnection(): ConnectionInfo? {
        val bridge = resolveBridge()
        val directOverlayAvailable = bridge == null && directOverlayAvailable()
        return when (
            resolveFeedConnectionKind(
                requiresBridge = requiresBridge,
                bridgeAvailable = bridge != null,
                directOverlayAvailable = directOverlayAvailable,
            )
        ) {
            FeedConnectionKind.BRIDGE -> ConnectionInfo(bridge!!.packageName, true)
            FeedConnectionKind.DIRECT -> ConnectionInfo(GOOGLE_APP_PACKAGE, false)
            FeedConnectionKind.UNAVAILABLE -> null
        }
    }

    /** Packages whose install state can change the active feed connection. */
    fun shouldReconnectForPackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return packageName == GOOGLE_APP_PACKAGE ||
            bridgePackages.any { it.packageName == packageName } ||
            prefs.feedProvider.get() == packageName
    }

    fun resolveSmartspace(): String {
        return bridgePackages.firstOrNull { it.supportsSmartspace }?.packageName
            ?: GOOGLE_APP_PACKAGE
    }

    open inner class BridgeInfo(val packageName: String, signatureHashRes: Int) {
        protected open val signatureHash =
            if (signatureHashRes > 0) context.resources.getInteger(signatureHashRes) else 0

        open val supportsSmartspace = false

        fun isAvailable(): Boolean {
            return runCatching {
                // Probe with the same launcher-authority URI used for the real bind. The previous
                // provider-authority probe could report a service that the launcher could not bind.
                context.packageManager.resolveService(
                    createOverlayIntent(context, packageName),
                    PackageManager.GET_META_DATA,
                ) != null && isSigned()
            }.onFailure {
                // Package replacement can race a preference refresh or lifecycle reconnect.
                Log.w(TAG, "Feed provider $packageName disappeared while being validated", it)
            }.getOrDefault(false)
        }

        open fun isSigned(): Boolean {
            when {
                BuildConfig.DEBUG -> return true

                Utilities.ATLEAST_P -> {
                    val info =
                        context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                    val signingInfo = info.signingInfo
                    if (signingInfo!!.hasMultipleSigners()) return false
                    return signingInfo.signingCertificateHistory.any { it.hashCode() == signatureHash }
                }

                else -> {
                    val info = context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                    return if (info.signatures!!.any { it.hashCode() != signatureHash }) false else info.signatures!!.isNotEmpty()
                }
            }
        }
    }

    private inner class SameSignatureBridgeInfo(packageName: String) : BridgeInfo(packageName, 0) {
        override fun isSigned(): Boolean {
            // The first-party bridge is privileged by a signature permission. Enforce the same
            // trust boundary before binding so a package-name squatter cannot impersonate it.
            return context.packageManager.checkSignatures(context.packageName, packageName) ==
                PackageManager.SIGNATURE_MATCH
        }
    }

    private inner class CustomBridgeInfo(packageName: String) : BridgeInfo(packageName, 0) {
        override val signatureHash = whitelist[packageName]?.toInt() ?: -1
        val ignoreWhitelist = prefs.ignoreFeedWhitelist.get()
        override fun isSigned(): Boolean {
            if (signatureHash == -1 && Utilities.ATLEAST_P) {
                val info = context.packageManager
                    .getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signingInfo = info.signingInfo
                if (signingInfo!!.hasMultipleSigners()) return false
                signingInfo.signingCertificateHistory.forEach {
                    val hash = Integer.toHexString(it.hashCode())
                    Log.d(TAG, "Feed provider $packageName(0x$hash) isn't whitelisted")
                }
            }
            return ignoreWhitelist || signatureHash != -1 && super.isSigned()
        }
    }

    private inner class PixelBridgeInfo(packageName: String, signatureHashRes: Int) : BridgeInfo(packageName, signatureHashRes) {
        override val supportsSmartspace get() = isAvailable()
    }

    private fun directOverlayAvailable(): Boolean {
        if (requiresBridge) return false
        return runCatching {
            context.packageManager.resolveService(
                createOverlayIntent(context, GOOGLE_APP_PACKAGE),
                PackageManager.GET_META_DATA,
            ) != null
        }.getOrDefault(false)
    }

    private fun isProviderSignatureAccepted(packageName: String): Boolean {
        return runCatching {
            if (BuildConfig.IS_EXPRESSIVE_PRODUCT && packageName == FIRST_PARTY_FEED_PACKAGE) {
                SameSignatureBridgeInfo(packageName).isSigned()
            } else {
                CustomBridgeInfo(packageName).isSigned()
            }
        }.getOrDefault(false)
    }

    data class ConnectionInfo(
        val packageName: String,
        val useBridge: Boolean,
    )

    companion object : SingletonHolder<FeedBridge, Context>(
        ensureOnMainThread(
            useApplicationContext(::FeedBridge),
        ),
    ) {
        private const val TAG = "FeedBridge"
        const val OVERLAY_ACTION = "com.android.launcher3.WINDOW_OVERLAY"
        const val GOOGLE_APP_PACKAGE = "com.google.android.googlequicksearchbox"
        const val FIRST_PARTY_FEED_PACKAGE = "dev.launcher.expressive.feed"
        const val FIRST_PARTY_CONNECT_PERMISSION =
            "dev.launcher.expressive.feed.permission.CONNECT"

        private val expressiveIncompatibleProviders = setOf(
            "app.lawnchair.lawnfeed",
            "com.google.android.apps.nexuslauncher",
            GOOGLE_APP_PACKAGE,
        )

        private val whitelist = mutableMapOf<String, Long?>()

        fun initializeWhitelist(context: Context) {
            whitelist["com.saulhdev.neofeed"] = getSignatureHash(context, "com.saulhdev.neofeed")
            whitelist["ua.itaysonlab.homefeeder"] = 0x887456ed
            whitelist["launcher.libre.dev"] = 0x2e9dbab5
            whitelist[SmartspacerConstants.SMARTSPACER_PACKAGE_NAME] = 0x15c6e36f
            whitelist["amirz.aidlbridge"] = 0xb662cc2f
            whitelist["com.google.android.googlequicksearchbox"] = 0xe3ca78d8
            whitelist["com.google.android.apps.nexuslauncher"] = 0xb662cc2f
        }

        fun getAvailableProviders(context: Context) = context.packageManager
            .queryIntentServices(
                Intent(OVERLAY_ACTION).setData(Uri.parse("app://${context.packageName}")),
                PackageManager.GET_META_DATA,
            )
            .asSequence()
            .map { it.serviceInfo.applicationInfo }
            .distinct()
            .filter { getInstance(context).isProviderSignatureAccepted(it.packageName) }

        @JvmStatic
        fun createOverlayIntent(context: Context, targetPackage: String): Intent {
            return Intent(OVERLAY_ACTION)
                .setPackage(targetPackage)
                .setData(
                    Uri.parse("app://${context.packageName}:${Process.myUid()}")
                        .buildUpon()
                        .appendQueryParameter("v", 7.toString())
                        .appendQueryParameter("cv", 9.toString())
                        .build(),
                )
        }

        @JvmStatic
        fun useBridge(context: Context) = getInstance(context).resolveConnection()?.useBridge == true
    }

    init {
        initializeWhitelist(context)
    }
}

internal enum class FeedConnectionKind {
    BRIDGE,
    DIRECT,
    UNAVAILABLE,
}

/** Pure policy kept separate from PackageManager so release-vs-debug fallback is regression tested. */
internal fun resolveFeedConnectionKind(
    requiresBridge: Boolean,
    bridgeAvailable: Boolean,
    directOverlayAvailable: Boolean,
): FeedConnectionKind = when {
    bridgeAvailable -> FeedConnectionKind.BRIDGE
    !requiresBridge && directOverlayAvailable -> FeedConnectionKind.DIRECT
    else -> FeedConnectionKind.UNAVAILABLE
}
