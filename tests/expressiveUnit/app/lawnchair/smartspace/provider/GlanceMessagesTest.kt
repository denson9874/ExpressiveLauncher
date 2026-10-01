package app.lawnchair.smartspace.provider

import com.android.launcher3.R
import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Test

class GlanceMessagesTest {

    @Test
    fun cakey_seesAnniversaryAndBirthdayAllDay() {
        for (time in listOf(LocalTime.of(0, 5), LocalTime.of(9, 0), LocalTime.of(14, 0), LocalTime.of(23, 30))) {
            val anniversary = selectGlanceMessage(at(2026, 10, 5, time), isCakey = true, region = "US")
            assertThat(anniversary).isEqualTo(
                GlanceMessage(GlanceMessageKind.CAKEY_SPECIAL_DAY, R.string.glance_cakey_anniversary),
            )

            val birthday = selectGlanceMessage(at(2027, 2, 11, time), isCakey = true, region = "US")
            assertThat(birthday).isEqualTo(
                GlanceMessage(GlanceMessageKind.CAKEY_SPECIAL_DAY, R.string.glance_cakey_birthday),
            )
        }
    }

    @Test
    fun everyoneElse_neverSeesLaurasPersonalDates() {
        val anniversary = selectGlanceMessage(at(2026, 10, 5, LocalTime.NOON), isCakey = false, region = "US")
        val birthday = selectGlanceMessage(at(2027, 2, 11, LocalTime.NOON), isCakey = false, region = "US")

        assertThat(anniversary.kind).isNotEqualTo(GlanceMessageKind.CAKEY_SPECIAL_DAY)
        assertThat(birthday.kind).isNotEqualTo(GlanceMessageKind.CAKEY_SPECIAL_DAY)
        assertThat(CAKEY_ONLY_MESSAGES).doesNotContain(anniversary.textResId)
        assertThat(CAKEY_ONLY_MESSAGES).doesNotContain(birthday.textResId)
    }

    @Test
    fun cakey_greetingFollowsTheTimeOfDay() {
        val day = LocalDate.of(2026, 10, 1)
        fun messageAt(hour: Int, minute: Int = 0) =
            selectGlanceMessage(day.atTime(hour, minute), isCakey = true, region = "US")

        assertThat(messageAt(5).textResId).isEqualTo(R.string.cakey_smartspace_morning)
        assertThat(messageAt(11, 59).textResId).isEqualTo(R.string.cakey_smartspace_morning)
        assertThat(messageAt(18).textResId).isEqualTo(R.string.cakey_smartspace_evening)
        assertThat(messageAt(21, 59).textResId).isEqualTo(R.string.cakey_smartspace_evening)
        assertThat(messageAt(22).textResId).isEqualTo(R.string.cakey_smartspace_night)
        assertThat(messageAt(4, 59).textResId).isEqualTo(R.string.cakey_smartspace_night)

        val afternoon = messageAt(12)
        assertThat(afternoon.kind).isEqualTo(GlanceMessageKind.CAKEY_NOTE)
        assertThat(CAKEY_NOTES).contains(afternoon.textResId)
        assertThat(messageAt(17, 59)).isEqualTo(afternoon)
    }

    @Test
    fun cakey_afternoonNoteChangesFromDayToDay() {
        val notes = (0L until CAKEY_NOTES.size.toLong()).map { offset ->
            selectGlanceMessage(
                LocalDate.of(2026, 10, 6).plusDays(offset).atTime(LocalTime.of(14, 0)),
                isCakey = true,
                region = "US",
            ).textResId
        }

        assertThat(notes).containsExactlyElementsIn(CAKEY_NOTES)
    }

    @Test
    fun holidays_showForEveryoneAndCakeyGetsHerOwnVersion() {
        val christmas = at(2026, 12, 25, LocalTime.of(8, 0))
        assertThat(selectGlanceMessage(christmas, isCakey = false, region = "US")).isEqualTo(
            GlanceMessage(GlanceMessageKind.HOLIDAY, R.string.glance_holiday_christmas),
        )
        assertThat(selectGlanceMessage(christmas, isCakey = true, region = "US")).isEqualTo(
            GlanceMessage(GlanceMessageKind.HOLIDAY, R.string.glance_cakey_christmas),
        )

        val halloween = at(2026, 10, 31, LocalTime.of(20, 0))
        assertThat(selectGlanceMessage(halloween, isCakey = true, region = "US")).isEqualTo(
            GlanceMessage(GlanceMessageKind.HOLIDAY, R.string.glance_holiday_halloween),
        )
    }

    @Test
    fun movableHolidays_landOnTheRightDates() {
        assertThat(easterSunday(2026)).isEqualTo(LocalDate.of(2026, 4, 5))
        assertThat(easterSunday(2027)).isEqualTo(LocalDate.of(2027, 3, 28))
        assertThat(easterSunday(2028)).isEqualTo(LocalDate.of(2028, 4, 16))

        assertThat(holidayOn(LocalDate.of(2026, 4, 5), "DE")).isEqualTo(Holiday.EASTER)
        assertThat(holidayOn(LocalDate.of(2026, 5, 10), "US")).isEqualTo(Holiday.MOTHERS_DAY)
        assertThat(holidayOn(LocalDate.of(2026, 6, 21), "US")).isEqualTo(Holiday.FATHERS_DAY)
        assertThat(holidayOn(LocalDate.of(2026, 11, 26), "US")).isEqualTo(Holiday.THANKSGIVING)
        assertThat(holidayOn(LocalDate.of(2027, 11, 25), "US")).isEqualTo(Holiday.THANKSGIVING)
        assertThat(holidayOn(LocalDate.of(2026, 11, 19), "US")).isNull()
    }

    @Test
    fun regionalHolidays_onlyShowInTheirRegion() {
        assertThat(holidayOn(LocalDate.of(2026, 7, 4), "US")).isEqualTo(Holiday.INDEPENDENCE_DAY)
        assertThat(holidayOn(LocalDate.of(2026, 7, 4), "GB")).isNull()
        assertThat(holidayOn(LocalDate.of(2026, 11, 26), "GB")).isNull()
        assertThat(holidayOn(LocalDate.of(2026, 5, 10), "GB")).isNull()
        assertThat(holidayOn(LocalDate.of(2026, 1, 1), "GB")).isEqualTo(Holiday.NEW_YEARS_DAY)
    }

    @Test
    fun ordinaryDays_alternateQuotesAndEncouragementForTheWholeDay() {
        val first = LocalDate.of(2026, 10, 6)
        val days = (0L until 14L).map { first.plusDays(it) }
        val messages = days.map {
            selectGlanceMessage(it.atTime(LocalTime.of(9, 0)), isCakey = false, region = "US")
        }

        assertThat(messages.map { it.kind }.toSet())
            .containsExactly(GlanceMessageKind.QUOTE, GlanceMessageKind.ENCOURAGEMENT)
        messages.zipWithNext().forEach { (today, tomorrow) ->
            assertThat(tomorrow.kind).isNotEqualTo(today.kind)
        }
        assertThat(messages.map { it.textResId }.toSet()).hasSize(14)

        // The same day reads the same from morning to night.
        days.zip(messages).forEach { (day, morning) ->
            val night = selectGlanceMessage(day.atTime(23, 59), isCakey = false, region = "US")
            assertThat(night).isEqualTo(morning)
        }
    }

    private fun at(year: Int, month: Int, day: Int, time: LocalTime): LocalDateTime =
        LocalDate.of(year, month, day).atTime(time)

    private companion object {
        val CAKEY_ONLY_MESSAGES = listOf(
            R.string.glance_cakey_anniversary,
            R.string.glance_cakey_birthday,
        )
    }
}
