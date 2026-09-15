package com.android.launcher3.touch

import android.app.Application
import android.content.Intent
import android.os.UserHandle
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class PrivateProfileInstallClickPolicyTest {

    @Test
    fun missingProfileHandle_doesNotCallMarketProvider() {
        var calls = 0

        val intent =
            ItemClickHandler.resolvePrivateProfileMarketIntent(null) {
                calls++
                Intent("test.private.market")
            }

        assertThat(intent).isNull()
        assertThat(calls).isEqualTo(0)
    }

    @Test
    fun availableProfileHandle_isForwardedToMarketProvider() {
        val privateUser = UserHandle(11)
        var requestedUser: UserHandle? = null

        val intent =
            ItemClickHandler.resolvePrivateProfileMarketIntent(privateUser) { user ->
                requestedUser = user
                Intent("test.private.market")
            }

        assertThat(intent?.action).isEqualTo("test.private.market")
        assertThat(requestedUser).isEqualTo(privateUser)
    }
}
