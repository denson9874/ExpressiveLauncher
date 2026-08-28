package app.lawnchair.ui.preferences.about

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.StringRes
import app.lawnchair.ui.preferences.PreferenceActivity
import app.lawnchair.ui.preferences.navigation.About
import com.android.launcher3.BuildConfig
import com.android.launcher3.R
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import retrofit2.create

internal const val EXTRA_OPEN_EXPRESSIVE_UPDATE_PROMPT =
    "app.lawnchair.extra.OPEN_EXPRESSIVE_UPDATE_PROMPT"

enum class ExpressiveUpdateSnoozeOption(
    @StringRes val labelResId: Int,
    val durationMillis: Long,
) {
    ONE_HOUR(R.string.expressive_update_snooze_one_hour, TimeUnit.HOURS.toMillis(1)),
    ONE_DAY(R.string.expressive_update_snooze_one_day, TimeUnit.DAYS.toMillis(1)),
    THREE_DAYS(R.string.expressive_update_snooze_three_days, TimeUnit.DAYS.toMillis(3)),
    ONE_WEEK(R.string.expressive_update_snooze_one_week, TimeUnit.DAYS.toMillis(7)),
}

internal data class ExpressiveUpdateNotificationSnapshot(
    val lastNotifiedVersionCode: Long,
    val snoozedVersionCode: Long,
    val snoozeUntilMillis: Long,
)

internal fun shouldPostExpressiveUpdateNotification(
    currentVersionCode: Long,
    availableVersionCode: Long,
    snapshot: ExpressiveUpdateNotificationSnapshot,
    nowMillis: Long,
): Boolean {
    if (availableVersionCode <= currentVersionCode) return false
    if (
        snapshot.snoozedVersionCode == availableVersionCode &&
        nowMillis < snapshot.snoozeUntilMillis
    ) {
        return false
    }
    if (
        snapshot.snoozedVersionCode == availableVersionCode &&
        nowMillis >= snapshot.snoozeUntilMillis
    ) {
        return true
    }
    return snapshot.lastNotifiedVersionCode != availableVersionCode
}

internal class ExpressiveUpdateNotificationStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun snapshot(channel: ExpressiveUpdateChannel): ExpressiveUpdateNotificationSnapshot {
        val prefix = channel.wireName
        return ExpressiveUpdateNotificationSnapshot(
            lastNotifiedVersionCode = preferences.getLong("${prefix}_last_notified_version", 0L),
            snoozedVersionCode = preferences.getLong("${prefix}_snoozed_version", 0L),
            snoozeUntilMillis = preferences.getLong("${prefix}_snooze_until", 0L),
        )
    }

    fun recordNotified(channel: ExpressiveUpdateChannel, versionCode: Long) {
        val prefix = channel.wireName
        preferences.edit()
            .putLong("${prefix}_last_notified_version", versionCode)
            .remove("${prefix}_snoozed_version")
            .remove("${prefix}_snooze_until")
            .apply()
    }

    fun snooze(
        channel: ExpressiveUpdateChannel,
        versionCode: Long,
        option: ExpressiveUpdateSnoozeOption,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val prefix = channel.wireName
        preferences.edit()
            .remove("${prefix}_last_notified_version")
            .putLong("${prefix}_snoozed_version", versionCode)
            .putLong("${prefix}_snooze_until", nowMillis + option.durationMillis)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "expressive_update_notifications"
    }
}

internal object ExpressiveUpdateNotifications {
    private const val CHANNEL_ID = "expressive_launcher_updates"
    private const val NOTIFICATION_ID = 0x455850

    fun canNotify(context: Context): Boolean = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED &&
        context.getSystemService(android.app.NotificationManager::class.java)
            .areNotificationsEnabled()

    fun post(
        context: Context,
        config: ExpressiveUpdateConfig,
        manifest: ExpressiveUpdateManifest,
    ) {
        if (!canNotify(context)) return
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.expressive_update_notification_channel),
                android.app.NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.expressive_update_notification_channel_description)
            },
        )
        val promptIntent = PreferenceActivity.createIntent(context, About).apply {
            putExtra(EXTRA_OPEN_EXPRESSIVE_UPDATE_PROMPT, true)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val promptPendingIntent = PendingIntent.getActivity(
            context,
            config.channel.ordinal,
            promptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val channelName = context.getString(config.channel.displayNameResId)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_new_releases)
            .setContentTitle(
                context.getString(
                    R.string.expressive_update_notification_title,
                    manifest.versionName,
                ),
            )
            .setContentText(
                context.getString(
                    R.string.expressive_update_notification_text,
                    channelName,
                ),
            )
            .setContentIntent(promptPendingIntent)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context) {
        context.getSystemService(android.app.NotificationManager::class.java)
            .cancel(NOTIFICATION_ID)
    }
}

private val ExpressiveUpdateChannel.displayNameResId: Int
    get() = when (this) {
        ExpressiveUpdateChannel.QA -> R.string.expressive_update_channel_qa
        ExpressiveUpdateChannel.RELEASE -> R.string.expressive_update_channel_release
    }

internal object ExpressiveUpdateScheduler {
    private const val PERIODIC_JOB_ID = 0x455851
    private const val IMMEDIATE_JOB_ID = 0x455852
    private const val SNOOZE_JOB_ID = 0x455853
    private val PERIOD_MILLIS = TimeUnit.HOURS.toMillis(6)
    private val FLEX_MILLIS = TimeUnit.HOURS.toMillis(1)

    fun ensureScheduled(context: Context) {
        installedExpressiveUpdateConfig() ?: return
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (!ExpressiveUpdateNotifications.canNotify(context)) {
            scheduler.cancel(PERIODIC_JOB_ID)
            scheduler.cancel(IMMEDIATE_JOB_ID)
            scheduler.cancel(SNOOZE_JOB_ID)
            return
        }
        val component = ComponentName(context, ExpressiveUpdateJobService::class.java)
        scheduler.schedule(
            JobInfo.Builder(PERIODIC_JOB_ID, component)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setPeriodic(PERIOD_MILLIS, FLEX_MILLIS)
                .build(),
        )
        if (scheduler.getPendingJob(IMMEDIATE_JOB_ID) == null) {
            scheduler.schedule(
                JobInfo.Builder(IMMEDIATE_JOB_ID, component)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setMinimumLatency(0L)
                    .build(),
            )
        }
    }

    fun scheduleAfterSnooze(context: Context, delayMillis: Long) {
        if (installedExpressiveUpdateConfig() == null) return
        val scheduler = context.getSystemService(JobScheduler::class.java)
        scheduler.schedule(
            JobInfo.Builder(
                SNOOZE_JOB_ID,
                ComponentName(context, ExpressiveUpdateJobService::class.java),
            )
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(delayMillis)
                .setOverrideDeadline(delayMillis + FLEX_MILLIS)
                .build(),
        )
    }
}

class ExpressiveUpdateJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var runningJob: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val config = installedExpressiveUpdateConfig() ?: return false
        if (!ExpressiveUpdateNotifications.canNotify(this)) return false
        runningJob = scope.launch {
            var shouldRetry = false
            try {
                when (
                    val decision = fetchExpressiveUpdateDecision(
                        api = gitHubApiRetrofit.create(),
                        config = config,
                        currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
                        currentPackageName = BuildConfig.APPLICATION_ID,
                    )
                ) {
                    is ExpressiveUpdateDecision.Available -> {
                        val store = ExpressiveUpdateNotificationStore(this@ExpressiveUpdateJobService)
                        val manifest = decision.manifest
                        if (
                            shouldPostExpressiveUpdateNotification(
                                currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
                                availableVersionCode = manifest.versionCode,
                                snapshot = store.snapshot(config.channel),
                                nowMillis = System.currentTimeMillis(),
                            )
                        ) {
                            ExpressiveUpdateNotifications.post(
                                context = this@ExpressiveUpdateJobService,
                                config = config,
                                manifest = manifest,
                            )
                            store.recordNotified(config.channel, manifest.versionCode)
                        }
                    }

                    ExpressiveUpdateDecision.UpToDate -> Unit

                    is ExpressiveUpdateDecision.Rejected -> Unit
                }
            } catch (_: Exception) {
                shouldRetry = true
            } finally {
                jobFinished(params, shouldRetry)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        runningJob?.cancel()
        return true
    }

    override fun onNetworkChanged(params: JobParameters) = Unit

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
