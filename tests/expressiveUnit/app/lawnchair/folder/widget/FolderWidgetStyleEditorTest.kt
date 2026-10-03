package app.lawnchair.folder.widget

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FolderWidgetStyleEditorTest {
    @Test fun freeUser_canChangeFreeRows() {
        assertThat(FolderWidgetStyleEditor(false).withColumns(FolderWidgetStyle(), 4).columns).isEqualTo(4)
        assertThat(FolderWidgetStyleEditor(false).withShowHeader(FolderWidgetStyle(), false).showHeader).isFalse()
    }

    @Test fun freeUser_cannotChangeProRows() {
        val e = FolderWidgetStyleEditor(false)
        assertThat(e.withIconScale(FolderWidgetStyle(), 0.8f).iconScale).isNull()
        assertThat(e.withBackgroundColor(FolderWidgetStyle(), 0xFF00FF).backgroundColor).isNull()
    }

    @Test fun proUser_canChangeProRows() {
        assertThat(FolderWidgetStyleEditor(true).withBackgroundOpacity(FolderWidgetStyle(), 0.3f).backgroundOpacity)
            .isEqualTo(0.3f)
    }

    @Test fun reset_returnsDefaults() {
        assertThat(FolderWidgetStyleEditor(true).reset()).isEqualTo(FolderWidgetStyle())
    }
}
