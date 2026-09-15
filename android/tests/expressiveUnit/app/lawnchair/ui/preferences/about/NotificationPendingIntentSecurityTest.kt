/*
 * Copyright 2026 Daryl Denson and Expressive Launcher contributors.
 * SPDX-License-Identifier: Apache-2.0
 * https://github.com/denson9874/ExpressiveLauncher
 */

package app.lawnchair.ui.preferences.about

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.lawnchair.bugreport.BugReport
import app.lawnchair.bugreport.BugReportReceiver
import app.lawnchair.ui.preferences.PreferenceActivity
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
class NotificationPendingIntentSecurityTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val manager = application.getSystemService(NotificationManager::class.java)

    @Test
    fun updatePromptCannotBeRedirectedByTheTokenRecipient() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val config = ExpressiveUpdateConfig(ExpressiveUpdateChannel.QA, "https://example.com/qa.json")
        val manifest = ExpressiveUpdateManifest(
            schemaVersion = 1, channel = "qa", versionCode = 2, versionName = "test",
            packageName = application.packageName, apkUrl = "https://example.com/test.apk",
            sha256 = "0".repeat(64), sizeBytes = 1, releaseNotes = "test",
        )
        ExpressiveUpdateNotifications.post(application, config, manifest)
        val notification = manager.activeNotifications.single().notification
        val token = notification.contentIntent

        assertThat(token.isImmutable).isTrue()
        token.send(application, 0, hostileFillIn())
        val delivered = shadowOf(application).nextStartedActivity
        assertThat(delivered.component).isEqualTo(ComponentName(application, PreferenceActivity::class.java))
        assertThat(delivered.getBooleanExtra(EXTRA_OPEN_EXPRESSIVE_UPDATE_PROMPT, false)).isTrue()
        assertThat(delivered.data).isNull()
        assertThat(delivered.hasExtra("attacker-extra")).isFalse()
    }

    @Test
    fun bugReportTokensAreImmutableAndPrivateActionsStayPackageScoped() {
        manager.createNotificationChannel(
            NotificationChannel(BugReportReceiver.NOTIFICATION_CHANNEL_ID, "Reports", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val report = BugReport(123L, 7, "Test", "Description", "Contents", null, file = null)
        BugReportReceiver.notify(application, report)
        val notification = manager.activeNotifications.single { it.id == report.id }.notification

        assertThat(notification.actions.size).isEqualTo(3)
        notification.actions.forEach { action -> assertThat(action.actionIntent.isImmutable).isTrue() }
        notification.actions.drop(1).forEach { action ->
            assertThat(shadowOf(action.actionIntent).savedIntent.`package`).isEqualTo(application.packageName)
        }
    }

    @Test
    fun bugReportLinkRetainsItsOriginalDestinationWhenSentWithFillIn() {
        manager.createNotificationChannel(
            NotificationChannel(BugReportReceiver.NOTIFICATION_CHANNEL_ID, "Reports", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val report = BugReport(123L, 8, "Test", "Description", "Contents", "https://example.com/report", file = null)
        BugReportReceiver.notify(application, report)
        val token = manager.activeNotifications.single { it.id == report.id }.notification.contentIntent

        assertThat(token.isImmutable).isTrue()
        token.send(application, 0, hostileFillIn())
        val delivered = shadowOf(application).nextStartedActivity
        assertThat(delivered.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(delivered.data).isEqualTo(Uri.parse(report.link))
        assertThat(delivered.component).isNull()
        assertThat(delivered.hasExtra("attacker-extra")).isFalse()
    }

    private fun hostileFillIn() = Intent("attacker.action").apply {
        component = ComponentName("attacker.app", "attacker.app.Target")
        data = Uri.parse("https://example.invalid/attacker")
        putExtra("attacker-extra", true)
        putExtra(EXTRA_OPEN_EXPRESSIVE_UPDATE_PROMPT, false)
    }
}
