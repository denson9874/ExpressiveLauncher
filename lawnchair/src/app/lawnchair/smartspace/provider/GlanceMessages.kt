/*
 * Copyright 2026, Expressive Launcher contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.lawnchair.smartspace.provider

import androidx.annotation.StringRes
import com.android.launcher3.R
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Month
import java.time.MonthDay
import java.time.temporal.TemporalAdjusters

internal enum class GlanceMessageKind {
    CAKEY_SPECIAL_DAY,
    HOLIDAY,
    CAKEY_GREETING,
    CAKEY_NOTE,
    QUOTE,
    ENCOURAGEMENT,
}

internal data class GlanceMessage(
    val kind: GlanceMessageKind,
    @StringRes val textResId: Int,
)

/** Laura's own days. Only a Cakey Edition license ever shows these. */
internal data class CakeySpecialDate(
    val monthDay: MonthDay,
    @StringRes val messageResId: Int,
)

internal val CAKEY_SPECIAL_DATES = listOf(
    CakeySpecialDate(MonthDay.of(Month.FEBRUARY, 11), R.string.glance_cakey_birthday),
    CakeySpecialDate(MonthDay.of(Month.OCTOBER, 5), R.string.glance_cakey_anniversary),
)

/**
 * Holidays shown to everyone who turns daily messages on. A null [regions] means everywhere;
 * otherwise the device's region (ISO 3166 country) must match.
 */
internal enum class Holiday(
    @StringRes val messageResId: Int,
    @StringRes val cakeyMessageResId: Int? = null,
    private val regions: Set<String>? = null,
    private val dateIn: (year: Int) -> LocalDate,
) {
    NEW_YEARS_DAY(
        R.string.glance_holiday_new_year,
        R.string.glance_cakey_new_year,
        dateIn = { LocalDate.of(it, Month.JANUARY, 1) },
    ),
    VALENTINES_DAY(
        R.string.glance_holiday_valentines,
        R.string.glance_cakey_valentines,
        dateIn = { LocalDate.of(it, Month.FEBRUARY, 14) },
    ),
    ST_PATRICKS_DAY(
        R.string.glance_holiday_st_patricks,
        dateIn = { LocalDate.of(it, Month.MARCH, 17) },
    ),
    EASTER(
        R.string.glance_holiday_easter,
        dateIn = ::easterSunday,
    ),
    MOTHERS_DAY(
        R.string.glance_holiday_mothers_day,
        regions = setOf("US", "CA"),
        dateIn = { nthWeekday(it, Month.MAY, DayOfWeek.SUNDAY, 2) },
    ),
    FATHERS_DAY(
        R.string.glance_holiday_fathers_day,
        regions = setOf("US", "CA", "GB"),
        dateIn = { nthWeekday(it, Month.JUNE, DayOfWeek.SUNDAY, 3) },
    ),
    INDEPENDENCE_DAY(
        R.string.glance_holiday_independence_day,
        regions = setOf("US"),
        dateIn = { LocalDate.of(it, Month.JULY, 4) },
    ),
    HALLOWEEN(
        R.string.glance_holiday_halloween,
        dateIn = { LocalDate.of(it, Month.OCTOBER, 31) },
    ),
    THANKSGIVING(
        R.string.glance_holiday_thanksgiving,
        regions = setOf("US"),
        dateIn = { nthWeekday(it, Month.NOVEMBER, DayOfWeek.THURSDAY, 4) },
    ),
    CHRISTMAS_EVE(
        R.string.glance_holiday_christmas_eve,
        dateIn = { LocalDate.of(it, Month.DECEMBER, 24) },
    ),
    CHRISTMAS(
        R.string.glance_holiday_christmas,
        R.string.glance_cakey_christmas,
        dateIn = { LocalDate.of(it, Month.DECEMBER, 25) },
    ),
    NEW_YEARS_EVE(
        R.string.glance_holiday_new_years_eve,
        dateIn = { LocalDate.of(it, Month.DECEMBER, 31) },
    ),
    ;

    fun isOn(date: LocalDate, region: String): Boolean =
        (regions == null || region.uppercase() in regions) && dateIn(date.year) == date
}

internal val GLANCE_QUOTES = listOf(
    R.string.glance_quote_1,
    R.string.glance_quote_2,
    R.string.glance_quote_3,
    R.string.glance_quote_4,
    R.string.glance_quote_5,
    R.string.glance_quote_6,
    R.string.glance_quote_7,
)

internal val GLANCE_ENCOURAGEMENTS = listOf(
    R.string.glance_encouragement_1,
    R.string.glance_encouragement_2,
    R.string.glance_encouragement_3,
    R.string.glance_encouragement_4,
    R.string.glance_encouragement_5,
    R.string.glance_encouragement_6,
    R.string.glance_encouragement_7,
)

/** Afternoon notes for Laura, one per day. */
internal val CAKEY_NOTES = listOf(
    R.string.cakey_smartspace_afternoon,
    R.string.glance_cakey_note_1,
    R.string.glance_cakey_note_2,
    R.string.glance_cakey_note_3,
    R.string.glance_cakey_note_4,
    R.string.glance_cakey_note_5,
    R.string.glance_cakey_note_6,
)

internal fun holidayOn(date: LocalDate, region: String): Holiday? =
    Holiday.entries.firstOrNull { it.isOn(date, region) }

/**
 * Picks the message for [now]. The order matters: Laura's own days, then holidays, then her
 * time-of-day greetings. Everyone else gets a quote or an encouraging word that stays the same
 * all day.
 */
internal fun selectGlanceMessage(
    now: LocalDateTime,
    isCakey: Boolean,
    region: String,
): GlanceMessage {
    val date = now.toLocalDate()
    if (isCakey) {
        CAKEY_SPECIAL_DATES.firstOrNull { it.monthDay == MonthDay.from(date) }?.let {
            return GlanceMessage(GlanceMessageKind.CAKEY_SPECIAL_DAY, it.messageResId)
        }
    }
    holidayOn(date, region)?.let { holiday ->
        val resId = holiday.cakeyMessageResId?.takeIf { isCakey } ?: holiday.messageResId
        return GlanceMessage(GlanceMessageKind.HOLIDAY, resId)
    }
    val day = date.toEpochDay()
    if (isCakey) {
        return when (now.hour) {
            in 5..11 -> GlanceMessage(GlanceMessageKind.CAKEY_GREETING, R.string.cakey_smartspace_morning)
            in 12..17 -> GlanceMessage(GlanceMessageKind.CAKEY_NOTE, CAKEY_NOTES.rotating(day))
            in 18..21 -> GlanceMessage(GlanceMessageKind.CAKEY_GREETING, R.string.cakey_smartspace_evening)
            else -> GlanceMessage(GlanceMessageKind.CAKEY_GREETING, R.string.cakey_smartspace_night)
        }
    }
    // Alternate quotes and encouragement so neither repeats for two weeks.
    val cycle = Math.floorDiv(day, 2L)
    return if (Math.floorMod(day, 2L) == 0L) {
        GlanceMessage(GlanceMessageKind.QUOTE, GLANCE_QUOTES.rotating(cycle))
    } else {
        GlanceMessage(GlanceMessageKind.ENCOURAGEMENT, GLANCE_ENCOURAGEMENTS.rotating(cycle))
    }
}

/** Western (Gregorian) Easter Sunday, using the anonymous Gregorian algorithm. */
internal fun easterSunday(year: Int): LocalDate {
    val a = year % 19
    val b = year / 100
    val c = year % 100
    val d = b / 4
    val e = b % 4
    val f = (b + 8) / 25
    val g = (b - f + 1) / 3
    val h = (19 * a + b - d - g + 15) % 30
    val i = c / 4
    val k = c % 4
    val l = (32 + 2 * e + 2 * i - h - k) % 7
    val m = (a + 11 * h + 22 * l) / 451
    val month = (h + l - 7 * m + 114) / 31
    val day = ((h + l - 7 * m + 114) % 31) + 1
    return LocalDate.of(year, month, day)
}

private fun nthWeekday(year: Int, month: Month, dayOfWeek: DayOfWeek, n: Int): LocalDate =
    LocalDate.of(year, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, dayOfWeek))

private fun List<Int>.rotating(index: Long): Int = get(Math.floorMod(index, size.toLong()).toInt())
