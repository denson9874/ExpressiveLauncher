package app.lawnchair.icons

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.DisplayMetrics
import androidx.test.core.app.ApplicationProvider
import com.android.launcher3.icons.BaseIconFactory
import com.android.launcher3.icons.BitmapInfo
import com.android.launcher3.icons.mono.MonoIconThemeController
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemedIconLayerTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun resetIconPreferences() {
        context.getSharedPreferences("com.android.launcher3.prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun adaptiveArtwork_staysFullColorWhenThemedFlagIsAbsent() {
        assertSeparateRenderedLayers(::adaptiveArtwork)
    }

    @Test
    fun legacyBitmapArtwork_staysFullColorWhenThemedFlagIsAbsent() {
        assertSeparateRenderedLayers(::legacyArtwork)
    }

    @Test
    fun disabledThemeController_preservesArtworkAndDoesNotCreateThemedBitmap() {
        for (artwork in listOf(::adaptiveArtwork, ::legacyArtwork)) {
            RenderingIconFactory(context, themed = false).use { factory ->
                val original = factory.createBadgedIconBitmap(artwork(), options())
                val layered = factory.createBadgedIconBitmap(
                    ThemedIconLayer(artwork(), ColorDrawable(Color.WHITE)),
                    options(),
                )

                assertThat(layered.themedBitmap).isNull()
                assertThat(layered.icon.sameAs(original.icon)).isTrue()
                val requestedTheme = layered.newIcon(context, BitmapInfo.FLAG_THEMED)
                assertThat(requestedTheme.isThemed()).isFalse()
                assertThat(render(requestedTheme).sameAs(render(original.newIcon(context)))).isTrue()
            }
        }
    }

    @Test
    fun drawableStateCopy_preservesTheMonochromeCarrierAndFullColorBase() {
        val original = ThemedIconLayer(adaptiveArtwork(), ColorDrawable(Color.WHITE))
        val state = checkNotNull(original.constantState)
        for (copy in listOf(state.newDrawable(), state.newDrawable(context.resources))) {
            assertThat(copy).isInstanceOf(ThemedIconLayer::class.java)
            val layeredCopy = copy as ThemedIconLayer
            assertThat(layeredCopy).isNotSameInstanceAs(original)
            assertThat(layeredCopy.baseIcon).isNotSameInstanceAs(original.baseIcon)
            assertThat(layeredCopy.monochrome).isNotSameInstanceAs(original.monochrome)
            RenderingIconFactory(context, themed = true).use { factory ->
                val copiedInfo = factory.createBadgedIconBitmap(layeredCopy, options())
                assertThat(copiedInfo.themedBitmap).isNotNull()
                assertThat(render(copiedInfo.newIcon(context)).getPixel(SIZE / 2, SIZE / 2))
                    .isEqualTo(Color.RED)
                assertThat(
                    render(copiedInfo.newIcon(context, BitmapInfo.FLAG_THEMED))
                        .getPixel(SIZE / 2, SIZE / 2),
                ).isEqualTo(Color.CYAN)
            }
        }
    }

    @Test
    fun iconPackBitmapStateCopy_preservesPackNormalizationWithAndWithoutResources() {
        val bitmap = (legacyArtwork() as BitmapDrawable).bitmap
        val packIcon = ExtendedBitmapDrawable(context.resources, bitmap, true)
        val original = ThemedIconLayer(packIcon, ColorDrawable(Color.WHITE))
        val state = checkNotNull(original.constantState)
        RenderingIconFactory(context, themed = true).use { factory ->
            val originalInfo = factory.createBadgedIconBitmap(original, options())
            for (copy in listOf(state.newDrawable(), state.newDrawable(context.resources))) {
                val layeredCopy = copy as ThemedIconLayer
                assertThat(layeredCopy.baseIcon).isInstanceOf(ExtendedBitmapDrawable::class.java)
                assertThat((layeredCopy.baseIcon as ExtendedBitmapDrawable).isFromIconPack).isTrue()
                assertThat(layeredCopy.baseIcon).isNotSameInstanceAs(packIcon)
                assertThat(layeredCopy.baseIcon.intrinsicWidth).isEqualTo(packIcon.intrinsicWidth)
                assertThat(layeredCopy.baseIcon.intrinsicHeight).isEqualTo(packIcon.intrinsicHeight)
                val copiedInfo = factory.createBadgedIconBitmap(layeredCopy, options())

                assertThat(copiedInfo.icon.sameAs(originalInfo.icon)).isTrue()
                assertThat(render(copiedInfo.newIcon(context)).sameAs(render(originalInfo.newIcon(context))))
                    .isTrue()
                assertThat(render(copiedInfo.newIcon(context, BitmapInfo.FLAG_THEMED)).getPixel(SIZE / 2, SIZE / 2))
                    .isEqualTo(Color.CYAN)
            }
        }
    }

    @Test
    fun ordinaryIconWithoutAdditionalLayer_keepsFactoryMonochromeSupport() {
        RenderingIconFactory(context, themed = true).use { factory ->
            val icon = AdaptiveIconDrawable(
                ColorDrawable(Color.BLUE),
                ColorDrawable(Color.RED),
                ColorDrawable(Color.WHITE),
            )
            val info = factory.createBadgedIconBitmap(icon, options())

            assertThat(info.themedBitmap).isNotNull()
            assertThat(render(info.newIcon(context)).getPixel(SIZE / 2, SIZE / 2))
                .isEqualTo(Color.RED)
            assertThat(render(info.newIcon(context, BitmapInfo.FLAG_THEMED)).getPixel(SIZE / 2, SIZE / 2))
                .isEqualTo(Color.CYAN)
        }
    }

    private fun assertSeparateRenderedLayers(artwork: () -> Drawable) {
        RenderingIconFactory(context, themed = true).use { factory ->
            val original = factory.createBadgedIconBitmap(artwork(), options())
            val layered = factory.createBadgedIconBitmap(
                ThemedIconLayer(artwork(), ColorDrawable(Color.WHITE)),
                options(),
            )
            val ordinary = layered.newIcon(context, 0)
            val before = render(ordinary)
            val themed = layered.newIcon(context, BitmapInfo.FLAG_THEMED)
            val themedPixels = render(themed)
            val after = render(layered.newIcon(context, 0))

            assertThat(layered.themedBitmap).isNotNull()
            assertThat(ordinary.isThemed()).isFalse()
            assertThat(themed.isThemed()).isTrue()
            assertThat(ordinary.creationFlags and BitmapInfo.FLAG_THEMED).isEqualTo(0)
            assertThat(themed.creationFlags and BitmapInfo.FLAG_THEMED).isNotEqualTo(0)
            assertThat(layered.icon.sameAs(original.icon)).isTrue()
            assertThat(before.sameAs(render(original.newIcon(context)))).isTrue()
            assertThat(before.sameAs(after)).isTrue()
            assertThat(before.sameAs(themedPixels)).isFalse()
            assertThat(themedPixels.getPixel(SIZE / 2, SIZE / 2)).isEqualTo(Color.CYAN)
            assertThat(before.getPixel(SIZE / 2, SIZE / 2)).isNotEqualTo(Color.CYAN)
        }
    }

    private fun adaptiveArtwork(): Drawable = AdaptiveIconDrawable(
        ColorDrawable(Color.BLUE),
        ColorDrawable(Color.RED),
    )

    private fun legacyArtwork(): Drawable {
        val pixels = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        Canvas(pixels).apply {
            drawColor(Color.RED)
            drawRect(0f, 0f, SIZE / 2f, SIZE.toFloat(), Paint().apply { color = Color.BLUE })
        }
        return BitmapDrawable(context.resources, pixels)
    }

    private fun options() = BaseIconFactory.IconOptions()
        .setBitmapGenerationMode(BaseIconFactory.MODE_DEFAULT)

    private fun render(drawable: Drawable): Bitmap =
        Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).also {
            drawable.setBounds(0, 0, SIZE, SIZE)
            drawable.draw(Canvas(it))
        }

    private class RenderingIconFactory(context: Context, themed: Boolean) :
        BaseIconFactory(context, DisplayMetrics.DENSITY_MEDIUM, SIZE) {
        init {
            if (themed) {
                mThemeController = MonoIconThemeController(
                    colorProvider = { intArrayOf(Color.DKGRAY, Color.CYAN) },
                )
            }
        }

        override fun createBadgedIconBitmap(icon: Drawable, options: IconOptions?): BitmapInfo =
            createBitmapWithThemedLayer(icon, options) { base, baseOptions ->
                super.createBadgedIconBitmap(base, baseOptions)
            }

        // Keep the same mask geometry while allowing the rendered drawable on a software Canvas.
        override fun getWhiteShadowLayer(): Bitmap = createScaledBitmap(
            AdaptiveIconDrawable(ColorDrawable(Color.WHITE), null),
            MODE_DEFAULT,
        )
    }

    companion object {
        private const val SIZE = 64
    }
}
