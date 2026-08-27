package com.android.launcher3.pm

import android.app.Application
import android.content.Intent
import android.os.UserHandle
import com.android.launcher3.util.UserIconInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class UserCachePrivateProfileLifecycleTest {

    private val privateUser = UserHandle(11)
    private val privateInfo = UserIconInfo(privateUser, UserIconInfo.TYPE_PRIVATE, 11L)

    @Test
    fun inaccessibleProfile_missingFromQuery_keepsLastKnownPrivateHandle() {
        val merged =
            UserCache.mergeUserProfilesForLifecycle(
                mapOf(privateUser to privateInfo),
                emptyMap(),
                privateUser,
                Intent.ACTION_PROFILE_INACCESSIBLE,
            )

        assertThat(merged[privateUser]).isSameInstanceAs(privateInfo)
    }

    @Test
    fun inaccessibleProfile_withAmbiguousMetadata_keepsPrivateClassification() {
        val ambiguousInfo = UserIconInfo(privateUser, UserIconInfo.TYPE_MAIN, 11L)

        val merged =
            UserCache.mergeUserProfilesForLifecycle(
                mapOf(privateUser to privateInfo),
                mapOf(privateUser to ambiguousInfo),
                privateUser,
                UserCache.ACTION_PROFILE_UNAVAILABLE,
            )

        assertThat(merged[privateUser]).isSameInstanceAs(privateInfo)
    }

    @Test
    fun removedProfile_isEvictedAndCannotBecomeGhostPrivateSpace() {
        val merged =
            UserCache.mergeUserProfilesForLifecycle(
                mapOf(privateUser to privateInfo),
                emptyMap(),
                privateUser,
                UserCache.ACTION_PROFILE_REMOVED,
            )

        assertThat(merged).doesNotContainKey(privateUser)
    }
}
