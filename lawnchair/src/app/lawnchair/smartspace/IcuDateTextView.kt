package app.lawnchair.smartspace

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.icu.text.DateFormat
import android.icu.text.DisplayContext
import android.os.SystemClock
import android.text.format.DateFormat.is24HourFormat
import android.util.AttributeSet
import androidx.annotation.VisibleForTesting
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import app.lawnchair.smartspace.model.SmartspaceCalendar
import app.lawnchair.smartspace.model.SmartspaceTimeFormat
import app.lawnchair.util.broadcastReceiverFlow
import app.lawnchair.util.repeatOnAttached
import com.android.launcher3.R
import java.util.Locale
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

typealias FormatterFunction = (Long) -> String

class IcuDateTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : DoubleShadowTextView(context, attrs) {

    private val prefs = PreferenceManager2.getInstance(context)
    private var calendar: SmartspaceCalendar = SmartspaceCalendar.Gregorian
    private var dateTimeOptions: DateTimeOptions = DateTimeOptions(
        showDate = true,
        showTime = false,
        timeFormat = SmartspaceTimeFormat.FollowSystem,
    )
    private var formatterFunction: FormatterFunction? = null
    private var isViewVisible = false
    private val ticker = Runnable { onTimeTick() }

    init {
        repeatOnAttached {
            runCatching {
                calendar = prefs.smartspaceCalendar.firstCached(prefs)
                dateTimeOptions = DateTimeOptions.fromCachedPrefs(prefs)
            }
            onTimeChanged(true)
            rescheduleTicker(updateNow = false)

            runCatching {
                val calendarFlow = prefs.smartspaceCalendar.get()
                val optionsFlow = DateTimeOptions.fromPrefs(prefs)
                combine(calendarFlow, optionsFlow) { calendar, options -> calendar to options }
            }.onSuccess { prefsFlow ->
                prefsFlow
                    .distinctUntilChanged()
                    .onEach { (newCalendar, newOptions) ->
                        val changed = calendar != newCalendar || dateTimeOptions != newOptions
                        calendar = newCalendar
                        dateTimeOptions = newOptions
                        if (changed || formatterFunction == null) {
                            onTimeChanged(true)
                            rescheduleTicker(updateNow = false)
                        }
                    }
                    .launchIn(this)
            }

            val intentFilter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_DATE_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
            broadcastReceiverFlow(context, intentFilter)
                .onEach { intent ->
                    val isTimeTick = intent.action == Intent.ACTION_TIME_TICK
                    onTimeChanged(!isTimeTick)
                    if (!isTimeTick) {
                        rescheduleTicker(updateNow = false)
                    }
                }
                .launchIn(this)
        }
    }

    private fun onTimeChanged(updateFormatter: Boolean) {
        if (isShown) {
            val timeText = getTimeText(updateFormatter)
            if (text != timeText) {
                textAlignment = if (shouldAlignToTextEnd()) TEXT_ALIGNMENT_TEXT_END else TEXT_ALIGNMENT_TEXT_START
                text = timeText
                contentDescription = timeText
            }
        } else if (updateFormatter) {
            formatterFunction = null
        }
    }

    private fun shouldAlignToTextEnd(): Boolean {
        val is24HourFormatManual = dateTimeOptions.timeFormat is SmartspaceTimeFormat.TwentyFourHourFormat
        val is24HourFormatOnSystem = dateTimeOptions.timeFormat is SmartspaceTimeFormat.FollowSystem && is24HourFormat(context)
        val is24HourFormat = is24HourFormatManual || is24HourFormatOnSystem
        val shouldNotAlignToEnd = dateTimeOptions.showTime && is24HourFormat && !dateTimeOptions.showDate
        return calendar == SmartspaceCalendar.Persian && !shouldNotAlignToEnd
    }

    private fun getTimeText(updateFormatter: Boolean): String {
        val formatter = getFormatterFunction(updateFormatter)
        return formatter(System.currentTimeMillis())
    }

    private fun getFormatterFunction(updateFormatter: Boolean): FormatterFunction {
        if (formatterFunction != null && !updateFormatter) {
            return formatterFunction!!
        }
        val formatter = when (calendar) {
            SmartspaceCalendar.Persian -> createPersianFormatter()
            SmartspaceCalendar.Lunar -> createLunarFormatter()
            else -> createGregorianFormatter()
        }
        formatterFunction = formatter
        return formatter
    }

    private fun currentTimePattern(): String? {
        if (!dateTimeOptions.showTime) return null
        return context.getString(
            when {
                dateTimeOptions.timeFormat is SmartspaceTimeFormat.TwelveHourFormat ->
                    R.string.smartspace_icu_date_pattern_gregorian_time_12h

                dateTimeOptions.timeFormat is SmartspaceTimeFormat.TwentyFourHourFormat ->
                    R.string.smartspace_icu_date_pattern_gregorian_time

                is24HourFormat(context) -> R.string.smartspace_icu_date_pattern_gregorian_time

                else -> R.string.smartspace_icu_date_pattern_gregorian_time_12h
            },
        )
    }

    internal fun currentTickerMode(): TickerMode = resolveTickerMode(
        showTime = dateTimeOptions.showTime,
        timePattern = currentTimePattern(),
    )

    @VisibleForTesting
    internal fun hasPendingTickerForTesting(): Boolean = handler?.hasCallbacks(ticker) == true

    private fun createLunarFormatter(): FormatterFunction {
        var format: String
        val timePattern = currentTimePattern()
        if (timePattern != null) {
            format = timePattern
            if (dateTimeOptions.showDate) format += context.getString(R.string.smartspace_icu_date_pattern_gregorian_date)
        } else {
            format = context.getString(R.string.smartspace_icu_date_pattern_gregorian_wday_month_day_no_year)
        }

        val chineseLocale = Locale.Builder()
            .setLocale(Locale.CHINESE)
            .setUnicodeLocaleKeyword("ca", "chinese")
            .build()

        val formatter = DateFormat.getInstanceForSkeleton(format, chineseLocale)
        formatter.setContext(DisplayContext.CAPITALIZATION_FOR_STANDALONE)
        return { formatter.format(it) }
    }

    private fun createPersianFormatter(): FormatterFunction {
        var format: String
        val timePattern = currentTimePattern()
        if (timePattern != null) {
            format = timePattern
            if (dateTimeOptions.showDate) format = context.getString(R.string.smartspace_icu_date_pattern_gregorian_date) + format
        } else {
            format = context.getString(R.string.smartspace_icu_date_pattern_gregorian_wday_month_day_no_year)
        }
        val persianLocale = Locale.Builder()
            .setLanguage("fa") // Mimic old Solar Hijri behaviour using Farsi script
            .setExtension('u', "ca-persian")
            .build()

        val formatter = DateFormat.getInstanceForSkeleton(format, persianLocale)
        return { formatter.format(it) }
    }

    private fun createGregorianFormatter(): FormatterFunction {
        var format: String
        val timePattern = currentTimePattern()
        if (timePattern != null) {
            format = timePattern
            if (dateTimeOptions.showDate) format += context.getString(R.string.smartspace_icu_date_pattern_gregorian_date)
        } else {
            format = context.getString(R.string.smartspace_icu_date_pattern_gregorian_wday_month_day_no_year)
        }
        val formatter = DateFormat.getInstanceForSkeleton(format, Locale.getDefault())
        formatter.setContext(DisplayContext.CAPITALIZATION_FOR_STANDALONE)
        return { formatter.format(it) }
    }

    private fun rescheduleTicker(updateNow: Boolean) {
        handler?.removeCallbacks(ticker)
        if (!isViewVisible) return
        if (updateNow) {
            onTimeChanged(false)
        }
        val delayMillis = computeNextTickDelayMillis(
            nowWallMillis = System.currentTimeMillis(),
            mode = currentTickerMode(),
        ) ?: return
        handler?.postAtTime(ticker, SystemClock.uptimeMillis() + delayMillis)
    }

    private fun onTimeTick() {
        onTimeChanged(false)
        if (!isViewVisible) return
        val delayMillis = computeNextTickDelayMillis(
            nowWallMillis = System.currentTimeMillis(),
            mode = currentTickerMode(),
        ) ?: return
        handler?.postAtTime(ticker, SystemClock.uptimeMillis() + delayMillis)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        isViewVisible = isVisible
        rescheduleTicker(updateNow = isVisible)
    }

    override fun onDetachedFromWindow() {
        isViewVisible = false
        handler?.removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }
}

internal enum class TickerMode {
    NONE,
    MINUTE,
    SECOND,
}

internal fun patternDisplaysSeconds(pattern: String?): Boolean {
    if (pattern.isNullOrEmpty()) return false
    var inQuotes = false
    for (ch in pattern) {
        if (ch == '\'') {
            inQuotes = !inQuotes
        } else if (!inQuotes && (ch == 's' || ch == 'S')) {
            return true
        }
    }
    return false
}

internal fun resolveTickerMode(
    showTime: Boolean,
    timePattern: String? = null,
): TickerMode {
    if (!showTime) return TickerMode.NONE
    return if (patternDisplaysSeconds(timePattern)) TickerMode.SECOND else TickerMode.MINUTE
}

internal fun computeNextTickDelayMillis(
    nowWallMillis: Long,
    mode: TickerMode,
): Long? = when (mode) {
    TickerMode.NONE -> null
    TickerMode.SECOND -> 1_000L - Math.floorMod(nowWallMillis, 1_000L)
    TickerMode.MINUTE -> 60_000L - Math.floorMod(nowWallMillis, 60_000L)
}

data class DateTimeOptions(
    val showDate: Boolean,
    val showTime: Boolean,
    val timeFormat: SmartspaceTimeFormat,
) {
    companion object {
        fun fromCachedPrefs(prefs: PreferenceManager2) = DateTimeOptions(
            showDate = prefs.smartspaceShowDate.firstCached(prefs),
            showTime = prefs.smartspaceShowTime.firstCached(prefs),
            timeFormat = prefs.smartspaceTimeFormat.firstCached(prefs),
        )

        fun fromPrefs(prefs: PreferenceManager2) = combine(
            prefs.smartspaceShowDate.get(),
            prefs.smartspaceShowTime.get(),
            prefs.smartspaceTimeFormat.get(),
        ) { showDate, showTime, timeFormat ->
            DateTimeOptions(showDate, showTime, timeFormat)
        }
    }
}
