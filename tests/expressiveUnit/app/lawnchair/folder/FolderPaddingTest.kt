package app.lawnchair.folder

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.launcher3.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FolderPaddingTest {

    @Test
    fun folderDimensions_haveTightenedVerticalPaddingAndFooterHeight() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val res = context.resources
        val density = res.displayMetrics.density

        val topPaddingPx = res.getDimensionPixelSize(R.dimen.folder_top_padding_default)
        val footerHeightPx = res.getDimensionPixelSize(R.dimen.folder_footer_height_default)
        val contentPaddingTopPx = res.getDimensionPixelSize(R.dimen.folder_content_padding_top)

        // TG-010: Verify folder top padding and footer heights are tightened to eliminate excess blank space
        val topPaddingDp = topPaddingPx / density
        val footerHeightDp = footerHeightPx / density
        val contentPaddingTopDp = contentPaddingTopPx / density

        assertThat(topPaddingDp).isEqualTo(12f)
        assertThat(footerHeightDp).isEqualTo(44f)
        assertThat(contentPaddingTopDp).isEqualTo(12f)
    }
}
