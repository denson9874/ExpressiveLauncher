package app.lawnchair.smartspace

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.CalendarContract
import android.util.AttributeSet
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import app.lawnchair.font.FontManager
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.smartspace.model.SmartspaceAction
import app.lawnchair.smartspace.model.SmartspaceTarget
import com.android.launcher3.R
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [37],
    application = Application::class,
    instrumentedPackages = ["app.lawnchair.smartspace.BcSmartSpaceUtil"],
    shadows = [
        BcSmartspaceDateClickTest.ShadowFontManager::class,
        BcSmartspaceDateClickTest.ShadowPreferenceManager::class,
    ],
)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
class BcSmartspaceDateClickTest {

    @Test
    fun dateTap_usesCurrentDayOnEveryTapWithoutRebinding() {
        setTime("2026-09-04T23:59:00Z")
        val context = RecordingContext()
        val card = inflateCard(context)
        card.setSmartspaceTarget(dateTarget(), multipleCards = false)
        val date = card.findViewById<View>(R.id.date)

        // The fallback target stays bound while the visible date changes overnight.
        val firstTapTime = setTime("2026-09-05T00:01:00Z")
        assertThat(date.performClick()).isTrue()
        assertCalendarIntent(context.startedActivities.single(), firstTapTime)

        val secondTapTime = setTime("2026-09-06T08:30:00Z")
        assertThat(date.performClick()).isTrue()
        assertThat(context.startedActivities).hasSize(2)
        assertCalendarIntent(context.startedActivities.last(), secondTapTime)
    }

    @Test
    fun dateTap_doesNotReplaceOrExecuteTheWholeCardAction() {
        setTime("2026-09-04T23:59:00Z")
        val context = RecordingContext()
        val card = inflateCard(context)
        var headerClicks = 0
        card.setSmartspaceTarget(
            dateTarget().copy(
                headerAction = SmartspaceAction(
                    id = "weather-action",
                    title = "Weather",
                    onClick = Runnable { headerClicks++ },
                ),
            ),
            multipleCards = false,
        )

        val tapTime = setTime("2026-09-05T00:01:00Z")
        assertThat(card.findViewById<View>(R.id.date).performClick()).isTrue()
        assertCalendarIntent(context.startedActivities.single(), tapTime)
        assertThat(headerClicks).isEqualTo(0)

        assertThat(card.performClick()).isTrue()
        assertThat(headerClicks).isEqualTo(1)
        assertThat(context.startedActivities).hasSize(1)
    }

    @Test
    fun missingCalendarHandler_isContainedAndTheDateCanBeRetried() {
        setTime("2026-09-04T23:59:00Z")
        val context = RecordingContext().apply { calendarAvailable = false }
        val card = inflateCard(context)
        card.setSmartspaceTarget(dateTarget(), multipleCards = false)
        val date = card.findViewById<View>(R.id.date)

        val failedTapTime = setTime("2026-09-05T00:01:00Z")
        assertThat(date.performClick()).isTrue()
        assertThat(context.failedStarts).isEqualTo(1)
        assertThat(context.startedActivities).isEmpty()
        assertCalendarIntent(requireNotNull(context.lastAttempt), failedTapTime)

        context.calendarAvailable = true
        val retryTime = setTime("2026-09-06T08:30:00Z")
        assertThat(date.performClick()).isTrue()
        assertCalendarIntent(context.startedActivities.single(), retryTime)
        assertThat(context.failedStarts).isEqualTo(1)
    }

    private fun inflateCard(context: RecordingContext): BcSmartspaceCard =
        LayoutInflater.from(context).inflate(
            R.layout.smartspace_card_date,
            FrameLayout(context),
            false,
        ) as BcSmartspaceCard

    private fun dateTarget() = SmartspaceTarget(
        id = "date-fallback",
        featureType = SmartspaceTarget.FeatureType.FEATURE_WEATHER,
    )

    private fun setTime(isoTime: String): Long = Instant.parse(isoTime).toEpochMilli().also {
        assertThat(SystemClock.setCurrentTimeMillis(it)).isTrue()
        // PAUSED mode redirects instrumented System.currentTimeMillis calls to this clock.
        assertThat(SystemClock.uptimeMillis()).isEqualTo(it)
    }

    private fun assertCalendarIntent(intent: Intent, timeMillis: Long) {
        assertThat(intent.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(intent.data).isEqualTo(
            ContentUris.withAppendedId(
                CalendarContract.CONTENT_URI.buildUpon().appendPath("time").build(),
                timeMillis,
            ),
        )
        assertThat(intent.flags).isEqualTo(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
        )
    }

    private class RecordingContext : ContextThemeWrapper(
        ApplicationProvider.getApplicationContext<Application>(),
        R.style.LauncherTheme,
    ) {
        val startedActivities = mutableListOf<Intent>()
        var calendarAvailable = true
        var failedStarts = 0
        var lastAttempt: Intent? = null

        override fun startActivity(intent: Intent) {
            lastAttempt = Intent(intent)
            if (!calendarAvailable) {
                failedStarts++
                throw ActivityNotFoundException("No calendar handler installed")
            }
            startedActivities += Intent(intent)
        }
    }

    // Keep real card/date views and their click listeners, but do not initialize the unrelated
    // launcher service graph: its shell flags are unavailable on the Robolectric bootclasspath.
    // These detached layout tests do not render fonts or subscribe to date-format preferences.
    @Implements(FontManager::class)
    class ShadowFontManager {
        @Implementation
        fun __constructor__(context: Context) = Unit

        @Implementation
        fun overrideFont(textView: TextView, attrs: AttributeSet?) = Unit
    }

    @Implements(PreferenceManager2::class)
    class ShadowPreferenceManager {
        @Implementation
        fun __constructor__(context: Context) = Unit
    }
}
