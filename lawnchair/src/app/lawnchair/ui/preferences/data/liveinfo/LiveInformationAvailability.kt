package app.lawnchair.ui.preferences.data.liveinfo

/**
 * Live information (remote announcements and default flags) is compiled out of the standard-home
 * product: the dashboard neither syncs nor shows it. Its settings switch must follow that, or it
 * promises announcements that never appear.
 */
object LiveInformationAvailability {
    fun isAvailable(standardHomeOnly: Boolean): Boolean = !standardHomeOnly

    /** The Updates group holds the nightly updater switch and the Live information switch only. */
    fun showsUpdatesGroup(isNightly: Boolean, liveInformationAvailable: Boolean): Boolean =
        isNightly || liveInformationAvailable
}
