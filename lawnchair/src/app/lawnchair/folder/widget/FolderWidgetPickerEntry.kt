package app.lawnchair.folder.widget

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.widget.ImageView
import com.android.launcher3.R
import com.android.launcher3.widget.LauncherAppWidgetProviderInfo
import com.android.launcher3.widget.LauncherAppWidgetProviderInfo.CLS_CUSTOM_WIDGET_PREFIX
import com.android.launcher3.widget.custom.CustomAppWidgetProviderInfo
import com.android.launcher3.widget.custom.CustomWidgetManager
import com.android.systemui.plugins.CustomWidgetPlugin

/**
 * Built-in custom widget entry for the Folder widget in Launcher3's widget picker.
 */
class FolderWidgetPickerEntry : CustomWidgetPlugin {

    override fun updateWidgetInfo(info: AppWidgetProviderInfo, context: Context) {
        val labelStr = context.getString(R.string.folder_widget_label)
        if (info is CustomAppWidgetProviderInfo) {
            info.label = labelStr
        }
        if (info is LauncherAppWidgetProviderInfo) {
            info.spanX = 2
            info.spanY = 2
            info.minSpanX = 1
            info.minSpanY = 1
        }
        info.configure = null
        info.resizeMode = AppWidgetProviderInfo.RESIZE_BOTH
        info.previewImage = R.drawable.folder_widget_preview
    }

    override fun onViewCreated(parent: AppWidgetHostView) {
        val preview = ImageView(parent.context).apply {
            setImageResource(R.drawable.folder_widget_preview)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        parent.addView(preview)
    }

    companion object {
        const val PROVIDER_SUFFIX = "folder"
        const val CLS_FOLDER_WIDGET = CLS_CUSTOM_WIDGET_PREFIX + PROVIDER_SUFFIX

        @JvmStatic
        fun provider(context: Context): ComponentName =
            ComponentName(context.packageName, CLS_FOLDER_WIDGET)

        @JvmStatic
        fun isFolderWidget(componentName: ComponentName?): Boolean =
            componentName?.className == CLS_FOLDER_WIDGET

        private var registered = false

        @JvmStatic
        fun register(context: Context) {
            if (registered) return
            registered = true
            CustomWidgetManager.INSTANCE.get(context).addBuiltInWidget(
                provider(context),
                FolderWidgetPickerEntry(),
            )
        }
    }
}
