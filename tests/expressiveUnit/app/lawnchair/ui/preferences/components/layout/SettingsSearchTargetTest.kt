package app.lawnchair.ui.preferences.components.layout

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SettingsSearchTargetTest {

    @Test
    fun nothingIsPendingUntilAResultIsChosen() {
        val target = SettingsSearchTarget()

        assertThat(target.hasPending).isFalse()
        assertThat(target.isPending("Return to default page")).isFalse()
    }

    @Test
    fun theChosenSettingIsPending_ignoringCaseAndAccents() {
        val target = SettingsSearchTarget()

        target.request("Return to default page")

        assertThat(target.hasPending).isTrue()
        assertThat(target.isPending("Return to default page")).isTrue()
        assertThat(target.isPending("RETURN TO DEFAULT PAGE")).isTrue()
        assertThat(target.isPending("Retürn to default page")).isTrue()
    }

    @Test
    fun otherRowsAreNotTheTarget() {
        val target = SettingsSearchTarget()

        target.request("Icon size")

        assertThat(target.isPending("Icon shape")).isFalse()
        assertThat(target.isPending("Label size")).isFalse()
    }

    @Test
    fun revealingTheRowClearsTheRequest_soItRunsOnce() {
        val target = SettingsSearchTarget()
        target.request("Show labels")

        target.consume("Show labels")

        assertThat(target.hasPending).isFalse()
        assertThat(target.isPending("Show labels")).isFalse()
    }

    @Test
    fun consumingADifferentRowLeavesTheRequestAlone() {
        val target = SettingsSearchTarget()
        target.request("Show labels")

        target.consume("Label size")

        assertThat(target.isPending("Show labels")).isTrue()
    }

    @Test
    fun aNewRequestReplacesTheOldOne() {
        val target = SettingsSearchTarget()
        target.request("Show labels")

        target.request("Top shadow")

        assertThat(target.isPending("Show labels")).isFalse()
        assertThat(target.isPending("Top shadow")).isTrue()
    }

    @Test
    fun clearingDropsAnUnansweredRequest() {
        val target = SettingsSearchTarget()
        target.request("Charging animation")

        target.clear()

        assertThat(target.hasPending).isFalse()
    }
}
