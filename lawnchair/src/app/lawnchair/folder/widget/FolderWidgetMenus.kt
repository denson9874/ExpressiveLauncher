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

    /** Menu entries (string resources) for a folder: Make widget for Home folders, widget actions for widgets. */
    @JvmStatic
    fun itemsFor(info: FolderInfo): List<Int> = when {
        info.container != Favorites.CONTAINER_DESKTOP -> emptyList()
        FolderWidgets.isFolderWidget(info) -> listOf(
            R.string.folder_widget_customize,
            R.string.folder_widget_make_folder,
            R.string.folder_widget_remove,
        )
        else -> listOf(R.string.folder_widget_make_widget)
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
                onItemClick(launcher, icon, labelRes)
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
                } else if (icon is FolderWidgetView) {
                    FolderWidgetResizeFrame.show(launcher, icon)
                }
            }
        }
    }

    /** Runs a menu entry; returns true so the menu closes. */
    private fun onItemClick(launcher: Launcher, icon: FolderIcon, labelRes: Int): Boolean = true

    private fun iconFor(labelRes: Int): Int = when (labelRes) {
        R.string.folder_widget_make_widget -> R.drawable.ic_widget
        R.string.folder_widget_customize -> R.drawable.ic_palette
        R.string.folder_widget_make_folder -> R.drawable.ic_folder
        else -> R.drawable.ic_remove_no_shadow
    }
}
