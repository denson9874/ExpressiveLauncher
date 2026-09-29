package app.lawnchair.ui.preferences.components.layout

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.launcher3.R
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ClickableIcon draws every icon-only button in Settings (back, search, more options, clear, ...).
 * It had no way to carry a label, so TalkBack announced each of them as just "Button".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class ClickableIconLabelsTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun everyIconButtonNamesItsAction() {
        val sourceRoot = launcherSourceRoot()
        val unlabeled = mutableListOf<String>()

        sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "ClickableIcon.kt" }
            .forEach { file ->
                val source = file.readText()
                Regex("""\bClickableIcon\s*\(""").findAll(source).forEach { match ->
                    val body = callBody(source, match.range.last + 1)
                    if ("contentDescription" !in body) {
                        val line = source.substring(0, match.range.first).count { it == '\n' } + 1
                        unlabeled += "${file.relativeTo(sourceRoot)}:$line"
                    }
                }
            }

        assertWithMessage("ClickableIcon calls without a contentDescription:\n" + unlabeled.joinToString("\n"))
            .that(unlabeled)
            .isEmpty()
    }

    @Test
    fun theScanFindsTheCallSites() {
        // Guards the guard: an unfindable source root or a rotted pattern would pass the test above.
        val count = launcherSourceRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "ClickableIcon.kt" }
            .sumOf { Regex("""\bClickableIcon\s*\(""").findAll(it.readText()).count() }

        assertThat(count).isAtLeast(10)
    }

    @Test
    fun theLabelsTheTopBarUsesAreReadable() {
        // Back, More options and Clear come from AppCompat, so they arrive already translated.
        assertThat(context.getString(androidx.appcompat.R.string.abc_action_bar_up_description))
            .isEqualTo("Navigate up")
        assertThat(context.getString(androidx.appcompat.R.string.abc_action_menu_overflow_description))
            .isEqualTo("More options")
        assertThat(context.getString(androidx.appcompat.R.string.abc_searchview_description_clear))
            .isEqualTo("Clear query")
        assertThat(context.getString(R.string.settings_search_placeholder)).isEqualTo("Search settings")
        assertThat(context.getString(R.string.settings_get_app)).isEqualTo("Get app")
    }

    private fun callBody(source: String, openParenEnd: Int): String {
        var depth = 1
        var index = openParenEnd
        while (index < source.length && depth > 0) {
            when (source[index]) {
                '(' -> depth++
                ')' -> depth--
            }
            index++
        }
        return source.substring(openParenEnd, index)
    }

    private fun launcherSourceRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "lawnchair/src/app/lawnchair")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        error("Could not find lawnchair/src/app/lawnchair from ${File("").absolutePath}")
    }
}
