package app.lawnchair.preferences

import com.android.launcher3.InvariantDeviceProfile.TYPE_DESKTOP
import com.android.launcher3.InvariantDeviceProfile.TYPE_MULTI_DISPLAY
import com.android.launcher3.InvariantDeviceProfile.TYPE_PHONE
import com.android.launcher3.InvariantDeviceProfile.TYPE_TABLET
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DefaultLayoutConfigTest {

    @Test
    fun phoneDatabase_hasRoomForEveryPixelDefaultDockApp() {
        assertThat(defaultLayoutConfig(TYPE_PHONE))
            .isEqualTo(LayoutConfig(hotseatColumns = 5, workspaceColumns = 4, workspaceRows = 6))
    }

    @Test
    fun foldableDatabase_hasFiveFoldedSlotsAndSixUnfoldedSlots() {
        assertThat(defaultLayoutConfig(TYPE_MULTI_DISPLAY)).isEqualTo(
            LayoutConfig(
                hotseatColumns = 5,
                workspaceColumns = 4,
                workspaceRows = 6,
                hotseatColumnsUnfolded = 6,
            ),
        )
    }

    @Test
    fun largeScreens_keepSixDockSlots() {
        assertThat(defaultLayoutConfig(TYPE_TABLET).hotseatColumns).isEqualTo(6)
        assertThat(defaultLayoutConfig(TYPE_DESKTOP).hotseatColumns).isEqualTo(6)
    }

    @Test
    fun unknownDeviceType_stillFitsTheFiveDefaultDockApps() {
        assertThat(defaultLayoutConfig(Int.MIN_VALUE).hotseatColumns).isAtLeast(5)
    }
}
