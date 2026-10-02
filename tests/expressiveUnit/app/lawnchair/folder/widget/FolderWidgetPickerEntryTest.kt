package app.lawnchair.folder.widget

import android.app.Application
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import com.android.launcher3.R
import com.android.launcher3.widget.LauncherAppWidgetProviderInfo
import com.android.launcher3.widget.LauncherAppWidgetProviderInfo.CLS_CUSTOM_WIDGET_PREFIX
import com.android.launcher3.widget.custom.CustomAppWidgetProviderInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class FolderWidgetPickerEntryTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun isFolderWidget_identifiesProvider() {
        val valid = ComponentName(context.packageName, CLS_CUSTOM_WIDGET_PREFIX + "folder")
        val invalid = ComponentName(context.packageName, CLS_CUSTOM_WIDGET_PREFIX + "other")
        assertThat(FolderWidgetPickerEntry.isFolderWidget(valid)).isTrue()
        assertThat(FolderWidgetPickerEntry.isFolderWidget(invalid)).isFalse()
        assertThat(FolderWidgetPickerEntry.isFolderWidget(null)).isFalse()
    }

    @Test
    fun updateWidgetInfo_configuresSpansAndLabel() {
        val entry = FolderWidgetPickerEntry()
        val info = CustomAppWidgetProviderInfo()
        entry.updateWidgetInfo(info, context)

        assertThat(info.label).isEqualTo(context.getString(R.string.folder_widget_label))
        assertThat(info.spanX).isEqualTo(2)
        assertThat(info.spanY).isEqualTo(2)
        assertThat(info.minSpanX).isEqualTo(1)
        assertThat(info.minSpanY).isEqualTo(1)
        assertThat(info.resizeMode).isEqualTo(AppWidgetProviderInfo.RESIZE_BOTH)
        assertThat(info.previewImage).isEqualTo(R.drawable.folder_widget_preview)
    }
}
