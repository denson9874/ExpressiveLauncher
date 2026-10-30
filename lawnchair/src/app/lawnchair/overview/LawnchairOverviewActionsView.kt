package app.lawnchair.overview

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Space
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.util.isOnePlusStock
import com.android.launcher3.R
import com.android.quickstep.views.OverviewActionsView
import kotlin.math.ceil

class LawnchairOverviewActionsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : OverviewActionsView<TaskOverlayFactoryImpl.OverlayUICallbacks>(context, attrs, defStyleAttr) {

    private val prefs = PreferenceManager.getInstance(context)
    private lateinit var container: LinearLayout
    private lateinit var screenshotAction: Button
    private lateinit var shareAction: Button
    private lateinit var lensAction: Button
    private lateinit var clearAllAction: Button
    private lateinit var lockedAction: Button
    private lateinit var labels: Map<Button, CharSequence>
    private var labelledMinWidth = 0
    private var labelledMinimumWidth = 0
    private var labelledDrawablePadding = 0
    private var iconOnly = false

    override fun onFinishInflate() {
        super.onFinishInflate()

        container = ViewCompat.requireViewById(this, R.id.action_buttons)
        clearAllAction = ViewCompat.requireViewById(this, R.id.action_clear_all)
        shareAction = ViewCompat.requireViewById(this, R.id.action_share)
        lensAction = ViewCompat.requireViewById(this, R.id.action_lens)
        screenshotAction = ViewCompat.requireViewById(this, R.id.action_screenshot)
        lockedAction = ViewCompat.requireViewById(this, R.id.action_locked)
        labels = listOf(screenshotAction, shareAction, lensAction, lockedAction, clearAllAction)
            .associateWith { it.text }
        labelledMinWidth = screenshotAction.minWidth
        labelledMinimumWidth = screenshotAction.minimumWidth
        labelledDrawablePadding = screenshotAction.compoundDrawablePadding

        shareAction.setOnClickListener { mCallbacks?.onShare() }
        lensAction.setOnClickListener { mCallbacks?.onLens() }

        prefs.recentsActionClearAll.subscribeChanges(this, ::updateVisibilities)
        prefs.recentsActionLens.subscribeChanges(this, ::updateVisibilities)
        prefs.recentsActionScreenshot.subscribeChanges(this, ::updateVisibilities)
        prefs.recentsActionShare.subscribeChanges(this, ::updateVisibilities)
        prefs.recentsActionLocked.subscribeChanges(this, ::updateVisibilities)
        prefs.recentActionOrder.subscribeChanges(this, ::updateVisibilities)

        updateVisibilities()
    }

    private fun updateVisibilities() {
        val order = prefs.recentActionOrder.get().split(",").map { it.toInt() }

        val buttonMap = mutableMapOf<Int, View>()
        if (prefs.recentsActionScreenshot.get() && !isOnePlusStock) {
            buttonMap[0] = screenshotAction
        }
        if (prefs.recentsActionShare.get()) {
            buttonMap[1] = shareAction
        }
        if (prefs.recentsActionLens.get() && isLensAvailable()) {
            buttonMap[2] = lensAction
        }
        if (prefs.recentsActionLocked.get()) {
            buttonMap[3] = lockedAction
        }
        if (prefs.recentsActionClearAll.get()) {
            buttonMap[4] = clearAllAction
        }

        val buttonsInOrder = order.mapNotNull { buttonMap[it] }

        container.removeAllViews()
        container.addView(createSpace())
        buttonsInOrder.forEach { view ->
            view.isVisible = true
            container.addView(view)
            container.addView(createSpace())
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Buttons in a LinearLayout don't shrink, so a row that doesn't fit (four or more actions,
        // a long translation or a large font) would push the last ones, often Clear all, off
        // screen. Show icons only then; each button keeps its label as description and tooltip.
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight -
                container.paddingLeft - container.paddingRight
            setIconOnly(labelledRowWidth() > available)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun labelledRowWidth(): Int = labels.entries
        .filter { (button, _) -> button.parent == container && button.isVisible }
        .sumOf { (button, label) ->
            val icon = button.compoundDrawablesRelative[0]?.intrinsicWidth ?: 0
            val text = ceil(button.paint.measureText(label, 0, label.length)).toInt()
            val padding = button.paddingStart + button.paddingEnd + labelledDrawablePadding
            val width = padding + icon + text
            val params = button.layoutParams as? MarginLayoutParams
            maxOf(width, labelledMinWidth, labelledMinimumWidth) +
                (params?.marginStart ?: 0) + (params?.marginEnd ?: 0)
        }

    private fun setIconOnly(iconOnly: Boolean) {
        if (this.iconOnly == iconOnly) return
        this.iconOnly = iconOnly
        labels.forEach { (button, label) ->
            button.text = if (iconOnly) null else label
            button.contentDescription = if (iconOnly) label else null
            button.tooltipText = if (iconOnly) label else null
            button.compoundDrawablePadding = if (iconOnly) 0 else labelledDrawablePadding
            button.minWidth = if (iconOnly) 0 else labelledMinWidth
            button.minimumWidth = if (iconOnly) 0 else labelledMinimumWidth
        }
    }

    override fun updateDisabledFlags(disabledFlags: Int, enable: Boolean) {
        super.updateDisabledFlags(disabledFlags, enable)
        // Clear all doesn't act on the centred task, so a missing or rotated preview mustn't
        // disable it the way it disables Screenshot, Share and Lens. Scrolling still does.
        if (::clearAllAction.isInitialized) {
            clearAllAction.isEnabled = (mDisabledFlags and DISABLED_SCROLLING) == 0
        }
    }

    private fun isLensAvailable(): Boolean {
        val lensIntent = context.packageManager.getLaunchIntentForPackage("com.google.ar.lens")
        return lensIntent != null
    }

    private fun createSpace(): View {
        return Space(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1).apply { weight = 1f }
        }
    }

    override fun setClearAllClickListener(clearAllClickListener: OnClickListener?) {
        clearAllAction.setOnClickListener(clearAllClickListener)
    }
}
