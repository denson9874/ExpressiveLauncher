package com.android.launcher3.util

import android.app.Application
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class SettingsCacheSecurityTest {

    @Test
    fun protectedObserverRead_keepsLastValueInsteadOfCrashing() {
        val resolved =
            SettingsCache.readValueSafely(
                { throw SecurityException("hidden secure setting") },
                true,
            )

        assertThat(resolved).isTrue()
    }

    @Test
    fun readableObserverValue_replacesFallback() {
        assertThat(SettingsCache.readValueSafely({ false }, true)).isFalse()
    }
}
