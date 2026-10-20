package app.lawnchair.data.category

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.android.launcher3.R
import com.android.launcher3.allapps.FloatingHeaderRow
import com.android.launcher3.allapps.FloatingHeaderView

/**
 * Scrollable chip bar for filtering All Apps by smart categories.
 * Implements [FloatingHeaderRow] to smoothly integrate into [FloatingHeaderView].
 */
class AllAppsCategoryTabs @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : HorizontalScrollView(context, attrs, defStyleAttr), FloatingHeaderRow {

    private val container = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
    }

    fun interface OnCategorySelectedListener {
        fun onCategorySelected(category: AppCategory)
    }

    private val buttons = mutableMapOf<AppCategory, Button>()
    var onCategorySelectedListener: OnCategorySelectedListener? = null
    var selectedCategory: AppCategory = AppCategory.ALL
        private set

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        clipToPadding = false
        addView(container)

        val marginPx = (context.resources.displayMetrics.density * 4).toInt()
        val paddingHorizontal = (context.resources.displayMetrics.density * 16).toInt()
        val paddingVertical = (context.resources.displayMetrics.density * 8).toInt()

        AppCategory.values().forEach { category ->
            val button = Button(context).apply {
                text = context.getString(category.labelRes)
                setTextAppearance(R.style.AllAppsPersonalWorkTabStyle)
                background = ContextCompat.getDrawable(context, R.drawable.all_apps_tabs_background)
                setTextColor(ContextCompat.getColorStateList(context, R.color.all_apps_tab_text))
                setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical)
                isSelected = category == AppCategory.ALL
                isAllCaps = false
                textSize = 13f

                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    setMargins(marginPx, 0, marginPx, 0)
                }
                layoutParams = lp

                setOnClickListener {
                    selectCategory(category)
                }
            }
            buttons[category] = button
            container.addView(button)
        }
    }

    fun selectCategory(category: AppCategory) {
        if (selectedCategory == category) return
        selectedCategory = category
        buttons.forEach { (cat, btn) ->
            btn.isSelected = (cat == category)
        }
        onCategorySelectedListener?.onCategorySelected(category)
    }

    override fun setup(parent: FloatingHeaderView?, allRows: Array<out FloatingHeaderRow>?, tabsHidden: Boolean) {
        // No special multi-row dependency required
    }

    override fun getExpectedHeight(): Int {
        return context.resources.getDimensionPixelSize(R.dimen.all_apps_header_pill_height)
    }

    override fun shouldDraw(): Boolean = visibility != View.GONE

    override fun hasVisibleContent(): Boolean = visibility != View.GONE

    override fun setVerticalScroll(scroll: Int, isScrolledOut: Boolean) {
        if (visibility == View.GONE) return
        visibility = if (isScrolledOut) View.INVISIBLE else View.VISIBLE
        if (!isScrolledOut) {
            translationY = scroll.toFloat()
        }
    }

    override fun getTypeClass(): Class<out FloatingHeaderRow> = AllAppsCategoryTabs::class.java

    override fun getFocusedChild(): View? = findFocus()
}
