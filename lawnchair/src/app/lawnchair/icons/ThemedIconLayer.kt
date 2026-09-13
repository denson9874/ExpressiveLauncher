package app.lawnchair.icons

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import app.lawnchair.util.Constants.LAWNICONS_PACKAGE_NAME
import com.android.launcher3.icons.BaseIconFactory
import com.android.launcher3.icons.BitmapInfo
import com.android.launcher3.icons.SourceHint

/** Keep the ordinary artwork intact until the icon factory has normalized and cached it. */
internal class ThemedIconLayer(
    val baseIcon: Drawable,
    val monochrome: Drawable,
) : DrawableWrapper(baseIcon) {
    override fun getConstantState(): ConstantState? {
        val baseState = baseIcon.constantState ?: return null
        val monoState = monochrome.constantState ?: return null
        val iconPackMarker = (baseIcon as? ExtendedBitmapDrawable)?.isFromIconPack
        return object : ConstantState() {
            override fun newDrawable(): Drawable = ThemedIconLayer(copyBase(null), monoState.newDrawable())

            override fun newDrawable(resources: Resources?): Drawable =
                ThemedIconLayer(copyBase(resources), monoState.newDrawable(resources))

            private fun copyBase(resources: Resources?): Drawable {
                val copy = if (resources == null) baseState.newDrawable() else baseState.newDrawable(resources)
                if (iconPackMarker == null || copy !is BitmapDrawable) return copy
                // BitmapDrawable's inherited state does not know ExtendedBitmapDrawable's marker.
                // Restore it so copied icon-pack PNGs keep the same normalization as their source.
                return ExtendedBitmapDrawable(resources ?: Resources.getSystem(), copy.bitmap, iconPackMarker).apply {
                    val bitmapDensity = copy.bitmap.density
                    if (bitmapDensity != Bitmap.DENSITY_NONE && copy.bitmap.width > 0) {
                        val density = (copy.intrinsicWidth.toLong() * bitmapDensity + copy.bitmap.width / 2) /
                            copy.bitmap.width
                        setTargetDensity(density.toInt())
                    }
                    paint.set(copy.paint)
                    gravity = copy.gravity
                    setTileModeXY(copy.tileModeX, copy.tileModeY)
                    isAutoMirrored = copy.isAutoMirrored
                }
            }

            override fun getChangingConfigurations(): Int =
                baseState.changingConfigurations or monoState.changingConfigurations
        }
    }
}

internal fun BaseIconFactory.createBitmapWithThemedLayer(
    icon: Drawable,
    options: BaseIconFactory.IconOptions?,
    sourceHint: SourceHint? = null,
    createBase: (Drawable, BaseIconFactory.IconOptions?) -> BitmapInfo,
): BitmapInfo {
    val layered = icon as? ThemedIconLayer ?: return createBase(icon, options)
    val result = createBase(layered.baseIcon, options)
    val monochrome = layered.monochrome.constantState?.newDrawable()?.mutate() ?: layered.monochrome
    val template = AdaptiveIconDrawable(null, null, monochrome).apply {
        setBounds(0, 0, result.icon.width, result.icon.height)
    }
    themeController?.createThemedBitmap(
        template,
        result,
        this,
        sourceHint,
    )?.let { result.themedBitmap = it }
    return result
}

/** Lawnicons contains monochrome art; ordinary colorful packs keep their unthemed artwork. */
internal fun unthemedIconPackPackage(packageName: String): String =
    packageName.takeUnless { it == LAWNICONS_PACKAGE_NAME }.orEmpty()

internal fun themedIconSourcePackage(normalPack: String, themedPack: String): String =
    themedPack.ifEmpty { normalPack.takeIf { it == LAWNICONS_PACKAGE_NAME }.orEmpty() }

/** Disable/re-enable and source changes must not reuse a previous source's monochrome map. */
internal class ThemedIconMapCache<T> {
    private var source: String? = null
    private var entries: Map<String, T>? = null

    @Synchronized
    fun get(enabled: Boolean, source: String, load: (String) -> Map<String, T>): Map<String, T> {
        if (!enabled) {
            clear()
            return emptyMap()
        }
        if (this.source != source || entries == null) {
            this.source = source
            entries = load(source)
        }
        return checkNotNull(entries)
    }

    @Synchronized
    fun clear() {
        source = null
        entries = null
    }
}
