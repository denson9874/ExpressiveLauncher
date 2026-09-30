package app.lawnchair.search

import android.util.SparseIntArray
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.util.contains
import app.lawnchair.allapps.views.SearchItemDecorator
import app.lawnchair.allapps.views.SearchResultView
import app.lawnchair.search.adapter.SearchAdapterItem
import com.android.app.search.LayoutType
import com.android.launcher3.DeviceProfile
import androidx.recyclerview.widget.RecyclerView
import com.android.launcher3.R
import com.android.launcher3.Utilities
import com.android.launcher3.allapps.ActivityAllAppsContainerView
import com.android.launcher3.allapps.AllAppsGridAdapter
import com.android.launcher3.allapps.BaseAllAppsAdapter
import com.android.launcher3.allapps.search.DefaultSearchAdapterProvider
import com.android.launcher3.views.ActivityContext

class LawnchairSearchAdapterProvider(
    launcher: ActivityContext,
    private val appsView: ActivityAllAppsContainerView<*>,
) : DefaultSearchAdapterProvider(launcher) {

    private val decorator = SearchItemDecorator(appsView)
    private val layoutIdMap = SparseIntArray().apply {
        append(SEARCH_RESULT_ICON, R.layout.search_result_icon)
        append(SEARCH_RESULT_ICON_ROW, R.layout.search_result_tall_icon_row)
        append(SEARCH_RESULT_SMALL_ICON_ROW, R.layout.search_result_small_icon_row)
        append(SEARCH_RESULT_DIVIDER, R.layout.search_result_divider)
        append(SEARCH_TEXT_HEADER, R.layout.search_result_text_header)
        append(SEARCH_PEOPLE_TILE, R.layout.search_result_icon_right_left)
        append(SEARCH_RESULT_FILE_TILE, R.layout.search_result_icon_right_left)
        append(SEARCH_RESULT_SUGGESTION_TILE, R.layout.search_result_small_icon_row)
        append(SEARCH_RESULT_SETTINGS_TILE, R.layout.search_result_small_icon_row)
        append(SEARCH_RESULT_RECENT_TILE, R.layout.search_result_small_icon_row)
        append(SEARCH_RESULT_CALCULATOR, R.layout.search_result_tall_icon_row_calculator)
        append(SEARCH_RESULT_EMPTY_STATE, R.layout.search_result_empty_state)
        append(SEARCH_RESULT_SEARCH_SETTINGS, R.layout.search_result_search_settings)
    }
    private val submitGate = SearchSubmitGate()
    private val runPendingSubmit = Runnable {
        submitGate.onQuickLaunchReady(currentQuery()) { findQuickLaunchView()?.launch() ?: false }
    }
    private var quickLaunchItem: SearchResultView? = null
        set(value) {
            field = value
            appsView.searchUiManager.setFocusedResultTitle(
                field?.titleText,
                field?.titleText,
                field != null,
            )
            appsView.mSearchRecyclerView.invalidate()
            if (value != null && submitGate.hasPendingSubmit) {
                // Bound during layout: launch after it, never from inside onBindView.
                appsView.mSearchRecyclerView.removeCallbacks(runPendingSubmit)
                appsView.mSearchRecyclerView.post(runPendingSubmit)
            }
        }

    /** Results for [query] were handed to the list. */
    fun onSearchResultsShown(query: String) {
        submitGate.onResultsShown(query)
        if (submitGate.hasPendingSubmit) {
            // An unchanged list is not rebound, so also check once the results are laid out.
            appsView.mSearchRecyclerView.removeCallbacks(runPendingSubmit)
            appsView.mSearchRecyclerView.post(runPendingSubmit)
        }
    }

    /** Search was cleared or closed. */
    fun onSearchCleared() {
        appsView.mSearchRecyclerView.removeCallbacks(runPendingSubmit)
        submitGate.reset()
    }

    private fun currentQuery(): String =
        appsView.searchUiManager.editText?.text?.let { Utilities.trim(it) }.orEmpty()

    /** The on-screen quick-launch row for the current list, or null while the list is changing. */
    private fun findQuickLaunchView(): SearchResultView? {
        val recyclerView = appsView.mSearchRecyclerView
        if (recyclerView.hasPendingAdapterUpdates()) return null
        quickLaunchItem?.takeIf { it.isQuickLaunch && (it as View).isAttachedToWindow }?.let { return it }
        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i)
            if (child is SearchResultView && child.isQuickLaunch &&
                recyclerView.getChildAdapterPosition(child) != RecyclerView.NO_POSITION
            ) {
                return child
            }
        }
        return null
    }

    override fun isViewSupported(viewType: Int): Boolean = layoutIdMap.contains(viewType)

    override fun onBindView(holder: BaseAllAppsAdapter.ViewHolder, position: Int) {
        val adapterItem = appsView.mSearchRecyclerView.mApps.adapterItems
            .getOrNull(position) as? SearchAdapterItem
        if (adapterItem == null) {
            // Search results are replaced asynchronously. RecyclerView can request one final bind
            // for the old list during a rapid filter/scroll transition; do not index stale data.
            holder.itemView.visibility = View.INVISIBLE
            return
        }
        holder.itemView.visibility = View.VISIBLE
        adapterItem.setRippleEffect(holder.itemView)
        val itemView = holder.itemView as SearchResultView
        itemView.bind(
            adapterItem.searchTarget,
            emptyList(),
        )
        if (itemView.isQuickLaunch) {
            quickLaunchItem = itemView
        } else if (quickLaunchItem === itemView) {
            quickLaunchItem = null
        }
    }

    override fun onCreateViewHolder(
        layoutInflater: LayoutInflater,
        parent: ViewGroup?,
        viewType: Int,
    ): BaseAllAppsAdapter.ViewHolder {
        val view = layoutInflater.inflate(layoutIdMap[viewType], parent, false)
        val grid: DeviceProfile = mLauncher.deviceProfile
        val leftMargin = grid.allAppsPadding.left
        val rightMargin = grid.allAppsPadding.right

        if (viewType != SEARCH_RESULT_ICON) {
            val layoutParams = ViewGroup.MarginLayoutParams(view.layoutParams)
            layoutParams.leftMargin = 0
            layoutParams.rightMargin = 0
            view.layoutParams = layoutParams
        }
        if (viewType == SEARCH_TEXT_HEADER) {
            val layoutParams: ViewGroup.MarginLayoutParams = ViewGroup.MarginLayoutParams(0, 0)
            layoutParams.leftMargin = 0
            layoutParams.rightMargin = 0
            view.layoutParams = layoutParams
        }

        return BaseAllAppsAdapter.ViewHolder(view)
    }

    override fun getItemsPerRow(viewType: Int, appsPerRow: Int) = if (viewType != SEARCH_RESULT_ICON) 1 else super.getItemsPerRow(viewType, appsPerRow)

    override fun launchHighlightedItem(): Boolean =
        submitGate.submit(currentQuery()) { findQuickLaunchView()?.launch() ?: false }

    override fun getHighlightedItem() = quickLaunchItem as View?

    override fun clearHighlightedItem() {
        super.clearHighlightedItem()
        quickLaunchItem = null
    }

    override fun getDecorator() = decorator

    companion object {
        private const val SEARCH_RESULT_ICON = (1 shl 10) or AllAppsGridAdapter.VIEW_TYPE_ICON
        private const val SEARCH_RESULT_ICON_ROW = 1 shl 11
        private const val SEARCH_RESULT_SMALL_ICON_ROW = 1 shl 12
        private const val SEARCH_RESULT_DIVIDER = 1 shl 13
        private const val SEARCH_TEXT_HEADER = 1 shl 14
        private const val SEARCH_PEOPLE_TILE = 1 shl 15
        private const val SEARCH_RESULT_FILE_TILE = 1 shl 16
        private const val SEARCH_RESULT_SUGGESTION_TILE = 1 shl 17
        private const val SEARCH_RESULT_SETTINGS_TILE = 1 shl 18
        private const val SEARCH_RESULT_RECENT_TILE = 1 shl 19
        private const val SEARCH_RESULT_CALCULATOR = 1 shl 20
        private const val SEARCH_RESULT_EMPTY_STATE = 1 shl 21
        private const val SEARCH_RESULT_SEARCH_SETTINGS = 1 shl 22

        val viewTypeMap = mapOf(
            LayoutType.ICON_SINGLE_VERTICAL_TEXT to SEARCH_RESULT_ICON,
            LayoutType.ICON_HORIZONTAL_TEXT to SEARCH_RESULT_ICON_ROW,
            LayoutType.SMALL_ICON_HORIZONTAL_TEXT to SEARCH_RESULT_SMALL_ICON_ROW,
            LayoutType.HORIZONTAL_MEDIUM_TEXT to SEARCH_RESULT_SUGGESTION_TILE,
            LayoutType.EMPTY_DIVIDER to SEARCH_RESULT_DIVIDER,
            LayoutType.TEXT_HEADER to SEARCH_TEXT_HEADER,
            LayoutType.PEOPLE_TILE to SEARCH_PEOPLE_TILE,
            LayoutType.THUMBNAIL to SEARCH_RESULT_FILE_TILE,
            LayoutType.ICON_SLICE to SEARCH_RESULT_SETTINGS_TILE,
            LayoutType.WIDGET_LIVE to SEARCH_RESULT_RECENT_TILE,
            LayoutType.CALCULATOR to SEARCH_RESULT_CALCULATOR,
            LayoutType.EMPTY_STATE to SEARCH_RESULT_EMPTY_STATE,
            LayoutType.SEARCH_SETTINGS to SEARCH_RESULT_SEARCH_SETTINGS,
        )
    }
}
