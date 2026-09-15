package com.android.launcher3.uioverrides

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentSender
import android.os.UserHandle
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class PrivateProfileMarketIntentTest {

    private val privateUser = UserHandle(11)

    @Test
    fun availableInstaller_usesCrossProfileIntentSender() {
        val expected = newIntentSender(1)
        val requests = mutableListOf<Pair<String?, UserHandle>>()

        val actual =
            resolvePrivateProfileMarketIntentSender(
                "dev.launcher.expressive.l3",
                privateUser,
            ) { packageName, user ->
                requests += packageName to user
                expected
            }

        assertThat(actual).isSameInstanceAs(expected)
        assertThat(requests)
            .containsExactly("dev.launcher.expressive.l3" to privateUser)
            .inOrder()
    }

    @Test
    fun vendorReturnsNullForPackage_retriesDefaultStoreInSameProfile() {
        val expected = newIntentSender(2)
        val requests = mutableListOf<Pair<String?, UserHandle>>()

        val actual =
            resolvePrivateProfileMarketIntentSender(
                "dev.launcher.expressive.l3",
                privateUser,
            ) { packageName, user ->
                requests += packageName to user
                if (packageName == null) expected else null
            }

        assertThat(actual).isSameInstanceAs(expected)
        assertThat(requests)
            .containsExactly(
                "dev.launcher.expressive.l3" to privateUser,
                null to privateUser,
            )
            .inOrder()
    }

    @Test
    fun providerThrowsForPackage_retriesDefaultStoreInSameProfile() {
        val expected = newIntentSender(3)
        val requests = mutableListOf<Pair<String?, UserHandle>>()

        val actual =
            resolvePrivateProfileMarketIntentSender(
                "dev.launcher.expressive.l3",
                privateUser,
            ) { packageName, user ->
                requests += packageName to user
                if (packageName == null) expected else error("vendor package lookup failed")
            }

        assertThat(actual).isSameInstanceAs(expected)
        assertThat(requests)
            .containsExactly(
                "dev.launcher.expressive.l3" to privateUser,
                null to privateUser,
            )
            .inOrder()
    }

    @Test
    fun noInstallerInPrivateProfile_returnsNullWithoutOwnerFallback() {
        val requests = mutableListOf<Pair<String?, UserHandle>>()

        val actual =
            resolvePrivateProfileMarketIntentSender(
                "dev.launcher.expressive.l3",
                privateUser,
            ) { packageName, user ->
                requests += packageName to user
                null
            }

        assertThat(actual).isNull()
        assertThat(requests)
            .containsExactly(
                "dev.launcher.expressive.l3" to privateUser,
                null to privateUser,
            )
            .inOrder()
    }

    private fun newIntentSender(requestCode: Int): IntentSender =
        PendingIntent.getActivity(
            ApplicationProvider.getApplicationContext(),
            requestCode,
            Intent("test.private.profile.market.$requestCode"),
            PendingIntent.FLAG_IMMUTABLE,
        ).intentSender
}
