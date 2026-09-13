package app.lawnchair.ui.preferences.about

import android.content.Context
import com.android.launcher3.BuildConfig

/** The selected feed survives an in-place QA/Stable installation and defaults to this build. */
internal class ExpressiveUpdateChannelPreferences(
    context: Context,
    private val buildType: String = BuildConfig.BUILD_TYPE,
    private val qaManifestUrl: String = BuildConfig.EXPRESSIVE_QA_UPDATE_MANIFEST_URL,
    private val releaseManifestUrl: String = BuildConfig.EXPRESSIVE_RELEASE_UPDATE_MANIFEST_URL,
    private val isExpressiveProduct: Boolean = BuildConfig.IS_EXPRESSIVE_PRODUCT,
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "expressive_update_channels",
        Context.MODE_PRIVATE,
    )

    fun selectedConfig(): ExpressiveUpdateConfig? {
        if (!isExpressiveProduct) return null
        return expressiveUpdateConfig(
            buildType = buildType,
            qaManifestUrl = qaManifestUrl,
            releaseManifestUrl = releaseManifestUrl,
            selectedChannel = preferences.getString(SELECTED_CHANNEL, null),
        )
    }

    fun select(channel: ExpressiveUpdateChannel): ExpressiveUpdateConfig? = synchronized(SELECTION_LOCK) {
        val current = selectedConfig() ?: return@synchronized null
        preferences.edit()
            .putString(SELECTED_CHANNEL, channel.wireName)
            .putLong(SELECTION_REVISION, selectionRevision() + if (current.channel != channel) 1L else 0L)
            .apply()
        selectedConfig()
    }

    fun selectionRevision(): Long = preferences.getLong(SELECTION_REVISION, 0L)

    fun withCurrentSelection(
        config: ExpressiveUpdateConfig,
        revision: Long,
        action: () -> Unit,
    ): Boolean = synchronized(SELECTION_LOCK) {
        if (selectedConfig() != config || selectionRevision() != revision) return@synchronized false
        action()
        true
    }

    private companion object {
        const val SELECTED_CHANNEL = "selected_channel"
        const val SELECTION_REVISION = "selection_revision"
        val SELECTION_LOCK = Any()
    }
}

internal fun selectedExpressiveUpdateConfig(context: Context): ExpressiveUpdateConfig? =
    ExpressiveUpdateChannelPreferences(context).selectedConfig()
