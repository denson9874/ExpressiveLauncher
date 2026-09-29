package app.lawnchair.qsb

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.qsb.QsbContainerView.QsbFragment

internal enum class GoogleSearchBarStep {
    /** The Google app offers no dock search widget. */
    UNAVAILABLE,

    /** A binding exists or Android allowed one silently; switch the dock now. */
    READY,

    /** Show Android's "Create widget and allow access?" confirmation. */
    ASK_ANDROID,
}

/** Android is asked only here, after the user turns the switch on; never on launcher start. */
internal fun googleSearchBarStep(
    providerAvailable: Boolean,
    alreadyBound: Boolean,
    boundWithoutAsking: Boolean,
): GoogleSearchBarStep = when {
    !providerAvailable -> GoogleSearchBarStep.UNAVAILABLE
    alreadyBound || boundWithoutAsking -> GoogleSearchBarStep.READY
    else -> GoogleSearchBarStep.ASK_ANDROID
}

/**
 * Binds Google's dock search widget into the dock's widget host and ID slot, so that when the dock
 * switches to Google's bar it finds the binding already in place.
 */
internal object GoogleSearchBarSetup {
    sealed interface Prepared {
        data object Unavailable : Prepared
        data object Ready : Prepared
        data class AskAndroid(val intent: Intent, val widgetId: Int) : Prepared
    }

    fun prepare(context: Context): Prepared {
        val provider = GoogleQsbContainerView.findGoogleDockSearchWidget(context)
        val manager = AppWidgetManager.getInstance(context)
        val prefs = LauncherPrefs.getPrefs(context)
        val savedId = prefs.getInt(QsbFragment.KEY_QSB_WIDGET_ID, -1)
        val alreadyBound = provider != null && manager.getAppWidgetInfo(savedId)?.provider == provider.provider
        var newId = -1
        var boundWithoutAsking = false
        if (provider != null && !alreadyBound) {
            newId = host(context).allocateAppWidgetId()
            boundWithoutAsking = manager.bindAppWidgetIdIfAllowed(newId, provider.profile, provider.provider, null)
        }
        return when (googleSearchBarStep(provider != null, alreadyBound, boundWithoutAsking)) {
            GoogleSearchBarStep.UNAVAILABLE -> Prepared.Unavailable
            GoogleSearchBarStep.READY -> {
                if (newId != -1) useWidget(context, newId)
                Prepared.Ready
            }
            GoogleSearchBarStep.ASK_ANDROID -> Prepared.AskAndroid(
                Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, newId)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider!!.provider)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, provider.profile),
                newId,
            )
        }
    }

    /** Keeps the widget when Android granted it; otherwise releases the unused ID. */
    fun onAndroidAnswered(context: Context, widgetId: Int, granted: Boolean) {
        if (widgetId == -1) return
        if (granted) useWidget(context, widgetId) else host(context).deleteAppWidgetId(widgetId)
    }

    private fun useWidget(context: Context, widgetId: Int) {
        val prefs = LauncherPrefs.getPrefs(context)
        val previous = prefs.getInt(QsbFragment.KEY_QSB_WIDGET_ID, -1)
        if (previous != -1 && previous != widgetId) host(context).deleteAppWidgetId(previous)
        prefs.edit().putInt(QsbFragment.KEY_QSB_WIDGET_ID, widgetId).apply()
    }

    private fun host(context: Context) = AppWidgetHost(context, QsbFragment.QSB_WIDGET_HOST_ID)
}
