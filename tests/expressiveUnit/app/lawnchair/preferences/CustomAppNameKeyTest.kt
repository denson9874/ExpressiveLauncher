package app.lawnchair.preferences

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.ShortcutInfo
import android.os.UserHandle
import androidx.test.core.app.ApplicationProvider
import app.lawnchair.gestures.config.GestureHandlerConfig
import app.lawnchair.gestures.handlers.OpenAppTarget
import app.lawnchair.gestures.ui.LawnchairShortcutActivity
import app.lawnchair.util.parseComponentKeyLenient
import com.android.launcher3.shortcuts.ShortcutKey
import com.android.launcher3.util.ComponentKey
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CustomAppNameKeyTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val chrome = ComponentKey(
        ComponentName("com.android.chrome", "com.google.android.apps.chrome.Main"),
        UserHandle.of(0),
    )
    private val openChrome = GestureHandlerConfig.OpenApp("Chrome", OpenAppTarget.App(chrome))

    /** The ID 5.0.0 gave a pinned "Open app" action: the handler's data-class toString. */
    private val legacyGestureId = "${LawnchairShortcutActivity.GESTURE_SHORTCUT_ID_PREFIX}:$openChrome"

    @Test
    fun renamedLegacyGestureShortcut_isUnreadableUpstreamButParsesLeniently() {
        val key = ShortcutKey(context.packageName, UserHandle.of(0), legacyGestureId)

        assertThat(legacyGestureId).contains("#")
        assertThat(ComponentKey.fromString(key.toString())).isNull()
        assertThat(parseComponentKeyLenient(key.toString())).isEqualTo(key)
    }

    @Test
    fun shortcutKeys_roundTripThroughTheStoredForm() {
        val ids = listOf(
            legacyGestureId,
            "id#with#hashes",
            "#",
            "id/with/slashes",
            ".leadingDot",
            ".leading/dot#and/both#",
        )
        for (userId in listOf(0, 10)) {
            for (id in ids) {
                val key = ShortcutKey(context.packageName, UserHandle.of(userId), id)

                val parsed = parseComponentKeyLenient(key.toString())

                assertThat(parsed).isEqualTo(key)
                assertThat(parsed!!.componentName.className).isEqualTo(id)
                assertThat(parsed.componentName.packageName).isEqualTo(context.packageName)
                assertThat(parsed.user).isEqualTo(UserHandle.of(userId))
                // customAppName is looked up with the ShortcutKey after the restart.
                assertThat(mapOf(parsed to "Renamed")[key]).isEqualTo("Renamed")
            }
        }
    }

    @Test
    fun appKeys_parseExactlyLikeComponentKeyFromString() {
        val work = ComponentKey(ComponentName("com.example.mail", "com.example.mail.Inbox"), UserHandle.of(10))
        for (stored in listOf(chrome.toString(), work.toString())) {
            assertThat(parseComponentKeyLenient(stored)).isEqualTo(ComponentKey.fromString(stored))
            assertThat(parseComponentKeyLenient(stored).toString()).isEqualTo(stored)
        }
    }

    @Test
    fun malformedKeys_areRejectedWithoutThrowing() {
        val malformed = listOf(
            "",
            "no-separators",
            "pkg/cls",
            "pkg/cls#",
            "pkg/cls#notAUser",
            "pkg/#0",
            "#0",
            "pkg#0/cls",
        )
        for (stored in malformed) {
            assertThat(parseComponentKeyLenient(stored)).isNull()
        }
    }

    @Test
    fun storedMap_skipsAMalformedKeyAndKeepsTheRest() {
        val renamed = ShortcutKey(context.packageName, UserHandle.of(0), legacyGestureId)
        val json = JSONObject()
            .put(renamed.toString(), "Browser")
            .put("not a component key", "Lost")
            .put(chrome.toString(), "Web")
            .toString()

        // The same parsers customAppName hands to MutableMapPref's init.
        val map = readStoredMap("pref_appNameMap", json, ::parseComponentKeyLenient) { it }

        assertThat(map).containsExactly(renamed, "Browser", chrome, "Web")
        // The one-time repair stores the readable keys again in exactly their old form.
        assertThat(map.keys.map { it.toString() })
            .containsExactly(renamed.toString(), chrome.toString())
    }

    @Test
    fun storedMap_skipsEntriesItsParsersRejectOrThrowOn() {
        val json = JSONObject().put("a", "1").put("b", "not a number").put("c", "3").toString()

        val map = readStoredMap("pref_test", json, { key -> key.takeIf { it != "c" } }) { it.toInt() }

        assertThat(map).containsExactly("a", 1)
    }

    @Test
    fun gestureShortcutId_isStableAndHasNoKeySeparators() {
        val id = LawnchairShortcutActivity.gestureShortcutId(openChrome)

        assertThat(id).startsWith(LawnchairShortcutActivity.GESTURE_SHORTCUT_ID_PREFIX)
        assertThat(id).doesNotContain("#")
        assertThat(id).doesNotContain("/")
        assertThat(id).doesNotContain("Chrome")
        assertThat(LawnchairShortcutActivity.gestureShortcutId(openChrome.copy())).isEqualTo(id)
        assertThat(LawnchairShortcutActivity.gestureShortcutId(GestureHandlerConfig.OpenAppDrawer))
            .isNotEqualTo(id)

        val key = ShortcutKey(context.packageName, UserHandle.of(0), id)
        assertThat(ComponentKey.fromString(key.toString())).isEqualTo(key)
        assertThat(parseComponentKeyLenient(key.toString())).isEqualTo(key)
    }

    @Test
    fun gestureShortcutId_stillSkipsTheShortcutBadge() {
        val shortcutInfo = ShortcutInfo.Builder(context, LawnchairShortcutActivity.gestureShortcutId(openChrome))
            .setShortLabel("Chrome")
            .build()

        assertThat(LawnchairShortcutActivity.shouldSkipShortcutBadge(context, shortcutInfo)).isTrue()
    }
}
