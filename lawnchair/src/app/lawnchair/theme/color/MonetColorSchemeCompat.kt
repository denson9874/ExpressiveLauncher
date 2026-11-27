package app.lawnchair.theme.color

import androidx.annotation.ColorInt
import com.android.systemui.monet.ColorScheme as MonetColorScheme
import com.android.systemui.monet.Style
import dev.kdrag0n.colorkt.Color
import dev.kdrag0n.monet.theme.ColorScheme
import dev.kdrag0n.monet.theme.ColorSwatch

class MonetColorSchemeCompat(
    @ColorInt private val seedColor: Int,
    val style: Style = Style.TONAL_SPOT,
) : ColorScheme() {

    override val neutral1: ColorSwatch
    override val neutral2: ColorSwatch
    override val accent1: ColorSwatch
    override val accent2: ColorSwatch
    override val accent3: ColorSwatch

    init {
        val scheme = MonetColorScheme(
            seedColor,
            style,
        )
        neutral1 = mapColors(scheme.neutral1.allShades)
        neutral2 = mapColors(scheme.neutral2.allShades)
        accent1 = mapColors(scheme.accent1.allShades)
        accent2 = mapColors(scheme.accent2.allShades)
        accent3 = mapColors(scheme.accent3.allShades)
    }

    private fun mapColors(colors: List<Int>): Map<Int, Color> {
        val paletteSize = colors.size
        val colorMap = mutableMapOf<Int, Color>()
        colors.forEachIndexed { index, color ->
            val brightness = when (val luminosity = index % paletteSize) {
                0 -> 10
                1 -> 50
                else -> (luminosity - 1) * 100
            }
            colorMap[brightness] = AndroidColor(color)
        }
        colorMap[0] = AndroidColor(android.graphics.Color.WHITE)
        return colorMap
    }
}
