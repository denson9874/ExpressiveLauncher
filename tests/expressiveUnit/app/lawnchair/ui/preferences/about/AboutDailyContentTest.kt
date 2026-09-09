package app.lawnchair.ui.preferences.about

import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertThrows
import org.junit.Test

class AboutDailyContentTest {

    @Test
    fun sameLocalDate_isStableAcrossRecreation() {
        val date = LocalDate.of(2026, 8, 26)

        val firstProcess = dailyAboutContent(date)
        val recreatedProcess = dailyAboutContent(date)

        assertThat(recreatedProcess).isEqualTo(firstProcess)
    }

    @Test
    fun nextLocalDate_rotatesSnarkRiddleAndQuote() {
        val today = dailyAboutContent(LocalDate.of(2026, 8, 26))
        val tomorrow = dailyAboutContent(LocalDate.of(2026, 8, 27))

        assertThat(tomorrow.snarkPack.id).isNotEqualTo(today.snarkPack.id)
        assertThat(tomorrow.riddle.id).isNotEqualTo(today.riddle.id)
        assertThat(tomorrow.quote.id).isNotEqualTo(today.quote.id)
        assertThat(tomorrow.dayKey).isEqualTo(today.dayKey + 1)
    }

    @Test
    fun rotationCyclesOnlyAfterEveryEntryHasAppeared() {
        val firstDate = LocalDate.of(2026, 8, 26)
        val week = (0L until 7L).map { dailyAboutContent(firstDate.plusDays(it)) }

        assertThat(week.map { it.snarkPack.id }).containsNoDuplicates()
        assertThat(week.map { it.riddle.id }).containsNoDuplicates()
        assertThat(week.map { it.quote.id }).containsNoDuplicates()
        assertThat(dailyAboutContent(firstDate.plusDays(7))).isEqualTo(
            dailyAboutContent(firstDate).copy(localDate = firstDate.plusDays(7)),
        )
    }

    @Test
    fun datesBeforeUnixEpoch_useSafePositiveIndexes() {
        val content = dailyAboutContent(LocalDate.of(1900, 1, 1))

        assertThat(content.snarkPack).isIn(ABOUT_DAILY_SNARK_PACKS)
        assertThat(content.riddle).isIn(ABOUT_DAILY_RIDDLES)
        assertThat(content.quote).isIn(ABOUT_DAILY_QUOTES)
    }

    @Test
    fun catalogs_haveStableUniqueIdsAndFiveTapPacks() {
        assertThat(ABOUT_DAILY_SNARK_PACKS.map { it.id }).containsNoDuplicates()
        assertThat(ABOUT_DAILY_RIDDLES.map { it.id }).containsNoDuplicates()
        assertThat(ABOUT_DAILY_QUOTES.map { it.id }).containsNoDuplicates()
        assertThat(ABOUT_DAILY_SNARK_PACKS.map { it.messageResIds.size }.toSet())
            .containsExactly(ABOUT_EASTER_EGG_TAP_COUNT)
    }

    @Test
    fun malformedSnarkPack_isRejectedBeforeItCanCrashATap() {
        assertThrows(IllegalArgumentException::class.java) {
            DailySnarkPack(id = "broken", messageResIds = listOf(1, 2, 3, 4))
        }
    }

    @Test
    fun springDaylightSavingBoundary_usesTwentyThreeHourLocalDay() {
        val newYork = ZoneId.of("America/New_York")
        val springForwardMidnight = Instant.parse("2026-03-08T05:00:00Z")

        assertThat(millisUntilNextLocalMidnight(springForwardMidnight, newYork))
            .isEqualTo(Duration.ofHours(23).toMillis())
    }

    @Test
    fun fallDaylightSavingBoundary_usesTwentyFiveHourLocalDay() {
        val newYork = ZoneId.of("America/New_York")
        val fallBackMidnight = Instant.parse("2026-11-01T04:00:00Z")

        assertThat(millisUntilNextLocalMidnight(fallBackMidnight, newYork))
            .isEqualTo(Duration.ofHours(25).toMillis())
    }

    @Test
    fun sameInstantInDifferentTimeZones_usesEachZonesLocalDate() {
        val instant = Instant.parse("2026-08-26T01:00:00Z")
        val newYorkDate = instant.atZone(ZoneId.of("America/New_York")).toLocalDate()
        val tokyoDate = instant.atZone(ZoneId.of("Asia/Tokyo")).toLocalDate()

        assertThat(newYorkDate).isEqualTo(LocalDate.of(2026, 8, 25))
        assertThat(tokyoDate).isEqualTo(LocalDate.of(2026, 8, 26))
        assertThat(dailyAboutContent(newYorkDate).dayKey)
            .isNotEqualTo(dailyAboutContent(tokyoDate).dayKey)
    }

    @Test
    fun savedInteractionState_restoresOnlyOnTheDayThatCreatedIt() {
        val savedDay = LocalDate.of(2026, 8, 26).toEpochDay()
        val nextDay = savedDay + 1
        val savedTapCount = longArrayOf(savedDay, 4L)
        val savedVisibility = longArrayOf(savedDay, 1L)

        assertThat(
            dailyScopedIntSaver(savedDay) { it in 0 until ABOUT_EASTER_EGG_TAP_COUNT }
                .restore(savedTapCount),
        ).isEqualTo(4)
        assertThat(dailyScopedBooleanSaver(savedDay).restore(savedVisibility)).isTrue()

        // Compose does not validate rememberSaveable inputs when restoring after process death.
        // The saver itself must reject yesterday's payload to avoid a one-tap celebration today.
        assertThat(
            dailyScopedIntSaver(nextDay) { it in 0 until ABOUT_EASTER_EGG_TAP_COUNT }
                .restore(savedTapCount),
        ).isNull()
        assertThat(dailyScopedBooleanSaver(nextDay).restore(savedVisibility)).isNull()
    }

    @Test
    fun savedInteractionState_rejectsMalformedValues() {
        val day = LocalDate.of(2026, 8, 26).toEpochDay()

        assertThat(
            dailyScopedIntSaver(day) { it in 0 until ABOUT_EASTER_EGG_TAP_COUNT }
                .restore(longArrayOf(day, ABOUT_EASTER_EGG_TAP_COUNT.toLong())),
        ).isNull()
        assertThat(dailyScopedBooleanSaver(day).restore(longArrayOf(day, 2L))).isNull()
        assertThat(dailyScopedBooleanSaver(day).restore(longArrayOf(day))).isNull()
    }
}
