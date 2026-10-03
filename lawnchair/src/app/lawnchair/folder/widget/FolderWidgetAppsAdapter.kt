package app.lawnchair.folder.widget

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.android.launcher3.BubbleTextView
import com.android.launcher3.R
import com.android.launcher3.apppairs.AppPairIcon
import com.android.launcher3.model.data.AppPairInfo
import com.android.launcher3.model.data.FolderInfo
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.model.data.ItemInfoWithIcon
import com.android.launcher3.model.data.WorkspaceItemInfo
import com.android.launcher3.views.ActivityContext
import kotlin.math.max

/**
 * The apps of a Folder widget as real folder icons (app pairs as app-pair icons), sized to the
 * panel's grid. Taps launch through the activity's item click listener, from the tapped icon.
 */
class FolderWidgetAppsAdapter(
    private val activityContext: ActivityContext,
    private val folder: FolderInfo,
    private val gridSpec: () -> FolderWidgetGridSpec,
    private val onAppLongClick: (BubbleTextView) -> Boolean,
) : RecyclerView.Adapter<FolderWidgetAppsAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view)

    /** An app whose grid icon stays hidden while a dropped icon animates into its cell. */
    var hiddenItem: ItemInfo? = null
        set(value) {
            val old = field
            field = value
            listOf(old, value).forEach { item ->
                val index = if (item == null) -1 else folder.getContents().indexOf(item)
                if (index >= 0) notifyItemChanged(index)
            }
        }

    override fun getItemCount(): Int = folder.getContents().size

    override fun getItemViewType(position: Int): Int =
        if (folder.getContents()[position] is AppPairInfo) TYPE_APP_PAIR else TYPE_APP

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        if (viewType == TYPE_APP_PAIR) {
            FrameLayout(parent.context)
        } else {
            LayoutInflater.from(parent.context).inflate(R.layout.folder_application, null)
        },
    )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = folder.getContents()[position]
        holder.itemView.visibility = if (item === hiddenItem) View.INVISIBLE else View.VISIBLE
        if (item is AppPairInfo) {
            val container = holder.itemView as FrameLayout
            container.removeAllViews()
            val pair = AppPairIcon.inflateIcon(
                R.layout.folder_app_pair,
                activityContext,
                null,
                item,
                BubbleTextView.DISPLAY_FOLDER,
            )
            container.addView(pair, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            return
        }
        val icon = holder.itemView as BubbleTextView
        when (item) {
            is WorkspaceItemInfo -> icon.applyFromWorkspaceItem(item)
            is ItemInfoWithIcon -> icon.applyFromItemInfoWithIcon(item)
        }
        applyGridSpec(icon)
        icon.setOnClickListener(activityContext.itemOnClickListener)
        icon.setOnLongClickListener { onAppLongClick(it as BubbleTextView) }
    }

    /** Sizes an icon to the grid; without labels the icon is centered in its row. */
    fun applyGridSpec(icon: BubbleTextView) {
        val spec = gridSpec()
        icon.setIconSizeOverridePx(spec.iconSizePx)
        icon.setTextVisibility(spec.labelsVisible)
        icon.setCenterVertically(spec.labelsVisible)
        // Without labels the text is cleared too: the shortcuts popup restores text visibility
        // when it closes. The content description keeps the app's name.
        if (!spec.labelsVisible) {
            icon.text = ""
            val vPad = maxOf(0, (spec.rowHeightPx - spec.iconSizePx) / 2)
            icon.setPadding(icon.paddingLeft, vPad, icon.paddingRight, vPad)
        } else {
            if (icon.text.isNullOrEmpty()) {
                val item = icon.tag as? ItemInfo
                if (item != null) icon.text = item.title
            }
        }
    }

    private companion object {
        const val TYPE_APP = 0
        const val TYPE_APP_PAIR = 1
        const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
