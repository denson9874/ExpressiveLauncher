package app.lawnchair.qsb

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.children
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.lawnchair.LawnchairLauncher
import app.lawnchair.animateToAllApps
import app.lawnchair.launcher
import app.lawnchair.preferences.observeAsState
import app.lawnchair.preferences.preferenceManager
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.asState
import app.lawnchair.preferences2.firstCached
import app.lawnchair.qsb.providers.AppSearch
import app.lawnchair.qsb.providers.Google
import app.lawnchair.qsb.providers.PixelSearch
import app.lawnchair.qsb.providers.QsbSearchProvider
import app.lawnchair.ui.preferences.PreferenceActivity
import app.lawnchair.ui.preferences.navigation.Search
import app.lawnchair.ui.theme.LawnchairTheme
import app.lawnchair.util.ProvideLifecycleState
import app.lawnchair.util.repeatOnAttached
import com.android.launcher3.BaseActivity
import com.android.launcher3.DeviceProfile
import com.android.launcher3.R
import com.android.launcher3.logging.StatsLogManager
import com.android.launcher3.views.ActivityContext
import com.android.launcher3.views.OptionsPopupView
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

internal fun requiresAttachedQsbRemeasure(measuredWidth: Int, measuredHeight: Int): Boolean =
    measuredWidth <= 0 || measuredHeight <= 0

internal fun canCreateAttachedQsbComposition(
    isAttached: Boolean,
    lifecycleState: Lifecycle.State?,
): Boolean = isAttached && lifecycleState != null && lifecycleState != Lifecycle.State.DESTROYED

internal fun canMeasureAttachedQsbComposition(
    isAttached: Boolean,
    lifecycleState: Lifecycle.State?,
    hasSavedStateOwner: Boolean,
): Boolean =
    hasSavedStateOwner && canCreateAttachedQsbComposition(isAttached, lifecycleState)

/**
 * Mirrors Compose 1.12's content-child lookup. Window recomposers are stored on this view, which is
 * the direct child of android.R.id.content (or the window root when no content container exists).
 */
internal fun findQsbWindowContentChild(view: View): View {
    var contentChild = view
    var parent = contentChild.parent
    while (parent is View) {
        if (parent.id == android.R.id.content) return contentChild
        contentChild = parent
        parent = contentChild.parent
    }
    return contentChild
}

/**
 * Makes the real QSB host owner visible from the exact node where Compose creates its recomposer.
 *
 * LauncherPreviewRenderer and taskbar contexts can put their owners on an inner root. The QSB can
 * therefore find a valid owner while Compose's window content child cannot, which caused the
 * release crash. Existing live window ownership always wins; only a missing or destroyed owner is
 * repaired from the QSB's actual ActivityContext/BaseContext tree.
 */
internal fun prepareQsbWindowOwners(view: View): Boolean {
    val hostLifecycleOwner = view.findViewTreeLifecycleOwner() ?: return false
    if (hostLifecycleOwner.lifecycle.currentState == Lifecycle.State.DESTROYED) return false
    val hostSavedStateOwner = view.findViewTreeSavedStateRegistryOwner() ?: return false
    val contentChild = findQsbWindowContentChild(view)

    val windowLifecycleOwner = contentChild.findViewTreeLifecycleOwner()
    val replaceWindowOwner =
        windowLifecycleOwner == null ||
            windowLifecycleOwner.lifecycle.currentState == Lifecycle.State.DESTROYED
    if (replaceWindowOwner) {
        contentChild.setViewTreeLifecycleOwner(hostLifecycleOwner)
        contentChild.setViewTreeSavedStateRegistryOwner(hostSavedStateOwner)
    } else if (contentChild.findViewTreeSavedStateRegistryOwner() == null) {
        contentChild.setViewTreeSavedStateRegistryOwner(hostSavedStateOwner)
    }

    val preparedLifecycleState =
        contentChild.findViewTreeLifecycleOwner()?.lifecycle?.currentState
    return preparedLifecycleState != null &&
        preparedLifecycleState != Lifecycle.State.DESTROYED &&
        contentChild.findViewTreeSavedStateRegistryOwner() != null
}

/**
 * A Compose host that never creates its composition merely because it was attached.
 *
 * Launcher can attach the hotseat to Activity, taskbar, and preview windows before those hosts
 * publish their view-tree owners. ComposeView normally creates a window recomposer from
 * onAttachedToWindow(), which makes that short ordering gap fatal. LawnQsbLayout explicitly creates
 * this view's composition only after the real host owners are available.
 */
private class OwnerDeferredComposeView(context: Context) : AbstractComposeView(context) {

    private var deferredContent: (@Composable () -> Unit)? = null

    override val shouldCreateCompositionOnAttachedToWindow: Boolean
        get() = false

    fun setDeferredContent(content: @Composable () -> Unit) {
        deferredContent = content
    }

    @Composable
    override fun Content() {
        deferredContent?.invoke()
    }
}

class LawnQsbLayout(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {

    private val activity: ActivityContext = ActivityContext.lookupContext<BaseActivity>(context)
    private val composeView = OwnerDeferredComposeView(context)
    private lateinit var preferenceManager2: PreferenceManager2

    private lateinit var searchProvider: QsbSearchProvider

    /**
     * Recreates the composition after the final view-tree attachment.
     *
     * A clean install performs an extra workspace bind while Launcher is attaching its first
     * hotseat. The early composition can then remain associated with the provisional tree: the
     * ComposeView has valid bounds, but no emitted layout nodes, so the QSB is transparent until a
     * later process recreation. Recreating it from the attached tree gives Compose the live
     * LifecycleOwner and WindowRecomposer on the first launch itself.
     */
    private val createCompositionAfterAttach = ViewTreeObserver.OnPreDrawListener {
        val lifecycleState = composeView.findViewTreeLifecycleOwner()?.lifecycle?.currentState
        val canCreate =
            canMeasureAttachedQsbComposition(
                isAttached = composeView.isAttachedToWindow,
                lifecycleState = lifecycleState,
                hasSavedStateOwner = composeView.findViewTreeSavedStateRegistryOwner() != null,
            ) && prepareQsbWindowOwners(composeView)
        if (!canCreate) {
            // Keep listening: Activity post-create and BaseContext window attachment can publish
            // the owners after this view's first measure request.
            if (!composeView.isAttachedToWindow) removeCompositionPreDrawListener()
            return@OnPreDrawListener true
        }

        removeCompositionPreDrawListener()
        composeView.disposeComposition()
        composeView.createComposition()
        composeView.requestLayout()
        requestLayout()
        invalidate()
        true
    }

    private fun scheduleCompositionAfterAttach() {
        removeCompositionPreDrawListener()
        composeView.viewTreeObserver.addOnPreDrawListener(createCompositionAfterAttach)
        composeView.postInvalidateOnAnimation()
    }

    private fun removeCompositionPreDrawListener() {
        val observer = composeView.viewTreeObserver
        if (observer.isAlive) {
            observer.removeOnPreDrawListener(createCompositionAfterAttach)
        }
    }

    private fun canMeasureComposeChild(): Boolean {
        val lifecycleState = composeView.findViewTreeLifecycleOwner()?.lifecycle?.currentState
        return canMeasureAttachedQsbComposition(
            isAttached = composeView.isAttachedToWindow,
            lifecycleState = lifecycleState,
            hasSavedStateOwner = composeView.findViewTreeSavedStateRegistryOwner() != null,
        ) && prepareQsbWindowOwners(composeView)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onFinishInflate() {
        super.onFinishInflate()

        preferenceManager2 = PreferenceManager2.getInstance(context)
        searchProvider = getSearchProvider(context, preferenceManager2)

        composeView.apply {
            // Launcher can briefly reattach a retained hotseat while the previous Activity
            // lifecycle is already DESTROYED (for example after package data is cleared or the
            // process is recreated). DisposeOnViewTreeLifecycleDestroyed throws during that
            // attach. The guarded pre-draw/measure path recreates the composition only after the
            // new host is ready and gives the child first-frame bounds instead of waiting for a
            // trip through Settings.
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setDeferredContent {
                LawnchairTheme {
                    ProvideLifecycleState {
                        val context = LocalContext.current

                        val prefs = preferenceManager()
                        val prefs2 = preferenceManager2

                        val searchProviderPref by prefs2.hotseatQsbProvider.asState()
                        val searchProvider = remember(searchProviderPref, context) {
                            getSearchProvider(context, searchProviderPref)
                        }
                        val themed by prefs2.themedHotseatQsb.asState()

                        val supportsLens = searchProvider == Google || searchProvider == PixelSearch
                        val voiceIntent = remember(searchProvider, context) {
                            getVoiceIntent(searchProvider, context)
                        }
                        val lensIntent = remember(supportsLens, context) {
                            if (supportsLens) getLensIntent(context) else null
                        }

                        val state = rememberHotseatQsbState(
                            searchProvider = searchProvider,
                            themed = themed,
                            showMic = voiceIntent != null,
                            showLens = lensIntent != null,
                        )

                        val style = buildQsbStyle(
                            context = LocalContext.current,
                            themed = themed,
                            backgroundColor = getHotseatBackgroundColor(context, themed),
                            backgroundAlpha = prefs.hotseatQsbAlpha.observeAsState().value,
                            cornerRadius = prefs.hotseatQsbCornerRadius.observeAsState().value,
                            // Use light color as strokeColor is a static color that doesn't use darkColor
                            strokeColor = prefs2.strokeColorStyle.asState().value.colorPreferenceEntry.lightColor.invoke(context),
                            strokeWidth = prefs.hotseatQsbStrokeWidth.observeAsState().value,
                        )

                        val actions = QsbActions(
                            onQsbClick = {
                                val launcher = context.launcher
                                launcher.lifecycleScope.launch {
                                    if (prefs2.matchHotseatQsbStyle.firstCached()) {
                                        val searchUiManager = launcher.appsView.searchUiManager
                                        searchUiManager.setDirectFocus(true)
                                        searchUiManager.editText?.showKeyboard()
                                        launcher.animateToAllApps()
                                    } else {
                                        searchProvider.launch(launcher)
                                    }
                                }
                            },
                            onQsbLongClick = ::openOptions,
                            onStartIconClick = null,
                            onEndIconClick = { id ->
                                runCatching {
                                    when (id) {
                                        QsbIconId.MIC -> voiceIntent?.let { context.startActivity(it) }
                                        QsbIconId.LENS -> lensIntent?.let { context.startActivity(it) }
                                        else -> null
                                    }
                                }
                            },
                        )

                        LawnQsbUi(
                            state = state,
                            style = style,
                            actions = actions,
                        )
                    }
                }
            }
        }

        addView(
            composeView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )

        if (searchProvider == Google) {
            repeatOnAttached {
                val forceWebsite = preferenceManager2.hotseatQsbForceWebsite.get()
                forceWebsite
                    .flatMapLatest {
                        if (it) Google.getSearchIntent(context) else flowOf(null)
                    }
                    .collect()
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        // onMeasure deliberately avoids composing while detached because preview contexts do not
        // own a normal Activity lifecycle. If Launcher measured the hotseat during its cold-start
        // bind, force one attached pass now so the Compose child cannot remain at 0 x 0 until the
        // launcher is resumed from another Activity.
        if (requiresAttachedQsbRemeasure(composeView.measuredWidth, composeView.measuredHeight)) {
            composeView.requestLayout()
            requestLayout()
        }

        // Retry at pre-draw until the actual Activity, taskbar, or preview host publishes both
        // owners. A one-shot post can still run before Activity.onPostCreated on a cold launch.
        scheduleCompositionAfterAttach()
    }

    override fun onDetachedFromWindow() {
        removeCompositionPreDrawListener()
        super.onDetachedFromWindow()
    }

    private fun openOptions() {
        val launcher = context.launcher
        val pos = Rect()
        launcher.dragLayer.getDescendantRectRelativeToSelf(composeView, pos)
        OptionsPopupView.show<LawnchairLauncher>(launcher, RectF(pos), listOf(getCustomizeOption()), true)
    }

    private fun getCustomizeOption() = OptionsPopupView.OptionItem(
        context,
        R.string.action_customize,
        R.drawable.ic_setting,
        StatsLogManager.LauncherEvent.IGNORE,
    ) {
        context.startActivity(PreferenceActivity.createIntent(context, Search()))
        true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val dp = activity.deviceProfile
        // Unlike Phone, for Foldable/Tablet we let the original onMeasure do that instead since it
        // matched what we need. It perfectly fit the QSB with the grid.
        if (!dp.deviceProperties.isPhone) {
            if (!canMeasureComposeChild()) {
                // Do not invoke AbstractComposeView.onMeasure while its window has no owners;
                // Compose would try to create a window recomposer and throw immediately.
                setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
                return
            }

            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        val requestedWidth = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val cellWidth = DeviceProfile.calculateCellWidth(
            requestedWidth,
            dp.cellLayoutBorderSpacePx.x,
            dp.numShownHotseatIcons,
        )
        val iconSize = (dp.iconSizePx * 0.92f).toInt()
        val widthReduction = cellWidth - iconSize
        val width = requestedWidth - widthReduction
        setMeasuredDimension(width, height)

        if (!canMeasureComposeChild()) {
            // The outer hotseat retains stable bounds while the pre-draw retry waits for the real
            // host lifecycle. This also keeps detached launcher previews safe.
            return
        }

        children.forEach { child ->
            measureChildWithMargins(child, widthMeasureSpec, widthReduction, heightMeasureSpec, 0)
        }
    }

    companion object {
        private const val LENS_PACKAGE = "com.google.ar.lens"
        private const val LENS_ACTIVITY = "com.google.vr.apps.ornament.app.lens.LensLauncherActivity"

        fun getVoiceIntent(
            provider: QsbSearchProvider,
            context: Context,
        ): Intent? {
            val intent = if (provider.supportVoiceIntent) provider.createVoiceIntent() else null

            return if (intent == null || !resolveIntent(context, intent)) {
                null
            } else {
                intent
            }
        }

        fun getLensIntent(context: Context): Intent? {
            val lensIntent = Intent.makeMainActivity(ComponentName(LENS_PACKAGE, LENS_ACTIVITY))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            if (context.packageManager.resolveActivity(lensIntent, 0) == null) return null

            return lensIntent
        }

        fun getSearchProvider(
            context: Context,
            provider: QsbSearchProvider,
        ): QsbSearchProvider {
            return if (provider == AppSearch ||
                resolveIntent(context, provider.createSearchIntent()) ||
                resolveIntent(context, provider.createWebsiteIntent())
            ) {
                provider
            } else {
                AppSearch
            }
        }

        fun getSearchProvider(
            context: Context,
            preferenceManager: PreferenceManager2,
        ): QsbSearchProvider {
            return getSearchProvider(context, preferenceManager.hotseatQsbProvider.firstCached())
        }

        fun resolveIntent(context: Context, intent: Intent): Boolean = context.packageManager.resolveActivity(intent, 0) != null
    }
}
