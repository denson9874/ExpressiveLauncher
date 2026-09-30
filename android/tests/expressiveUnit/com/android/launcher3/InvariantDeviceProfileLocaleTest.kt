package com.android.launcher3

import android.app.Application
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class InvariantDeviceProfileLocaleTest {

    @Test
    fun languageChange_isAModelPropertyChange() {
        // Launcher is recreated on a language change and binds the in-memory model directly, so
        // the IDP locale broadcast is the only path that reloads app labels and drawer sections.
        val idp = idpWithoutGridInit()
        ReflectionHelpers.setField(idp, "mLocale", "en-US")
        val before = modelState(idp)

        ReflectionHelpers.setField(idp, "mLocale", "fr-FR")

        assertThat(modelState(idp).asList()).isNotEqualTo(before.asList())
    }

    @Test
    fun secondaryLanguageChange_isAModelPropertyChange() {
        // Labels without a translation for the first language fall back through the list.
        val idp = idpWithoutGridInit()
        ReflectionHelpers.setField(idp, "mLocale", "en-US,fr-FR")
        val before = modelState(idp)

        ReflectionHelpers.setField(idp, "mLocale", "en-US,de-DE")

        assertThat(modelState(idp).asList()).isNotEqualTo(before.asList())
    }

    @Test
    fun unchangedLanguage_isNotAModelPropertyChange() {
        val idp = idpWithoutGridInit()
        ReflectionHelpers.setField(idp, "mLocale", "en-US")
        val before = modelState(idp)

        ReflectionHelpers.setField(idp, "mLocale", "en-US")

        assertThat(modelState(idp).asList()).isEqualTo(before.asList())
    }

    private fun modelState(idp: InvariantDeviceProfile): Array<Any?> =
        ReflectionHelpers.callInstanceMethod(idp, "toModelState")

    /** The grid init needs the full launcher graph; the model state only reads plain fields. */
    private fun idpWithoutGridInit(): InvariantDeviceProfile {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
            .get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, InvariantDeviceProfile::class.java) as InvariantDeviceProfile
    }
}
