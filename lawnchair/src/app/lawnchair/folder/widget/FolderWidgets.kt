package app.lawnchair.folder.widget

import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.model.data.FolderInfo
import com.android.launcher3.model.data.ItemInfo

/**
 * Rules for Folder widgets: desktop folder rows flagged with [FolderInfo.FLAG_FOLDER_WIDGET] that
 * span at least [MIN_CELLS] cells and show their apps as a grid on Home.
 */
object FolderWidgets {
    const val MIN_CELLS = 2
    const val DEFAULT_SPAN = 2

    @JvmStatic
    fun isFolderWidget(info: ItemInfo?): Boolean =
        info is FolderInfo && info.hasOption(FolderInfo.FLAG_FOLDER_WIDGET)

    /** A loaded folder row becomes a widget on Home when it is flagged or larger than one cell. */
    @JvmStatic
    fun wantsWidget(container: Int, options: Int, spanX: Int, spanY: Int): Boolean =
        container == Favorites.CONTAINER_DESKTOP &&
            ((options and FolderInfo.FLAG_FOLDER_WIDGET) != 0 || spanX * spanY > 1)

    /** The options a loaded folder row gets: the widget flag exactly when it [wantsWidget]. */
    @JvmStatic
    fun loadedOptions(container: Int, options: Int, spanX: Int, spanY: Int): Int =
        if (wantsWidget(container, options, spanX, spanY)) {
            options or FolderInfo.FLAG_FOLDER_WIDGET
        } else {
            options and FolderInfo.FLAG_FOLDER_WIDGET.inv()
        }

    @JvmStatic
    fun isValidSpan(spanX: Int, spanY: Int): Boolean =
        spanX >= 1 && spanY >= 1 && spanX * spanY >= MIN_CELLS

    /** Folder widgets stay on Home with one app or none; normal folders dissolve. */
    @JvmStatic
    fun canDissolve(info: FolderInfo): Boolean = !isFolderWidget(info)

    @JvmStatic
    fun acceptsDrop(item: ItemInfo): Boolean = when (item.itemType) {
        Favorites.ITEM_TYPE_APPLICATION,
        Favorites.ITEM_TYPE_SHORTCUT,
        Favorites.ITEM_TYPE_DEEP_SHORTCUT,
        -> true
        else -> false
    }

    /** Whether [item] (this very object) is already one of the folder's apps. */
    @JvmStatic
    fun isInFolder(folder: FolderInfo, item: ItemInfo): Boolean = folder.getContents().any { it === item }

    /** The smallest span a drag may shrink an item to; a Folder widget always keeps its size. */
    @JvmStatic
    fun dragMinSpan(item: ItemInfo): IntArray = when {
        isFolderWidget(item) -> intArrayOf(item.spanX, item.spanY)
        item.minSpanX > 0 && item.minSpanY > 0 -> intArrayOf(item.minSpanX, item.minSpanY)
        else -> intArrayOf(item.spanX, item.spanY)
    }

    /** Whether the full folder opens with [itemCount] apps; a Folder widget opens with one. */
    @JvmStatic
    fun canOpen(info: FolderInfo, itemCount: Int): Boolean = itemCount >= if (isFolderWidget(info)) 1 else 2

    /** The loader's empty-folder clean-up: folders that no item points to, except Folder widgets. */
    @JvmStatic
    fun emptyFoldersSelection(): String =
        "${Favorites.ITEM_TYPE} = ${Favorites.ITEM_TYPE_FOLDER} AND " +
            "${Favorites._ID} NOT IN (SELECT ${Favorites.CONTAINER} FROM ${Favorites.TABLE_NAME}) AND " +
            "(${Favorites.OPTIONS} & ${FolderInfo.FLAG_FOLDER_WIDGET}) = 0"
}
