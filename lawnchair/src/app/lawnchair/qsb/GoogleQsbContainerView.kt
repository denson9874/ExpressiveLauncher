package app.lawnchair.qsb

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Process
import android.util.AttributeSet
import com.android.launcher3.qsb.QsbContainerView

class GoogleQsbContainerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : QsbContainerView(context, attrs, defStyleAttr) {

    class QsbFragment : QsbContainerView.QsbFragment() {
        override fun isQsbEnabled(): Boolean = true

        override fun getSearchWidgetProvider(): AppWidgetProviderInfo? {
            val providers = AppWidgetManager.getInstance(context)
                .getInstalledProvidersForPackage(GOOGLE_PACKAGE, Process.myUserHandle())
            val chosen = pickGoogleDockSearchWidget(
                providers.map {
                    SearchWidgetCandidate(
                        className = it.provider.className,
                        category = it.widgetCategory,
                        needsConfiguration = needsMandatoryConfiguration(it.configure != null, it.widgetFeatures),
                    )
                },
            ) ?: return null
            return providers.first { it.provider.className == chosen.className }
        }

    }

    companion object {
        private const val GOOGLE_PACKAGE = "com.google.android.googlequicksearchbox"
    }
}

internal data class SearchWidgetCandidate(
    val className: String,
    val category: Int,
    /** True only when setup is mandatory; Google's search widgets offer optional customization. */
    val needsConfiguration: Boolean,
)

internal fun needsMandatoryConfiguration(hasConfigureActivity: Boolean, widgetFeatures: Int): Boolean =
    hasConfigureActivity && widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL == 0

/** Pixel Launcher's dock widget first, then Google's older search box widget. */
private val PREFERRED_GOOGLE_DOCK_WIDGETS = listOf(
    "com.google.android.apps.gsa.staticplugins.searchwidget.GoogleSearchWidgetProvider",
    "com.google.android.googlequicksearchbox.SearchWidgetProvider",
)

/**
 * Chooses Google's search bar widget for the dock. The generic AOSP lookup skips every widget with a
 * configure activity, which now includes both of Google's search widgets (their customization is
 * optional), and falls back to the home-screen-only Premium widget, which renders empty in the dock.
 */
internal fun pickGoogleDockSearchWidget(candidates: List<SearchWidgetCandidate>): SearchWidgetCandidate? {
    val usable = candidates.filter {
        !it.needsConfiguration && it.category and AppWidgetProviderInfo.WIDGET_CATEGORY_SEARCHBOX != 0
    }
    return PREFERRED_GOOGLE_DOCK_WIDGETS.firstNotNullOfOrNull { name -> usable.firstOrNull { it.className == name } }
        ?: usable.firstOrNull()
}
