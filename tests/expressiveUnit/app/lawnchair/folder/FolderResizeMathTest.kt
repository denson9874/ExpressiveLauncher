package app.lawnchair.folder

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FolderResizeMathTest {

    @Test
    fun growDirectionFollowsHandle() {
        val one = CellRect(1, 1, 1, 1)
        assertThat(FolderResizeMath.targetFor(one, ResizeHandle.RIGHT)).isEqualTo(CellRect(1, 1, 2, 2))
        assertThat(FolderResizeMath.targetFor(one, ResizeHandle.BOTTOM)).isEqualTo(CellRect(1, 1, 2, 2))
        assertThat(FolderResizeMath.targetFor(one, ResizeHandle.LEFT)).isEqualTo(CellRect(0, 1, 2, 2))
        assertThat(FolderResizeMath.targetFor(one, ResizeHandle.TOP)).isEqualTo(CellRect(1, 0, 2, 2))
    }

    @Test
    fun shrinkKeepsCellOnPulledSide() {
        val two = CellRect(1, 1, 2, 2)
        // Pulling the right handle inward shrinks toward the left: top-left cell kept.
        assertThat(FolderResizeMath.targetFor(two, ResizeHandle.RIGHT)).isEqualTo(CellRect(1, 1, 1, 1))
        // Pulling the left handle inward keeps the right column.
        assertThat(FolderResizeMath.targetFor(two, ResizeHandle.LEFT)).isEqualTo(CellRect(2, 1, 1, 1))
        assertThat(FolderResizeMath.targetFor(two, ResizeHandle.TOP)).isEqualTo(CellRect(1, 2, 1, 1))
        assertThat(FolderResizeMath.targetFor(two, ResizeHandle.BOTTOM)).isEqualTo(CellRect(1, 1, 1, 1))
    }

    @Test
    fun snapsPastHalfACellOutwardOnlyForOneByOne() {
        val one = CellRect(1, 1, 1, 1)
        assertThat(FolderResizeMath.shouldSnap(51f, 100f, one, ResizeHandle.RIGHT)).isTrue()
        assertThat(FolderResizeMath.shouldSnap(49f, 100f, one, ResizeHandle.RIGHT)).isFalse()
        assertThat(FolderResizeMath.shouldSnap(-60f, 100f, one, ResizeHandle.RIGHT)).isFalse()
        assertThat(FolderResizeMath.shouldSnap(-60f, 100f, one, ResizeHandle.LEFT)).isTrue()
    }

    @Test
    fun snapsInwardForTwoByTwo() {
        val two = CellRect(1, 1, 2, 2)
        assertThat(FolderResizeMath.shouldSnap(-60f, 100f, two, ResizeHandle.RIGHT)).isTrue()
        assertThat(FolderResizeMath.shouldSnap(60f, 100f, two, ResizeHandle.LEFT)).isTrue()
        assertThat(FolderResizeMath.shouldSnap(60f, 100f, two, ResizeHandle.RIGHT)).isFalse()
    }

    @Test
    fun blockedWhenTargetCoversAnotherFolder() {
        val target = CellRect(1, 1, 2, 2)
        assertThat(FolderResizeMath.isAllowed(target, 4, 5) { x, y -> if (x == 2 && y == 2) CellOwner.BLOCKING else CellOwner.EMPTY }).isFalse()
        assertThat(FolderResizeMath.isAllowed(target, 4, 5) { x, _ -> if (x == 2) CellOwner.WIDGET else CellOwner.EMPTY }).isTrue()
        assertThat(FolderResizeMath.isAllowed(CellRect(3, 1, 2, 2), 4, 5) { _, _ -> CellOwner.EMPTY }).isFalse()
        // Shrinking must land on an empty cell: never on a widget (it would hide the folder).
        assertThat(FolderResizeMath.isAllowed(CellRect(1, 1, 1, 1), 4, 5) { _, _ -> CellOwner.WIDGET }).isFalse()
        assertThat(FolderResizeMath.isAllowed(CellRect(1, 1, 1, 1), 4, 5) { _, _ -> CellOwner.EMPTY }).isTrue()
    }

    @Test
    fun rubberBandIsCapped() {
        assertThat(FolderResizeMath.rubberBand(1000f, 100f)).isWithin(0.01f).of(15f)
        assertThat(FolderResizeMath.rubberBand(-1000f, 100f)).isWithin(0.01f).of(-15f)
        assertThat(FolderResizeMath.rubberBand(10f, 100f)).isLessThan(10f)
    }
}
