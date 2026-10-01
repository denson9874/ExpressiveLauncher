package com.android.launcher3.icons

import android.app.Application
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * After a system language change the icon database rows are stale, so drawer apps load through the
 * bulk fallback with their new title but no description. TalkBack needs a description.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class IconCacheFallbackDescriptionTest {

    private val badge = java.util.function.Function<CharSequence, CharSequence> { "Work $it" }

    @Test
    fun titleWithoutDescriptionGetsBadgedTitle() {
        assertThat(IconCache.fallbackContentDescription(null, "Kalender", badge).toString())
            .isEqualTo("Work Kalender")
        assertThat(IconCache.fallbackContentDescription("", "Uhr", badge).toString())
            .isEqualTo("Work Uhr")
    }

    @Test
    fun existingDescriptionIsKept() {
        assertThat(IconCache.fallbackContentDescription("Calendar", "Kalender", badge).toString())
            .isEqualTo("Calendar")
    }

    @Test
    fun noTitleLeavesAnEmptyDescription() {
        assertThat(IconCache.fallbackContentDescription(null, null, badge).toString()).isEmpty()
        assertThat(IconCache.fallbackContentDescription(null, "", badge).toString()).isEmpty()
    }
}
