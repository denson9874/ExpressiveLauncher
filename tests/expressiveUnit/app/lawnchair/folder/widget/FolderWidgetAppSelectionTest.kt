package app.lawnchair.folder.widget

import android.app.Application
import android.content.ComponentName
import android.os.Process
import com.android.launcher3.util.ComponentKey
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class FolderWidgetAppSelectionTest {
    private fun key(p: String) = ComponentKey(ComponentName(p, "$p.Main"), Process.myUserHandle())

    @Test fun diff_keepsOrderAndFindsChanges() {
        val (add, remove) = FolderWidgetAppSelection.diff(listOf(key("a"), key("b")), listOf(key("c"), key("a")))
        assertThat(add).containsExactly(key("c"))
        assertThat(remove).containsExactly(key("b"))
    }

    @Test fun diff_noChanges() {
        val (add, remove) = FolderWidgetAppSelection.diff(listOf(key("a")), listOf(key("a")))
        assertThat(add).isEmpty()
        assertThat(remove).isEmpty()
    }

    @Test fun diff_emptyCurrent() {
        val (add, remove) = FolderWidgetAppSelection.diff(emptyList(), listOf(key("a"), key("b")))
        assertThat(add).containsExactly(key("a"), key("b")).inOrder()
        assertThat(remove).isEmpty()
    }

    @Test fun diff_emptySelected() {
        val (add, remove) = FolderWidgetAppSelection.diff(listOf(key("a"), key("b")), emptyList())
        assertThat(add).isEmpty()
        assertThat(remove).containsExactly(key("a"), key("b")).inOrder()
    }

    @Test
    fun updateWidgetTitle_syncsHeaderAndDatabase() {
        val folderInfo = com.android.launcher3.model.data.FolderInfo().apply {
            title = "Old Title"
        }
        var headerText: CharSequence? = "Old Title"
        val updated = FolderWidgetController.updateWidgetTitle(
            folderInfo,
            null,
            { headerText = it },
            "New Title",
        )
        assertThat(updated).isTrue()
        assertThat(headerText.toString()).isEqualTo("New Title")
        assertThat(folderInfo.title?.toString()).isEqualTo("New Title")
    }

    @Test
    fun updateWidgetTitle_unchangedTitle_noUpdate() {
        val folderInfo = com.android.launcher3.model.data.FolderInfo().apply {
            title = "Same Title"
        }
        var headerUpdated = false
        val updated = FolderWidgetController.updateWidgetTitle(
            folderInfo,
            null,
            { headerUpdated = true },
            "  Same Title  ",
        )
        assertThat(updated).isFalse()
        assertThat(headerUpdated).isFalse()
    }
}
