package app.lawnchair.folder

import android.view.View
import android.view.ViewGroup

/**
 * Launcher3 hides an open folder's title when the footer row leaves it less than
 * `folder_title_min_width`, because the page indicator shares that row. Expressive puts its own
 * buttons in the same row (⋮, add apps, customize). They must not count against the title: the ⋮
 * alone hid the title of every 2-column Home folder, so those folders couldn't be renamed.
 */
object FolderTitleRoom {

    /**
     * The width the title would get in a measured [footer] if the [ignored] views weren't there.
     * Pass the title itself and Expressive's own buttons.
     */
    @JvmStatic
    fun roomForTitle(footer: ViewGroup, vararg ignored: View?): Int {
        var room = footer.measuredWidth - footer.paddingLeft - footer.paddingRight
        for (i in 0 until footer.childCount) {
            val child = footer.getChildAt(i)
            if (child.visibility == View.GONE || ignored.any { it === child }) continue
            val lp = child.layoutParams as? ViewGroup.MarginLayoutParams
            room -= child.measuredWidth + (lp?.marginStart ?: 0) + (lp?.marginEnd ?: 0)
        }
        return room
    }

    @JvmStatic
    fun shouldHideTitle(room: Int, minTitleWidth: Int): Boolean = room < minTitleWidth
}
