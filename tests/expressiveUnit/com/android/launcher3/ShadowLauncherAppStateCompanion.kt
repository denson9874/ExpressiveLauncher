package com.android.launcher3

import android.content.Context
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.annotation.Resetter
import org.robolectric.util.reflector.Direct
import org.robolectric.util.reflector.ForType
import org.robolectric.util.reflector.Reflector.reflector

/**
 * The one Robolectric shadow of LauncherAppState's companion for all unit tests.
 *
 * Robolectric attaches a shadow to the companion singleton the first time a test calls it, and
 * every test class in a JVM fork shares that singleton. When two test classes each declared their
 * own shadow class for it, whichever ran second failed with a ClassCastException. That depended
 * only on how Gradle split the classes into forks, so it surfaced in Jenkins build 24.
 *
 * A test sets [idp] or [instance] to what it needs. Anything left null runs the real method.
 */
@Implements(LauncherAppState.Companion::class)
class ShadowLauncherAppStateCompanion {

    @RealObject
    private lateinit var realCompanion: LauncherAppState.Companion

    @Implementation
    fun getIDP(context: Context): InvariantDeviceProfile = idp ?: real().getIDP(context)

    @Implementation
    fun getInstance(context: Context): LauncherAppState = instance ?: real().getInstance(context)

    private fun real() = reflector(CompanionReflector::class.java, realCompanion)

    @ForType(LauncherAppState.Companion::class)
    interface CompanionReflector {
        @Direct
        fun getIDP(context: Context): InvariantDeviceProfile

        @Direct
        fun getInstance(context: Context): LauncherAppState
    }

    companion object {
        /** What getIDP returns, or null for the real grid. */
        @JvmStatic
        var idp: InvariantDeviceProfile? = null

        /** What getInstance returns, or null for the real LauncherAppState. */
        @JvmStatic
        var instance: LauncherAppState? = null

        @Resetter
        @JvmStatic
        fun reset() {
            idp = null
            instance = null
        }
    }
}
