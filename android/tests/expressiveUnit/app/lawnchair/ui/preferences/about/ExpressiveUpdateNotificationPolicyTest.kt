package app.lawnchair.ui.preferences.about

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExpressiveUpdateNotificationPolicyTest {

    @Test
    fun currentOrOlderBuild_neverNotifies() {
        assertThat(shouldPost(current = 7, available = 7)).isFalse()
        assertThat(shouldPost(current = 7, available = 6)).isFalse()
    }

    @Test
    fun newBuild_notifiesOnlyOnceUntilSnoozed() {
        assertThat(shouldPost(current = 7, available = 8)).isTrue()
        assertThat(
            shouldPost(
                current = 7,
                available = 8,
                snapshot = snapshot(lastNotified = 8),
            ),
        ).isFalse()
    }

    @Test
    fun matchingVersionSnooze_blocksUntilPresetExpires() {
        val snapshot = snapshot(
            snoozedVersion = 8,
            snoozeUntil = NOW + ExpressiveUpdateSnoozeOption.ONE_DAY.durationMillis,
        )

        assertThat(shouldPost(7, 8, snapshot, NOW)).isFalse()
        assertThat(
            shouldPost(
                7,
                8,
                snapshot,
                NOW + ExpressiveUpdateSnoozeOption.ONE_DAY.durationMillis,
            ),
        ).isTrue()
    }

    @Test
    fun newerBuild_bypassesPreviousVersionSnoozeAndNotification() {
        val snapshot = snapshot(
            lastNotified = 8,
            snoozedVersion = 8,
            snoozeUntil = NOW + ExpressiveUpdateSnoozeOption.ONE_WEEK.durationMillis,
        )

        assertThat(shouldPost(7, 9, snapshot, NOW)).isTrue()
    }

    @Test
    fun presets_haveExpectedDurations() {
        assertThat(ExpressiveUpdateSnoozeOption.entries.map { it.durationMillis }).containsExactly(
            3_600_000L,
            86_400_000L,
            259_200_000L,
            604_800_000L,
        ).inOrder()
    }

    private fun shouldPost(
        current: Long,
        available: Long,
        snapshot: ExpressiveUpdateNotificationSnapshot = snapshot(),
        now: Long = NOW,
    ) = shouldPostExpressiveUpdateNotification(
        currentVersionCode = current,
        availableVersionCode = available,
        snapshot = snapshot,
        nowMillis = now,
    )

    private fun snapshot(
        lastNotified: Long = 0,
        snoozedVersion: Long = 0,
        snoozeUntil: Long = 0,
    ) = ExpressiveUpdateNotificationSnapshot(
        lastNotifiedVersionCode = lastNotified,
        snoozedVersionCode = snoozedVersion,
        snoozeUntilMillis = snoozeUntil,
    )

    private companion object {
        const val NOW = 1_000_000L
    }
}
