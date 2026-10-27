// ITEM_TYPE_SHORTCUT is deprecated, and is what this test covers.
@file:Suppress("DEPRECATION")

package com.android.launcher3.taskbar

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import android.util.SparseArray
import android.view.KeyEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.android.launcher3.LauncherSettings.Favorites.CONTAINER_HOTSEAT
import com.android.launcher3.LauncherSettings.Favorites.ITEM_TYPE_APPLICATION
import com.android.launcher3.LauncherSettings.Favorites.ITEM_TYPE_DEEP_SHORTCUT
import com.android.launcher3.LauncherSettings.Favorites.ITEM_TYPE_SEARCH_ACTION
import com.android.launcher3.LauncherSettings.Favorites.ITEM_TYPE_SHORTCUT
import com.android.launcher3.R
import com.android.launcher3.accessibility.BaseAccessibilityDelegate
import com.android.launcher3.accessibility.LauncherAccessibilityDelegate.DEEP_SHORTCUTS
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.model.data.WorkspaceItemInfo
import com.android.launcher3.shortcuts.ShortcutKey
import com.android.launcher3.taskbar.TaskbarShortcutMenuAccessibilityDelegate.CREATE_APPLICATION_BUBBLE
import com.android.launcher3.taskbar.TaskbarShortcutMenuAccessibilityDelegate.MOVE_TO_BOTTOM_OR_RIGHT
import com.android.launcher3.taskbar.TaskbarShortcutMenuAccessibilityDelegate.MOVE_TO_TOP_OR_LEFT
import com.android.launcher3.util.ComponentKey
import com.android.launcher3.util.SplitConfigurationOptions.STAGE_POSITION_TOP_OR_LEFT
import com.android.launcher3.util.SplitConfigurationOptions.STAGE_TYPE_MAIN
import com.android.launcher3.util.SplitConfigurationOptions.SplitPositionOption
import com.android.quickstep.util.SplitSelectDataHolder
import com.android.quickstep.util.SplitSelectStateController
import com.android.systemui.shared.recents.model.Task
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

private typealias Action = BaseAccessibilityDelegate<TaskbarActivityContext>.LauncherAction

/**
 * R18b: the taskbar starts a split by itself (from its long-press menu in an app, its "Move to
 * top/left" and "bottom/right" accessibility actions, and a drag into split) through the item's
 * deep shortcut id or LauncherApps#getMainActivityLaunchIntent of the component in its intent.
 * A legacy shortcut (ITEM_TYPE_SHORTCUT) has neither: its intent names no component, so
 * getMainActivityLaunchIntent(null) crashed Home, or one that need not be a launcher activity,
 * which LauncherApps refuses. The taskbar no longer offers or starts a split for one.
 *
 * Split selection (the taskbar menu in Overview, and Home's own menu on large screens) launches
 * the item's own intent and keeps taking one. It first looks for a running task of the item's
 * component, and a shortcut without a component crashed Home there too.
 *
 * The taskbar only runs while Home is the recents provider, which these emulators can't set up,
 * so this is covered here and not on a device.
 */
// SDK 34: Android 17's framework jar bundles an older com.android.launcher3.Flags that hides ours.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class TaskbarLegacyShortcutSplitTest {

    @Test
    fun onlyAppsAndDeepShortcuts_canStartASplitDirectly() {
        assertThat(TaskbarPopupController.canStartSplitDirectly(app())).isTrue()
        assertThat(TaskbarPopupController.canStartSplitDirectly(deepShortcut())).isTrue()

        assertThat(TaskbarPopupController.canStartSplitDirectly(legacyShortcut())).isFalse()
        // Even one that names a component: it need not be a launcher activity.
        assertThat(TaskbarPopupController.canStartSplitDirectly(legacyShortcut(SETTINGS)))
            .isFalse()
        assertThat(TaskbarPopupController.canStartSplitDirectly(searchAction())).isFalse()
        assertThat(
            TaskbarPopupController.canStartSplitDirectly(app().apply { intent = Intent() }),
        ).isFalse()
    }

    @Test
    fun accessibilityActions_offerNoSplitForALegacyShortcut() {
        val delegate = accessibilityDelegate()

        assertThat(supportedActions(delegate, app()))
            .containsExactly(DEEP_SHORTCUTS, MOVE_TO_TOP_OR_LEFT, MOVE_TO_BOTTOM_OR_RIGHT)
        assertThat(supportedActions(delegate, legacyShortcut())).containsExactly(DEEP_SHORTCUTS)
    }

    @Test
    fun accessibilitySplitAction_onALegacyShortcut_isRefusedWithoutCrashing() {
        val delegate = accessibilityDelegate()

        // An accessibility service can ask for any action id, offered or not. Before the fix this
        // went on to log and start the split, and threw: this delegate has no stats logger and
        // no LauncherApps.
        for (action in listOf(MOVE_TO_TOP_OR_LEFT, MOVE_TO_BOTTOM_OR_RIGHT)) {
            assertThat(performAction(delegate, legacyShortcut(), action)).isFalse()
            assertThat(performAction(delegate, legacyShortcut(SETTINGS), action)).isFalse()
        }
    }

    @Test
    fun longPressMenuInAnApp_offersNoSplitForALegacyShortcut() {
        val popup = popupController(allowInitialSplitSelection = false)
        val split = popup.createSplitShortcutFactory(SPLIT_LEFT)

        assertThat(split.getShortcut(null, app(), icon())).isNotNull()
        assertThat(split.getShortcut(null, deepShortcut(), icon())).isNotNull()
        assertThat(split.getShortcut(null, legacyShortcut(), icon())).isNull()
    }

    @Test
    fun longPressMenuInOverview_keepsSplitSelectionForALegacyShortcut() {
        // In Overview the menu starts split selection, which launches the item's own intent.
        val popup = popupController(allowInitialSplitSelection = true)
        val split = popup.createSplitShortcutFactory(SPLIT_LEFT)

        assertThat(split.getShortcut(null, legacyShortcut(), icon())).isNotNull()
        assertThat(split.getShortcut(null, app(), icon())).isNotNull()
    }

    @Test
    fun splitSelection_findsNoRunningTaskForAShortcutWithoutAComponent() {
        val controller = allocate(SplitSelectStateController::class.java)
        ReflectionHelpers.setField(
            controller,
            "mSplitSelectDataHolder",
            allocate(SplitSelectDataHolder::class.java),
        )
        val mapsTask = Task(
            Task.TaskKey(
                /* id= */ 7,
                /* windowingMode= */ 0,
                Intent(Intent.ACTION_MAIN).setComponent(MAPS),
                MAPS,
                Process.myUserHandle().identifier,
                /* lastActiveTime= */ 0,
            ),
        )

        // A legacy shortcut without a component has no ComponentKey (getComponentKey() is null).
        assertThat(legacyShortcut().componentKey).isNull()
        assertThat(controller.isInstanceOfComponent(mapsTask, null)).isFalse()
        // An app still finds its running task.
        assertThat(controller.isInstanceOfComponent(mapsTask, app().componentKey)).isTrue()
        val settings = ComponentKey(SETTINGS, Process.myUserHandle())
        assertThat(controller.isInstanceOfComponent(mapsTask, settings)).isFalse()
    }

    @Test
    fun dragIntoSplit_isNotStartedForALegacyShortcut() {
        assertThat(TaskbarDragController.canStartSystemDrag(app())).isTrue()
        assertThat(TaskbarDragController.canStartSystemDrag(deepShortcut())).isTrue()
        // A search action goes to the system as a PendingIntent of its own intent.
        assertThat(TaskbarDragController.canStartSystemDrag(searchAction())).isTrue()
        // Running tasks are dragged by task id.
        assertThat(TaskbarDragController.canStartSystemDrag(Any())).isTrue()

        assertThat(TaskbarDragController.canStartSystemDrag(legacyShortcut())).isFalse()
        assertThat(TaskbarDragController.canStartSystemDrag(legacyShortcut(SETTINGS))).isFalse()
    }

    /** The taskbar icon the menu is opened from. */
    private fun icon() = View(ApplicationProvider.getApplicationContext())

    private fun app() = WorkspaceItemInfo().apply {
        itemType = ITEM_TYPE_APPLICATION
        container = CONTAINER_HOTSEAT
        intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(MAPS)
    }

    private fun deepShortcut() = WorkspaceItemInfo().apply {
        itemType = ITEM_TYPE_DEEP_SHORTCUT
        container = CONTAINER_HOTSEAT
        intent = ShortcutKey.makeIntent("navigate-home", MAPS.packageName)
    }

    /**
     * Like a Contacts direct-dial or Settings Wi-Fi shortcut: an intent with no package and,
     * unless [component] is given, no component.
     */
    private fun legacyShortcut(component: ComponentName? = null) = WorkspaceItemInfo().apply {
        itemType = ITEM_TYPE_SHORTCUT
        container = CONTAINER_HOTSEAT
        intent = Intent(Settings.ACTION_WIFI_SETTINGS).setComponent(component)
    }

    private fun searchAction() = WorkspaceItemInfo().apply {
        itemType = ITEM_TYPE_SEARCH_ACTION
        intent = Intent(Intent.ACTION_VIEW)
    }

    /**
     * The delegate as TaskbarActivityContext builds it, without the taskbar it needs: no
     * LauncherApps and no stats logger, so a split it tries to start throws.
     */
    private fun accessibilityDelegate(): TaskbarShortcutMenuAccessibilityDelegate {
        val delegate = allocate(TaskbarShortcutMenuAccessibilityDelegate::class.java)
        val context = ApplicationProvider.getApplicationContext<Context>()
        ReflectionHelpers.setField(delegate, "mContext", context)
        val actions = SparseArray<Action>()
        ReflectionHelpers.setField(delegate, "mActions", actions)
        for ((id, label) in listOf(
            DEEP_SHORTCUTS to R.string.action_deep_shortcut,
            MOVE_TO_TOP_OR_LEFT to R.string.move_drop_target_top_or_left,
            MOVE_TO_BOTTOM_OR_RIGHT to R.string.move_drop_target_bottom_or_right,
            CREATE_APPLICATION_BUBBLE to R.string.open_app_as_a_bubble,
        )) {
            actions.put(id, delegate.LauncherAction(id, label, KeyEvent.KEYCODE_UNKNOWN))
        }
        return delegate
    }

    /** The actions offered for [item], but the bubble one, which follows a shell flag. */
    private fun supportedActions(
        delegate: TaskbarShortcutMenuAccessibilityDelegate,
        item: ItemInfo,
    ): List<Int> {
        val out = ArrayList<Action>()
        ReflectionHelpers.callInstanceMethod<Unit>(
            delegate,
            "getSupportedActions",
            ClassParameter.from(View::class.java, null),
            ClassParameter.from(ItemInfo::class.java, item),
            ClassParameter.from(List::class.java, out),
        )
        return out.map { it.accessibilityAction.id }.filter { it != CREATE_APPLICATION_BUBBLE }
    }

    private fun performAction(
        delegate: TaskbarShortcutMenuAccessibilityDelegate,
        item: ItemInfo,
        action: Int,
    ): Boolean = ReflectionHelpers.callInstanceMethod(
        delegate,
        "performAction",
        ClassParameter.from(View::class.java, null),
        ClassParameter.from(ItemInfo::class.java, item),
        ClassParameter.from(Int::class.javaPrimitiveType, action),
        ClassParameter.from(Boolean::class.javaPrimitiveType, false),
    )

    /** The controller's split factory only reads whether split selection takes over. */
    private fun popupController(allowInitialSplitSelection: Boolean): TaskbarPopupController {
        val popup = allocate(TaskbarPopupController::class.java)
        popup.setAllowInitialSplitSelection(allowInitialSplitSelection)
        return popup
    }

    /** Builds [type] without running its constructor, which needs the whole taskbar. */
    private fun <T> allocate(type: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
            .get(null)
        return type.cast(
            unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type),
        )
    }

    private companion object {
        val MAPS = ComponentName(
            "com.google.android.apps.maps",
            "com.google.android.maps.MapsActivity",
        )
        val SETTINGS = ComponentName(
            "com.android.settings",
            "com.android.settings.Settings\$WifiSettingsActivity",
        )
        val SPLIT_LEFT = SplitPositionOption(
            R.drawable.ic_split_horizontal,
            R.string.recent_task_option_split_screen,
            STAGE_POSITION_TOP_OR_LEFT,
            STAGE_TYPE_MAIN,
        )
    }
}
