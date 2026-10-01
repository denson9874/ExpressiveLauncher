package app.lawnchair.folder

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LargeFolderAnimationGeometryTest {
    private val tile = Box(100f, 200f, 400f)

    @Test
    fun slotBoxesAreCenteredInQuadrants() {
        val s0 = LargeFolderAnimationGeometry.slotBox(tile, 0, 0.5f)
        assertThat(s0).isEqualTo(Box(150f, 250f, 100f))
        val s3 = LargeFolderAnimationGeometry.slotBox(tile, 3, 0.5f)
        assertThat(s3).isEqualTo(Box(350f, 450f, 100f))
    }

    @Test
    fun boxForRankMatchesTileDrawing() {
        // 4 apps: all direct.
        assertThat(LargeFolderAnimationGeometry.boxForRank(tile, 3, 4)).isEqualTo(LargeFolderAnimationGeometry.slotBox(tile, 3))
        // 6 apps: ranks 0-2 direct, 3-6 in the "more" slot, 7+ not drawn.
        assertThat(LargeFolderAnimationGeometry.boxForRank(tile, 2, 6)).isEqualTo(LargeFolderAnimationGeometry.slotBox(tile, 2))
        assertThat(LargeFolderAnimationGeometry.boxForRank(tile, 3, 6)).isEqualTo(LargeFolderAnimationGeometry.miniBox(tile, 0))
        assertThat(LargeFolderAnimationGeometry.boxForRank(tile, 7, 9)).isNull()
    }

    @Test
    fun cornerRadiusMatchesTile() {
        assertThat(LargeFolderAnimationGeometry.cornerRadius(tile)).isWithin(0.01f).of(400f * LargeFolderTile.CORNER_FRACTION)
    }

    @Test
    fun fractionClampsToZeroAndOne() {
        assertThat(LargeFolderAnimationGeometry.fraction(-0.2f)).isEqualTo(0f)
        assertThat(LargeFolderAnimationGeometry.fraction(1.3f)).isEqualTo(1f)
        assertThat(LargeFolderAnimationGeometry.lerp(Box(0f, 0f, 10f), Box(10f, 20f, 30f), 0.5f)).isEqualTo(Box(5f, 10f, 20f))
    }
}
