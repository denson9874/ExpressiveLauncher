package app.lawnchair.smartspace

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.viewpager.widget.ViewPager
import app.lawnchair.LawnchairLauncher
import app.lawnchair.launcher
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import app.lawnchair.ui.preferences.PreferenceActivity
import app.lawnchair.ui.preferences.navigation.Smartspace
import com.android.launcher3.R
import com.android.launcher3.logging.StatsLogManager
import com.android.launcher3.views.OptionsPopupView
import com.kieronquinn.app.smartspacer.sdk.client.R as SmartspacerR
import com.kieronquinn.app.smartspacer.sdk.client.views.BcSmartspaceView as SmartspacerSdkView
import com.kieronquinn.app.smartspacer.sdk.client.views.popup.Popup
import com.kieronquinn.app.smartspacer.sdk.client.views.popup.PopupFactory
import com.kieronquinn.app.smartspacer.sdk.model.SmartspaceConfig
import com.kieronquinn.app.smartspacer.sdk.model.SmartspaceTarget
import com.kieronquinn.app.smartspacer.sdk.model.UiSurface

class SmartspacerView(context: Context, attrs: AttributeSet?) : SmartspacerSdkView(context, attrs) {
    private val prefs2 = PreferenceManager2.getInstance(context)
    private var sdkViewPager: ViewPager? = null
    private var sdkIndicator: View? = null
    private var fallbackView: View? = null
    private var receivedTargetsSinceAttach = false
    private val showFallback = Runnable {
        if (isAttachedToWindow && !receivedTargetsSinceAttach) {
            showLocalFallback()
        }
    }

    /**
     * The SDK creates its IPC helper lazily from this value. Read the cached preference before
     * that first access so a recreated launcher never binds with a stale default target count.
     * The former detached CoroutineScope was never cancelled and could also retain this View.
     */
    override val config: SmartspaceConfig by lazy(LazyThreadSafetyMode.NONE) {
        SmartspaceConfig(
            sanitizeSmartspacerTargetCount(prefs2.smartspacerMaxCount.firstCached(prefs2)),
            UiSurface.HOMESCREEN,
            context.packageName,
        )
    }

    init {
        popupFactory = object : PopupFactory {
            override fun createPopup(
                context: Context,
                anchorView: View,
                target: SmartspaceTarget,
                backgroundColor: Int,
                textColour: Int,
                launchIntent: (Intent?) -> Unit,
                dismissAction: ((SmartspaceTarget) -> Unit)?,
                aboutIntent: Intent?,
                feedbackIntent: Intent?,
                settingsIntent: Intent?,
            ): Popup {
                val launcher = context.launcher
                val pos = Rect()
                launcher.dragLayer.getDescendantRectRelativeToSelf(anchorView, pos)
                val options = listOfNotNull(
                    getAboutOption(launchIntent, aboutIntent),
                    getCustomizeOption(launchIntent, settingsIntent),
                    getFeedbackOption(launchIntent, feedbackIntent),
                    getDismissOption(target, dismissAction),
                ).ifEmpty { listOf(getCustomizeOptionFallback()) }
                val popup = OptionsPopupView
                    .show<LawnchairLauncher>(launcher, RectF(pos), options, true)
                return object : Popup {
                    override fun dismiss() {
                        popup.close(true)
                    }
                }
            }
        }
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        sdkViewPager = findViewById(SmartspacerR.id.smartspace_card_pager)
        sdkIndicator = findViewById(SmartspacerR.id.smartspace_page_indicator)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        receivedTargetsSinceAttach = false
        removeCallbacks(showFallback)
        if (canResolveSmartspacerService()) {
            postDelayed(showFallback, FALLBACK_TIMEOUT_MILLIS)
        } else {
            post(showFallback)
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(showFallback)
        removeLocalFallback()
        super.onDetachedFromWindow()
    }

    override fun onSmartspaceTargetsUpdate(targets: List<SmartspaceTarget>) {
        removeCallbacks(showFallback)
        super.onSmartspaceTargetsUpdate(targets)
        receivedTargetsSinceAttach = targets.isNotEmpty()
        if (targets.isEmpty()) {
            // An empty binder response is not a usable feed. Keep the built-in glanceable visible
            // until Smartspacer supplies real content instead of replacing it with a blank pager.
            post(showFallback)
        } else {
            removeLocalFallback()
        }
    }

    private fun canResolveSmartspacerService(): Boolean {
        val serviceIntent = Intent(SMARTSPACER_MANAGER_ACTION).setPackage(SMARTSPACER_PACKAGE)
        return runCatching {
            context.packageManager.resolveService(
                serviceIntent,
                PackageManager.ResolveInfoFlags.of(0),
            ) != null
        }.getOrDefault(false)
    }

    private fun showLocalFallback() {
        if (fallbackView != null) return

        // Keep the workspace useful when Smartspacer is missing, permission-gated, or its binder
        // does not answer. Inflating on demand avoids collecting the local provider while the
        // Smartspacer feed is healthy.
        val fallback = LayoutInflater.from(context)
            .inflate(R.layout.smartspace_enhanced, this, false)
            .also {
                it.layoutParams = LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
        sdkViewPager?.visibility = View.GONE
        sdkIndicator?.visibility = View.GONE
        fallbackView = fallback
        addView(fallback)
    }

    private fun removeLocalFallback() {
        fallbackView?.let(::removeView)
        fallbackView = null
        sdkViewPager?.visibility = View.VISIBLE
        sdkIndicator?.visibility = View.VISIBLE
    }

    private fun getDismissOption(
        target: SmartspaceTarget,
        dismissAction: ((SmartspaceTarget) -> Unit)?,
    ): OptionsPopupView.OptionItem? {
        if (dismissAction == null) return null
        return OptionsPopupView.OptionItem(
            context,
            SmartspacerR.string.smartspace_long_press_popup_dismiss,
            SmartspacerR.drawable.ic_smartspace_long_press_dismiss,
            StatsLogManager.LauncherEvent.IGNORE,
        ) {
            dismissAction.invoke(target)
            true
        }
    }

    private fun getAboutOption(
        launchIntent: (Intent?) -> Unit,
        aboutIntent: Intent?,
    ): OptionsPopupView.OptionItem? {
        if (aboutIntent == null) return null
        return OptionsPopupView.OptionItem(
            context,
            SmartspacerR.string.smartspace_long_press_popup_about,
            SmartspacerR.drawable.ic_smartspace_long_press_about,
            StatsLogManager.LauncherEvent.IGNORE,
        ) {
            launchIntent(aboutIntent)
            true
        }
    }

    private fun getFeedbackOption(
        launchIntent: (Intent?) -> Unit,
        feedbackIntent: Intent?,
    ): OptionsPopupView.OptionItem? {
        if (feedbackIntent == null) return null
        return OptionsPopupView.OptionItem(
            context,
            SmartspacerR.string.smartspace_long_press_popup_feedback,
            SmartspacerR.drawable.ic_smartspace_long_press_feedback,
            StatsLogManager.LauncherEvent.IGNORE,
        ) {
            launchIntent(feedbackIntent)
            true
        }
    }

    private fun getCustomizeOption(
        launchIntent: (Intent?) -> Unit,
        settingsIntent: Intent?,
    ): OptionsPopupView.OptionItem? {
        if (settingsIntent == null) return null
        return OptionsPopupView.OptionItem(
            context,
            R.string.action_customize,
            R.drawable.ic_setting,
            StatsLogManager.LauncherEvent.IGNORE,
        ) {
            launchIntent(settingsIntent)
            true
        }
    }

    private fun getCustomizeOptionFallback() = OptionsPopupView.OptionItem(
        context,
        R.string.action_customize,
        R.drawable.ic_setting,
        StatsLogManager.LauncherEvent.IGNORE,
    ) {
        context.startActivity(PreferenceActivity.createIntent(context, Smartspace))
        true
    }

    private companion object {
        const val SMARTSPACER_PACKAGE = "com.kieronquinn.app.smartspacer"
        const val SMARTSPACER_MANAGER_ACTION = "com.kieronquinn.app.smartspacer.MANAGER"
        const val FALLBACK_TIMEOUT_MILLIS = 5_000L
    }
}

internal fun sanitizeSmartspacerTargetCount(value: Int): Int = value.coerceIn(1, 20)
