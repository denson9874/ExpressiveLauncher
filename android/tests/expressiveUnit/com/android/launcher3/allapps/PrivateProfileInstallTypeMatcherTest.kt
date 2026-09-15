package com.android.launcher3.allapps

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.os.UserHandle
import com.android.launcher3.model.data.AppInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class PrivateProfileInstallTypeMatcherTest {

    private val privateUser = UserHandle(11)

    @Test
    fun profileMissingFromManagerSnapshot_usesConcreteAppUserWithoutNullLookup() {
        val requestedUsers = mutableListOf<UserHandle>()
        val matcher =
            PrivateProfileManager.createPrivateAppInstallTypeMatcher { user ->
                requestedUsers += user
                listOf("com.android.system.private")
            }
        val userInstalledApp =
            AppInfo(
                ComponentName("com.example.private", "com.example.private.Main"),
                "Private app",
                privateUser,
                Intent(),
            )

        assertThat(matcher.test(userInstalledApp)).isTrue()
        assertThat(requestedUsers).containsExactly(privateUser)
    }

    @Test
    fun multipleAppsForSamePrivateUser_readPreinstallCacheOnce() {
        var lookupCount = 0
        val matcher =
            PrivateProfileManager.createPrivateAppInstallTypeMatcher {
                lookupCount++
                listOf("com.android.system.private")
            }
        val systemApp =
            AppInfo(
                ComponentName("com.android.system.private", "com.android.system.private.Main"),
                "System app",
                privateUser,
                Intent(),
            )
        val userApp =
            AppInfo(
                ComponentName("com.example.private", "com.example.private.Main"),
                "User app",
                privateUser,
                Intent(),
            )

        assertThat(matcher.test(systemApp)).isFalse()
        assertThat(matcher.test(userApp)).isTrue()
        assertThat(lookupCount).isEqualTo(1)
    }
}
