package app.lawnchair

import app.lawnchair.DeviceProfileOverrides.Companion.foldableDatabaseHotseatIcons
import com.android.launcher3.InvariantDeviceProfile.TYPE_MULTI_DISPLAY
import com.android.launcher3.InvariantDeviceProfile.TYPE_PHONE
import com.android.launcher3.InvariantDeviceProfile.TYPE_TABLET
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FoldableDockDatabaseTest {

    @Test
    fun unfoldedFoldable_keepsTheUnfoldedDock() {
        assertThat(foldableDatabaseHotseatIcons(TYPE_MULTI_DISPLAY, hasHinge = true, foldedColumns = 4, unfoldedColumns = 5))
            .isEqualTo(5)
    }

    @Test
    fun foldedFoldableReportedAsPhone_stillKeepsTheFifthDockSlot() {
        // Folded, only the cover screen is reported, so the device type drops to TYPE_PHONE.
        // The database must keep the unfolded count or the loader deletes the fifth dock app.
        assertThat(foldableDatabaseHotseatIcons(TYPE_PHONE, hasHinge = true, foldedColumns = 4, unfoldedColumns = 5))
            .isEqualTo(5)
    }

    @Test
    fun foldedFoldable_withEqualDocks_keepsTheGridCount() {
        assertThat(foldableDatabaseHotseatIcons(TYPE_PHONE, hasHinge = true, foldedColumns = 5, unfoldedColumns = 5))
            .isEqualTo(-1)
    }

    @Test
    fun phoneWithoutHinge_keepsTheGridCount() {
        assertThat(foldableDatabaseHotseatIcons(TYPE_PHONE, hasHinge = false, foldedColumns = 4, unfoldedColumns = 6))
            .isEqualTo(-1)
    }

    @Test
    fun tablet_keepsTheGridCount() {
        assertThat(foldableDatabaseHotseatIcons(TYPE_TABLET, hasHinge = true, foldedColumns = 4, unfoldedColumns = 6))
            .isEqualTo(-1)
    }
}
