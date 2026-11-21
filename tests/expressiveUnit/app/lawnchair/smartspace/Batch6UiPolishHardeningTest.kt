package app.lawnchair.smartspace

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import android.view.LayoutInflater
import android.view.View
import android.view.View.MeasureSpec
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.test.core.app.ApplicationProvider
import app.lawnchair.NotificationManager
import app.lawnchair.animation.PhysicsAnimator
import app.lawnchair.nexuslauncher.OverlayCallbackImpl
import app.lawnchair.smartspace.provider.MediaListener
import app.lawnchair.smartspace.provider.SmartspaceWidgetReader
import app.lawnchair.ui.preferences.destinations.SettingsSearchIndex
import app.lawnchair.ui.preferences.destinations.SettingsSearchProduct
import com.android.launcher3.R
import com.google.android.libraries.launcherclient.BaseClientService
import com.google.android.libraries.launcherclient.LauncherClient
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [37],
    application = Application::class,
    shadows = [BcSmartspaceDateClickTest.ShadowFontManager::class],
)
@ConscryptMode(ConscryptMode.Mode.OFF)
class Batch6UiPolishHardeningTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun icuDateTextView_dateOnlyHasNoTicker_andMinuteSecondAlignmentIsExact() {
        assertThat(resolveTickerMode(showTime = false, timePattern = "HH:mm")).isEqualTo(TickerMode.NONE)
        assertThat(resolveTickerMode(showTime = false, timePattern = "HH:mm:ss")).isEqualTo(TickerMode.NONE)
        assertThat(computeNextTickDelayMillis(nowWallMillis = 12_345L, mode = TickerMode.NONE)).isNull()

        assertThat(resolveTickerMode(showTime = true, timePattern = "HH:mm")).isEqualTo(TickerMode.MINUTE)
        assertThat(resolveTickerMode(showTime = true, timePattern = "hh:mm aa")).isEqualTo(TickerMode.MINUTE)
        assertThat(resolveTickerMode(showTime = true, timePattern = "HH:mm 's'")).isEqualTo(TickerMode.MINUTE)
        assertThat(computeNextTickDelayMillis(nowWallMillis = 12_345L, mode = TickerMode.MINUTE))
            .isEqualTo(47_655L)
        assertThat(computeNextTickDelayMillis(nowWallMillis = 60_000L, mode = TickerMode.MINUTE))
            .isEqualTo(60_000L)

        assertThat(resolveTickerMode(showTime = true, timePattern = "HH:mm:ss")).isEqualTo(TickerMode.SECOND)
        assertThat(computeNextTickDelayMillis(nowWallMillis = 12_345L, mode = TickerMode.SECOND))
            .isEqualTo(655L)

        // Real attached view in default date-only configuration (non-null Handler) must schedule zero ticks.
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setTheme(R.style.AppTheme_Dark)
        val host = FrameLayout(activity)
        val dateView = IcuDateTextView(activity)
        host.addView(dateView)
        activity.setContentView(host)
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(dateView.handler).isNotNull()
        assertThat(dateView.currentTickerMode()).isEqualTo(TickerMode.NONE)
        dateView.onVisibilityAggregated(true)
        assertThat(dateView.hasPendingTickerForTesting()).isFalse()
    }

    @Test
    fun mediaListener_onPause_tearsDownControllersStateAndCallbacks() {
        val notificationManager = NotificationManager.INSTANCE.get(context)
        val mediaSession = MediaSession(context, "batch6-test-session")
        try {
            mediaSession.setMetadata(
                MediaMetadata.Builder()
                    .putText(MediaMetadata.METADATA_KEY_TITLE, "Test Track")
                    .putText(MediaMetadata.METADATA_KEY_ARTIST, "Test Artist")
                    .build(),
            )
            mediaSession.setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                    .build(),
            )
            val notification = Notification.Builder(context, "media")
                .setContentTitle("Test Track")
                .setStyle(Notification.MediaStyle().setMediaSession(mediaSession.sessionToken))
                .build().apply {
                    extras.putParcelable(Notification.EXTRA_MEDIA_SESSION, mediaSession.sessionToken)
                }
            val sbn = StatusBarNotification(
                context.packageName,
                context.packageName,
                42,
                "media",
                Process.myUid(),
                Process.myPid(),
                notification,
                UserHandle.getUserHandleForUid(Process.myUid()),
                null,
                System.currentTimeMillis(),
            )
            notificationManager.onNotificationPosted(sbn)

            val listener = MediaListener(context) { }
            listener.onResume()
            shadowOf(Looper.getMainLooper()).idle()

            assertThat(listener.isActiveForTesting).isTrue()
            assertThat(listener.trackedControllerCountForTesting).isAtLeast(1)

            // Queue a handler callback before pausing to verify onPause() removes pending messages.
            listener.onMetadataChanged(null)
            assertThat(listener.hasPendingCallbacksForTesting()).isTrue()

            listener.onPause()

            assertThat(listener.isActiveForTesting).isFalse()
            assertThat(listener.trackedControllerCountForTesting).isEqualTo(0)
            assertThat(listener.tracking).isNull()
            assertThat(listener.`package`).isNull()
            assertThat(listener.hasPendingCallbacksForTesting()).isFalse()

            // Post-pause callbacks from an in-flight MediaController must not re-arm tracking.
            listener.onPlaybackStateChanged(null)
            assertThat(listener.trackedControllerCountForTesting).isEqualTo(0)
            assertThat(listener.hasPendingCallbacksForTesting()).isFalse()
        } finally {
            mediaSession.release()
            notificationManager.close()
        }
    }

    @Test
    fun overlayScrollClamp_andReconnectBackoff_enforceBounds() {
        val cases = listOf(
            -2.0f to 0f,
            -0.01f to 0f,
            0f to 0f,
            0.35f to 0.35f,
            1f to 1f,
            1.5f to 1f,
            Float.NaN to 0f,
            Float.NEGATIVE_INFINITY to 0f,
            Float.POSITIVE_INFINITY to 1f,
        )
        for ((input, expected) in cases) {
            assertThat(OverlayCallbackImpl.clampOverlayScroll(input)).isEqualTo(expected)
            assertThat(LauncherClient.clampScrollProgress(input)).isEqualTo(expected)
        }

        assertThat(BaseClientService.computeReconnectDelayMs(0)).isEqualTo(1_000L)
        assertThat(BaseClientService.computeReconnectDelayMs(1)).isEqualTo(2_000L)
        assertThat(BaseClientService.computeReconnectDelayMs(2)).isEqualTo(4_000L)
        assertThat(BaseClientService.computeReconnectDelayMs(10)).isEqualTo(60_000L)
    }

    @Test
    fun bcSmartspaceView_cancelDetachAndReducedMotion_cleanUpOverlayAndRunningAnimation() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setTheme(R.style.AppTheme_Dark)
        val host = FrameLayout(activity)
        val view = LayoutInflater.from(activity)
            .inflate(R.layout.smartspace_enhanced, host, false) as BcSmartspaceView
        host.addView(view)
        activity.setContentView(host)

        val widthSpec = MeasureSpec.makeMeasureSpec(400, MeasureSpec.EXACTLY)
        val heightSpec = MeasureSpec.makeMeasureSpec(120, MeasureSpec.EXACTLY)
        host.measure(widthSpec, heightSpec)
        host.layout(0, 0, 400, 120)

        withAnimatorDurationScale(1f) {
            val oldCard1 = BcSmartspaceCard(activity)
            view.animateSmartspaceUpdate(oldCard1)
            assertThat(view.runningAnimation).isNotNull()
            assertThat(view.getAnimatingOldCardForTesting()).isSameInstanceAs(oldCard1)

            // Explicit cancel must clear overlay tracking and runningAnimation.
            view.runningAnimation!!.cancel()
            assertThat(view.runningAnimation).isNull()
            assertThat(view.getAnimatingOldCardForTesting()).isNull()

            // Detaching mid-animation must cancel and clear overlay state cleanly.
            val oldCard2 = BcSmartspaceCard(activity)
            view.animateSmartspaceUpdate(oldCard2)
            assertThat(view.runningAnimation).isNotNull()
            host.removeView(view)
            assertThat(view.runningAnimation).isNull()
            assertThat(view.getAnimatingOldCardForTesting()).isNull()
        }

        // Re-attach and verify Reduced Motion short-circuits without starting an animation.
        host.addView(view)
        withAnimatorDurationScale(0f) {
            val oldCard3 = BcSmartspaceCard(activity)
            view.animateSmartspaceUpdate(oldCard3)
            assertThat(view.runningAnimation).isNull()
            assertThat(view.getAnimatingOldCardForTesting()).isNull()
        }
    }

    @Test
    fun smartspaceWidgetReader_handlesMissingUnitAndShallowParentSafely() {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        assertThat(SmartspaceWidgetReader.parseWeatherData(bitmap, "72")).isNull()
        assertThat(SmartspaceWidgetReader.parseWeatherData(bitmap, "")).isNull()
        assertThat(SmartspaceWidgetReader.parseWeatherData(bitmap, "°F")).isNull()
        assertThat(SmartspaceWidgetReader.parseWeatherData(bitmap, "72°F")?.getTitle()).isEqualTo("72°F")
        assertThat(SmartspaceWidgetReader.parseWeatherData(bitmap, "-5°C")?.getTitle()).isEqualTo("-5°C")

        // Shallow ViewGroup (title.parent.parent == null) must not NPE when reading pendingIntent.
        val reader = SmartspaceWidgetReader(context)
        val shallowContainer = LinearLayout(context).apply {
            addView(ImageView(context).apply { setImageDrawable(BitmapDrawable(context.resources, bitmap)) })
            addView(ImageView(context).apply { setImageDrawable(BitmapDrawable(context.resources, bitmap)) })
            addView(TextView(context).apply { text = "Standup" })
            addView(TextView(context).apply { text = "10:00 AM" })
            addView(TextView(context).apply { text = "72°F" })
        }

        val targets = reader.extractWidgetLayout(shallowContainer)
        assertThat(targets).hasSize(2)
        assertThat(targets.map { it.id }).containsExactly(
            "smartspaceWidgetCard",
            SmartspaceWidgetReader.WEATHER_TARGET_ID,
        )
    }

    @Test
    fun physicsAnimator_reducedMotion_snapsImmediatelyAndRunsEndActions() {
        val view = View(context).apply { scaleX = 1f }
        var endActionCalled = false
        var endListenerValue = -1f

        withAnimatorDurationScale(0f) {
            PhysicsAnimator.getInstance(view)
                .spring(DynamicAnimation.SCALE_X, 0.5f)
                .addEndListener(object : PhysicsAnimator.EndListener<View> {
                    override fun onAnimationEnd(
                        target: View,
                        property: androidx.dynamicanimation.animation.FloatPropertyCompat<in View>,
                        wasFling: Boolean,
                        canceled: Boolean,
                        finalValue: Float,
                        finalVelocity: Float,
                        allRelevantPropertyAnimsEnded: Boolean,
                    ) {
                        endListenerValue = finalValue
                    }
                })
                .withEndActions(Runnable { endActionCalled = true })
                .start()
        }

        assertThat(view.scaleX).isEqualTo(0.5f)
        assertThat(endListenerValue).isEqualTo(0.5f)
        assertThat(endActionCalled).isTrue()
        assertThat(PhysicsAnimator.getInstance(view).isRunning()).isFalse()
    }

    @Test
    fun settingsSearchIndex_coversNewEntriesAndGatesCoreStrippedEntries() {
        val fullTitles = SettingsSearchIndex.entries(SettingsSearchProduct.Expressive).map { it.title }
        assertThat(fullTitles).containsAtLeast(
            R.string.smartspace_fallback_weather,
            R.string.smartspace_bt_battery_title,
            R.string.smartspace_glance_messages,
            R.string.expressive_update_notification_channel,
            R.string.smartspace_now_playing,
            R.string.notification_dots,
        )

        val coreTitles = SettingsSearchIndex.entries(SettingsSearchProduct.ExpressiveCore).map { it.title }
        assertThat(coreTitles).containsAtLeast(
            R.string.smartspace_fallback_weather,
            R.string.smartspace_bt_battery_title,
            R.string.smartspace_glance_messages,
            R.string.expressive_update_notification_channel,
        )
        assertThat(coreTitles).doesNotContain(R.string.smartspace_now_playing)
        assertThat(coreTitles).doesNotContain(R.string.notification_dots)
        assertThat(coreTitles).doesNotContain(R.string.show_notification_count)
    }

    @Test
    fun expressiveMotionDimens_resolveCleanly() {
        val res = context.resources
        assertThat(res.getFloat(R.dimen.swipe_up_rect_scale_stiffness_v2)).isWithin(0.01f).of(500.0f)
        assertThat(res.getFloat(R.dimen.swipe_up_rect_scale_damping_ratio_v2)).isWithin(0.001f).of(0.99f)
        assertThat(res.getFloat(R.dimen.widget_rect_scale_stiffness)).isWithin(0.01f).of(600.0f)
        assertThat(res.getDimension(R.dimen.folder_blur_radius)).isGreaterThan(0f)
    }

    private inline fun withAnimatorDurationScale(scale: Float, block: () -> Unit) {
        val setter = ValueAnimator::class.java.getMethod(
            "setDurationScale",
            Float::class.javaPrimitiveType,
        )
        val previous = ValueAnimator.getDurationScale()
        setter.invoke(null, scale)
        try {
            block()
        } finally {
            setter.invoke(null, previous)
        }
    }
}
