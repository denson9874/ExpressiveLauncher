package com.android.launcher3.widget

import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.UserHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lawnchair.smartspace.SmartspaceAppWidgetProvider
import com.android.launcher3.pm.UserCache
import com.android.launcher3.proxy.ProxyActivityStarter
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetFlowRegistrationTest {

    @Test
    fun widgetResultProxy_isInstalledAndPrivate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val component = ComponentName(context, ProxyActivityStarter::class.java)
        val info = context.packageManager.getActivityInfo(
            component,
            PackageManager.ComponentInfoFlags.of(0),
        )

        // QuickstepLauncher sends both ACTION_APPWIDGET_BIND and provider configuration results
        // through this activity. It must exist, but no external package should be able to invoke it.
        assertThat(info.enabled).isTrue()
        assertThat(info.exported).isFalse()
    }

    @Test
    fun coldStartWidgetCatalog_doesNotDependOnAsyncUserCache() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val userCache = UserCache.INSTANCE.get(context)
        val profilesField =
            UserCache::class.java.getDeclaredField("mUserToSerialMap").apply {
                isAccessible = true
            }
        val originalProfiles = profilesField.get(userCache)

        try {
            // Reproduce the cold-start window where UserCache has been constructed but its model
            // executor initialization has not populated a profile yet.
            profilesField.set(userCache, emptyMap<UserHandle, Any>())

            val providers = WidgetManagerHelper(context).getAllProviders(null)
            val ownProvider = ComponentName(context, SmartspaceAppWidgetProvider::class.java)

            assertThat(providers.map { it.provider }).contains(ownProvider)
        } finally {
            profilesField.set(userCache, originalProfiles)
        }
    }
}
