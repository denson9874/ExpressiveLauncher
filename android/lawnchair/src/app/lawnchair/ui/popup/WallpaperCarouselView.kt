package app.lawnchair.ui.popup

import android.animation.ValueAnimator
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.cardview.widget.CardView
import androidx.core.graphics.ColorUtils
import app.lawnchair.data.wallpaper.Wallpaper
import app.lawnchair.data.wallpaper.service.WallpaperService
import app.lawnchair.launcher
import app.lawnchair.util.decodeSampledBitmapFromFile
import app.lawnchair.views.component.IconFrame
import com.android.launcher3.R
import com.android.launcher3.util.PackageManagerHelper
import com.android.launcher3.util.Themes
import java.io.File
import java.io.FileInputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Pixel-style wallpaper previews shown above the launcher's long-press options.
 *
 * Android 14 and newer no longer let ordinary Play Store apps read the current static wallpaper
 * bitmap. The first two cards therefore use the public wallpaper color API (or a real live-
 * wallpaper thumbnail when available). Wallpapers previously captured with legitimate access are
 * still shown after those cards. This keeps the picker useful without requesting broad file access.
 */
class WallpaperCarouselView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val launcher = context.launcher
    private val deviceProfile = launcher.deviceProfile
    private val wallpaperManager = WallpaperManager.getInstance(context)
    private val wallpaperService = WallpaperService.INSTANCE.get(context)
    private var viewScope = createViewScope()
    private var renderJob: Job? = null
    private var renderScope = createRenderScope()

    private var currentItemIndex = 0
    private var latestSavedWallpapers = emptyList<Wallpaper>()
    private var displayedItems = emptyList<CarouselItem>()
    private var renderPosted = false
    private val renderWallpapersRunnable = Runnable {
        renderPosted = false
        if (isAttachedToWindow && width > 0 && height > 0) {
            displayWallpapers(latestSavedWallpapers)
        }
    }

    private val iconFrame = IconFrame(context).apply {
        setIcon(R.drawable.ic_tick)
        setBackgroundWithRadius(Themes.getColorAccent(context), 100F)
    }

    init {
        orientation = HORIZONTAL
        observeWallpapers()
    }

    private fun observeWallpapers() {
        // StateFlow immediately emits an empty list, so clean installs get public-API previews
        // without waiting for Room. Later database updates are collected continuously.
        viewScope.launch {
            wallpaperService.topWallpapers.collectLatest { wallpapers ->
                latestSavedWallpapers = wallpapers
                scheduleWallpaperRender()
            }
        }
    }

    private fun scheduleWallpaperRender() {
        if (renderPosted) return
        renderPosted = true
        // The popup is inflated before it has a measured size. Rendering cards immediately gave
        // them zero bounds on the real release window even though the carousel shell was visible.
        // Deferring one frame lets us use the popup's actual constrained width and height.
        post(renderWallpapersRunnable)
    }

    private fun displayWallpapers(savedWallpapers: List<Wallpaper>) {
        val items = buildList {
            add(CarouselItem.Current(WallpaperManager.FLAG_SYSTEM, R.string.wallpaper_carousel_home))
            add(CarouselItem.Current(WallpaperManager.FLAG_LOCK, R.string.wallpaper_carousel_lock))
            savedWallpapers.take(MAX_SAVED_PREVIEWS).forEach { add(CarouselItem.Saved(it)) }
        }
        displayedItems = items
        currentItemIndex = currentItemIndex.coerceIn(0, items.lastIndex)
        renderJob?.cancel()
        renderScope = createRenderScope()

        removeAllViews()
        val widths = calculateWallpaperCarouselWidths(calculateTotalWidth(), items.size)
        items.forEachIndexed { index, item ->
            val cardView = createCardView(index, item, widths)
            addView(cardView)
            when (item) {
                is CarouselItem.Current -> loadCurrentWallpaperPreview(item, cardView, index)
                is CarouselItem.Saved -> loadSavedWallpaperPreview(item.wallpaper, cardView, index)
            }
        }
    }

    private fun calculateTotalWidth(): Int {
        return width.takeIf { it > 0 }
            ?: measuredWidth.takeIf { it > 0 }
            ?: (
                deviceProfile.deviceProperties.widthPx *
                    if (deviceProfile.deviceProperties.isLandscape || deviceProfile.deviceProperties.isPhone) {
                        0.5
                    } else {
                        0.8
                    }
                ).toInt()
    }

    private fun createCardView(
        index: Int,
        item: CarouselItem,
        widths: WallpaperCarouselWidths,
    ): CardView {
        val label = when (item) {
            is CarouselItem.Current -> context.getString(item.labelResId)
            is CarouselItem.Saved -> context.getString(
                R.string.wallpaper_carousel_recent,
                index - CURRENT_PREVIEW_COUNT + 1,
            )
        }
        return CardView(context).apply {
            radius = Themes.getDialogCornerRadius(context) / 2
            contentDescription = label
            isClickable = true
            isFocusable = true
            updateAccessibilityState(
                cardView = this,
                selected = index == currentItemIndex,
                item = item,
            )
            val cardWidth = if (index == currentItemIndex) {
                widths.expandedWidth
            } else {
                widths.collapsedWidth
            }
            layoutParams = LinearLayout.LayoutParams(
                cardWidth,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ).apply {
                setMargins(if (index > 0) widths.margin else 0, 0, 0, 0)
            }
            // An explicit minimum height makes a transient opening-layout pass harmless while
            // still allowing the selected and collapsed widths to animate freely.
            minimumHeight = resources.getDimensionPixelSize(R.dimen.wallpaper_carousel_height)
            setOnClickListener {
                if (index != currentItemIndex) {
                    animateWidthTransition(index, widths)
                } else {
                    when (item) {
                        is CarouselItem.Current -> openWallpaperPicker()
                        is CarouselItem.Saved -> setWallpaper(item.wallpaper, this)
                    }
                }
            }
        }
    }

    private fun loadCurrentWallpaperPreview(
        item: CarouselItem.Current,
        cardView: CardView,
        index: Int,
    ) {
        val imageView = ImageView(context).apply {
            setImageDrawable(createFallbackPreview())
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        cardView.addView(
            imageView,
            FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        addPreviewLabel(cardView, item.labelResId)
        if (index == currentItemIndex) addIconFrameToCenter(cardView)

        // Both getWallpaperColors() and live-thumbnail loading may use IPC/package I/O. Android's
        // API documentation explicitly warns not to request wallpaper colors on the UI thread.
        renderScope.launch {
            val preview = withContext(Dispatchers.IO) {
                val liveThumbnail = runCatching {
                    wallpaperManager.getWallpaperInfo(item.which)
                        ?.loadThumbnail(context.packageManager)
                }.getOrNull()
                liveThumbnail ?: createColorPreview(item.which)
            }
            imageView.setImageDrawable(preview)
        }
    }

    private fun createFallbackPreview(): Drawable {
        val accent = Themes.getColorAccent(context)
        return createGradient(accent, ColorUtils.blendARGB(accent, Color.BLACK, 0.22f), Color.BLACK)
    }

    private fun createColorPreview(which: Int): Drawable {
        val colors = runCatching { wallpaperManager.getWallpaperColors(which) }.getOrNull()
            ?: if (which == WallpaperManager.FLAG_LOCK) {
                runCatching {
                    wallpaperManager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
                }.getOrNull()
            } else {
                null
            }
        val fallback = Themes.getColorAccent(context)
        val primary = colors?.primaryColor?.toArgb() ?: fallback
        val contrasting = if (ColorUtils.calculateLuminance(primary) > 0.45) Color.BLACK else Color.WHITE
        val secondary = colors?.secondaryColor?.toArgb()
            ?: ColorUtils.blendARGB(primary, contrasting, 0.22f)
        val tertiary = colors?.tertiaryColor?.toArgb()
            ?: ColorUtils.blendARGB(primary, contrasting, 0.38f)
        return createGradient(primary, secondary, tertiary)
    }

    private fun createGradient(primary: Int, secondary: Int, tertiary: Int): Drawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(primary, secondary, tertiary),
        )
    }

    private fun addPreviewLabel(cardView: CardView, @StringRes labelResId: Int) {
        val density = resources.displayMetrics.density
        val label = TextView(context).apply {
            setText(labelResId)
            setTextColor(Color.WHITE)
            textSize = 12f
            maxLines = 1
            setPadding(
                (8 * density).roundToInt(),
                (4 * density).roundToInt(),
                (8 * density).roundToInt(),
                (4 * density).roundToInt(),
            )
            background = GradientDrawable().apply {
                setColor(Color.argb(168, 0, 0, 0))
                cornerRadius = 12 * density
            }
        }
        cardView.addView(
            label,
            FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                val margin = (8 * density).roundToInt()
                setMargins(margin, margin, margin, margin)
            },
        )
    }

    private fun loadSavedWallpaperPreview(
        wallpaper: Wallpaper,
        cardView: CardView,
        index: Int,
    ) {
        val requestWidth = cardView.layoutParams?.width ?: 0
        val requestHeight = height.takeIf { it > 0 } ?: requestWidth
        val imageView = ImageView(context).apply {
            setImageDrawable(createFallbackPreview())
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0f
        }
        cardView.addView(
            imageView,
            FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        renderScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                decodeSampledWallpaper(wallpaper.imagePath, requestWidth, requestHeight)
            }
            if (bitmap != null) imageView.setImageBitmap(bitmap)
            imageView.animate().alpha(1f).setDuration(200L).withEndAction {
                if (index == currentItemIndex) addIconFrameToCenter(cardView)
            }.start()
        }
    }

    /** Decode only the small thumbnail size instead of a multi-megabyte source bitmap. */
    private fun decodeSampledWallpaper(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        val file = File(path).takeIf { it.exists() } ?: return null
        if (reqWidth <= 0 || reqHeight <= 0) return BitmapFactory.decodeFile(file.path)
        return decodeSampledBitmapFromFile(file.path, reqWidth, reqHeight)
    }

    private fun setWallpaper(wallpaper: Wallpaper, currentCardView: CardView) {
        val spinner = createLoadingSpinner()
        currentCardView.removeView(iconFrame)
        currentCardView.addView(spinner)

        viewScope.launch(Dispatchers.IO) {
            var succeeded = false
            try {
                val file = File(wallpaper.imagePath).takeIf { it.isFile }
                    ?: error("Wallpaper preview file is missing")
                FileInputStream(file).use { stream ->
                    // setStream preserves the source quality without allocating a full-resolution
                    // bitmap in the launcher process, which avoids OOMs on modern wallpapers.
                    wallpaperManager.setStream(
                        stream,
                        null,
                        true,
                        WallpaperManager.FLAG_SYSTEM,
                    )
                }
                wallpaperService.updateWallpaperRank(wallpaper)
                succeeded = true
            } catch (error: Exception) {
                Log.e(TAG, "Failed to set wallpaper", error)
            } finally {
                withContext(Dispatchers.Main) {
                    (spinner.parent as? ViewGroup)?.removeView(spinner)
                    if (succeeded) {
                        addIconFrameToCenter()
                    } else {
                        animateWidthTransition(
                            newIndex = 0,
                            widths = calculateWallpaperCarouselWidths(
                                calculateTotalWidth(),
                                displayedItems.size,
                            ),
                        )
                        Toast.makeText(
                            context,
                            R.string.wallpaper_carousel_apply_failed,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        }
    }

    private fun openWallpaperPicker() {
        val explicitPicker = PackageManagerHelper.getStyleWallpapersIntent(context)
        val fallbackPicker = Intent(Intent.ACTION_SET_WALLPAPER)
        val intent = listOf(explicitPicker, fallbackPicker).firstOrNull {
            it.resolveActivity(context.packageManager) != null
        }
        if (intent == null || runCatching { context.startActivity(intent) }.isFailure) {
            Toast.makeText(context, R.string.wallpaper_picker_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun createLoadingSpinner() = ProgressBar(context).apply {
        isIndeterminate = true
        layoutParams = FrameLayout.LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.CENTER
        }
    }

    private fun addIconFrameToCenter(cardView: CardView? = getChildAt(currentItemIndex) as? CardView) {
        (iconFrame.parent as? ViewGroup)?.removeView(iconFrame)
        cardView?.addView(
            iconFrame,
            FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            },
        )
    }

    private fun updateAccessibilityState(
        cardView: CardView,
        selected: Boolean,
        item: CarouselItem,
    ) {
        cardView.isSelected = selected
        cardView.stateDescription = context.getString(
            wallpaperCarouselStateDescriptionRes(
                selected = selected,
                appliesWallpaper = item is CarouselItem.Saved,
            ),
        )
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(renderWallpapersRunnable)
        renderPosted = false
        renderJob?.cancel()
        viewScope.cancel()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!viewScope.isActive) {
            viewScope = createViewScope()
            renderScope = createRenderScope()
            observeWallpapers()
        }
        scheduleWallpaperRender()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && (w != oldw || h != oldh)) {
            scheduleWallpaperRender()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = calculateTotalWidth()
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        val measuredWidth = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.EXACTLY -> availableWidth
            MeasureSpec.AT_MOST -> min(desiredWidth, availableWidth)
            else -> desiredWidth
        }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(max(1, measuredWidth), MeasureSpec.EXACTLY),
            heightMeasureSpec,
        )
    }

    private fun createViewScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun createRenderScope(): CoroutineScope {
        renderJob = SupervisorJob(viewScope.coroutineContext[Job])
        return CoroutineScope(renderJob!! + Dispatchers.Main.immediate)
    }

    private fun animateWidthTransition(
        newIndex: Int,
        widths: WallpaperCarouselWidths,
    ) {
        currentItemIndex = newIndex
        for (index in 0 until childCount) {
            (getChildAt(index) as? CardView)?.let { cardView ->
                displayedItems.getOrNull(index)?.let { item ->
                    updateAccessibilityState(
                        cardView = cardView,
                        selected = index == currentItemIndex,
                        item = item,
                    )
                }
                val targetWidth = if (index == currentItemIndex) {
                    widths.expandedWidth
                } else {
                    widths.collapsedWidth
                }
                if (cardView.layoutParams.width != targetWidth) {
                    ValueAnimator.ofInt(cardView.layoutParams.width, targetWidth).apply {
                        duration = WIDTH_ANIMATION_DURATION_MILLIS
                        addUpdateListener {
                            cardView.layoutParams.width = it.animatedValue as Int
                            cardView.requestLayout()
                        }
                        start()
                    }
                }
                if (index == currentItemIndex) addIconFrameToCenter(cardView)
            }
        }
    }

    private sealed interface CarouselItem {
        data class Current(
            val which: Int,
            @StringRes val labelResId: Int,
        ) : CarouselItem

        data class Saved(val wallpaper: Wallpaper) : CarouselItem
    }

    private companion object {
        const val TAG = "WallpaperCarouselView"
        const val CURRENT_PREVIEW_COUNT = 2
        const val MAX_SAVED_PREVIEWS = 3
        const val WIDTH_ANIMATION_DURATION_MILLIS = 300L
    }
}

internal data class WallpaperCarouselWidths(
    val expandedWidth: Int,
    val collapsedWidth: Int,
    val margin: Int,
)

/** Safe for 0/1-item inputs; the previous formula divided by itemCount - 1. */
internal fun calculateWallpaperCarouselWidths(
    totalWidth: Int,
    itemCount: Int,
): WallpaperCarouselWidths {
    val safeWidth = max(1, totalWidth)
    if (itemCount <= 1) {
        return WallpaperCarouselWidths(
            expandedWidth = safeWidth,
            collapsedWidth = safeWidth,
            margin = 0,
        )
    }
    val margin = (safeWidth * 0.03f).roundToInt()
    val desiredExpanded = (safeWidth * 0.45f).roundToInt()
    val remaining = safeWidth - desiredExpanded - margin * (itemCount - 1)
    val collapsed = max(1, remaining / (itemCount - 1))
    // Integer division can leave a few pixels behind. Give that remainder to the selected card so
    // the carousel always reaches the popup edge instead of showing a release-only-looking gap.
    val expanded = safeWidth - collapsed * (itemCount - 1) - margin * (itemCount - 1)
    return WallpaperCarouselWidths(
        expandedWidth = max(1, expanded),
        collapsedWidth = collapsed,
        margin = margin,
    )
}

@StringRes
internal fun wallpaperCarouselStateDescriptionRes(
    selected: Boolean,
    appliesWallpaper: Boolean,
): Int = when {
    !selected -> R.string.wallpaper_carousel_state_collapsed
    appliesWallpaper -> R.string.wallpaper_carousel_state_selected_apply
    else -> R.string.wallpaper_carousel_state_selected_open
}
