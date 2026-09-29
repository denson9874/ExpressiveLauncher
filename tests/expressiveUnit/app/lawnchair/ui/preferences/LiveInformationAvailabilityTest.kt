package app.lawnchair.ui.preferences

import app.lawnchair.ui.preferences.data.liveinfo.LiveInformationAvailability
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LiveInformationAvailabilityTest {

    @Test
    fun standardHomeBuilds_doNotOfferLiveInformation_becauseTheyNeverSyncOrShowIt() {
        // PreferencesDashboard skips SyncLiveInformation() and AnnouncementPreference() for the
        // standard-home product, so a switch for them would be a control that does nothing.
        assertThat(LiveInformationAvailability.isAvailable(standardHomeOnly = true)).isFalse()
    }

    @Test
    fun otherBuilds_keepLiveInformation() {
        assertThat(LiveInformationAvailability.isAvailable(standardHomeOnly = false)).isTrue()
    }

    @Test
    fun updatesGroup_isHiddenWhenNoRowWouldBeInIt() {
        assertThat(
            LiveInformationAvailability.showsUpdatesGroup(isNightly = false, liveInformationAvailable = false),
        ).isFalse()
    }

    @Test
    fun updatesGroup_staysWhenEitherRowExists() {
        assertThat(
            LiveInformationAvailability.showsUpdatesGroup(isNightly = true, liveInformationAvailable = false),
        ).isTrue()
        assertThat(
            LiveInformationAvailability.showsUpdatesGroup(isNightly = false, liveInformationAvailable = true),
        ).isTrue()
    }
}
