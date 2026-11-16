package com.android.launcher3.model

import app.lawnchair.icons.themedIconsSupported
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * Uninstalling any app used to switch Themed icons off whenever no icon pack was installed, so
 * stock themed icons (Android 13+, no pack needed) reverted to unthemed on Home and in the drawer.
 */
class PackageRemovalThemedIconsTest {

    @Test
    fun uninstallingAnAppWithNoIconPack_keepsStockThemedIconsOnAndroid13Plus() {
        assertThat(themedIconsSupported(sdkInt = 37, monochromePackInstalled = false)).isTrue()
    }

    @Test
    fun removeBranch_onlyDisablesThemedIconsWhenTheyAreNoLongerSupported() {
        val source = packageUpdatedTaskSource().readText()
        val removeBranch = source.substringAfter("case OP_REMOVE: {").substringBefore("case OP_UNAVAILABLE:")
        val disable = removeBranch.indexOf("getThemedIcons().set(false)")

        // Guards the guard: a moved or renamed branch would make the check below vacuous.
        assertThat(disable).isAtLeast(0)
        assertWithMessage("OP_REMOVE must gate getThemedIcons().set(false) on themedIconsSupported(...)")
            .that(removeBranch.substring(0, disable))
            .contains("!ThemedIconLayerKt.themedIconsSupported(")
    }

    private fun packageUpdatedTaskSource(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "src/com/android/launcher3/model/PackageUpdatedTask.java")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("Could not find PackageUpdatedTask.java from ${File("").absolutePath}")
    }
}
