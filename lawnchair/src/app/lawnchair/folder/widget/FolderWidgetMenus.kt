package app.lawnchair.folder.widget

import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.ViewConfiguration
import com.android.launcher3.DropTarget
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.R
import com.android.launcher3.dragndrop.DragOptions
import com.android.launcher3.folder.FolderIcon
import com.android.launcher3.logging.StatsLogManager.LauncherEvent
import com.android.launcher3.model.data.FolderInfo
import com.android.launcher3.views.OptionsPopupView
import com.android.launcher3.views.OptionsPopupView.OptionItem

/**
 * The long-press menus of Home folders and Folder widgets. The menu shows while the folder or
 * widget is picked up; moving the finger starts the usual drag and closes it.
 */
object FolderWidgetMenus {

    /**
     * Menu entries (string resources) for a folder: Make widget for Home folders, widget actions for
     * widgets, nothing while Home is locked (every entry changes the layout or its look).
     */
    @JvmStatic
    @JvmOverloads
    fun itemsFor(info: FolderInfo, homeLocked: Boolean = false): List<Int> = when {
        homeLocked || info.container != Favorites.CONTAINER_DESKTOP -> emptyList()
        FolderWidgets.isFolderWidget(info) -> listOf(
            R.string.folder_widget_customize,
            R.string.folder_widget_make_folder,
            R.string.folder_widget_remove,
        )
        else -> listOf(R.string.folder_widget_make_widget)
    }

    /** Whether a long-press on this folder shows its menu. */
    @JvmStatic
    fun hasMenu(launcher: Launcher, info: FolderInfo): Boolean =
        itemsFor(info, FolderWidgetController.isHomeLocked(launcher)).isNotEmpty()

    /** The open folder's footer button: the other kind (Make widget or Make normal folder), or none. */
    @JvmStatic
    fun footerActionFor(info: FolderInfo, homeLocked: Boolean): Int? =
        itemsFor(info, homeLocked).firstOrNull {
            it == R.string.folder_widget_make_widget || it == R.string.folder_widget_make_folder
        }

    /**
     * The open folder's ⋮ button: shows the footer action (Make widget / Make normal folder) in a
     * small menu, so converting takes a deliberate second tap (community request TG-002).
     * Returns false when the folder has no footer action.
     */
    @JvmStatic
    fun showFooterOverflow(launcher: Launcher, icon: FolderIcon, anchor: View): Boolean {
        val action = footerActionFor(icon.mInfo, FolderWidgetController.isHomeLocked(launcher))
            ?: return false
        val bounds = Rect()
        launcher.dragLayer.getDescendantRectRelativeToSelf(anchor, bounds)
        val item = OptionItem(launcher, action, iconFor(action), LauncherEvent.IGNORE) { _: View ->
            runAction(launcher, icon, action)
            true
        }
        OptionsPopupView.show<Launcher>(launcher, RectF(bounds), listOf(item), true)
        return true
    }

    /**
     * Shows the menu for [icon] and returns the pre-drag condition for its drag: moving past the
     * touch slop starts the drag and closes the menu; releasing without moving keeps the menu and,
     * for a Folder widget, shows its resize frame.
     */
    @JvmStatic
    fun show(launcher: Launcher, icon: FolderIcon): DragOptions.PreDragCondition {
        val bounds = Rect()
        launcher.dragLayer.getDescendantRectRelativeToSelf(icon, bounds)
        val items = itemsFor(icon.mInfo).map { labelRes ->
            OptionItem(launcher, labelRes, iconFor(labelRes), LauncherEvent.IGNORE) { _: View ->
                runAction(launcher, icon, labelRes)
                true
            }
        }
        val menu = OptionsPopupView.show<Launcher>(launcher, RectF(bounds), items, true)
        val touchSlop = ViewConfiguration.get(launcher).scaledTouchSlop
        return object : DragOptions.PreDragCondition {
            override fun shouldStartDrag(distanceDragged: Double) = distanceDragged > touchSlop

            override fun onPreDragStart(dragObject: DropTarget.DragObject) = Unit

            override fun onPreDragEnd(dragObject: DropTarget.DragObject, dragStarted: Boolean) {
                if (dragStarted) {
                    menu?.close(true)
                    return
                }
                // Workspace.startDrag hid the icon; a cancelled pre-drag (pause, back, cancel)
                // has no drop to show it again.
                icon.visibility = View.VISIBLE
                if (icon is FolderWidgetView && launcher.hasBeenResumed()) {
                    FolderWidgetResizeFrame.show(launcher, icon)
                }
            }
        }
    }

    /** Runs a menu or footer entry. Customize opens the widget settings once they exist (Task 14). */
    @JvmStatic
    fun runAction(launcher: Launcher, icon: FolderIcon, labelRes: Int) {
        when (labelRes) {
            R.string.folder_widget_make_widget -> FolderWidgetController.makeWidget(launcher, icon)
            R.string.folder_widget_make_folder ->
                (icon as? FolderWidgetView)?.let { FolderWidgetController.makeNormalFolder(launcher, it) }
            R.string.folder_widget_remove ->
                (icon as? FolderWidgetView)?.let { FolderWidgetController.confirmRemove(launcher, it) }
            R.string.folder_widget_customize ->
                (icon as? FolderWidgetView)?.let { FolderWidgetController.showSettingsSheet(launcher, it) }
        }
    }

    @JvmStatic
    fun iconFor(labelRes: Int): Int = when (labelRes) {
        R.string.folder_widget_make_widget -> R.drawable.ic_widget
        R.string.folder_widget_customize -> R.drawable.ic_palette
        R.string.folder_widget_make_folder -> R.drawable.ic_folder
        else -> R.drawable.ic_remove_no_shadow
    }
}
