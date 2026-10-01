package app.lawnchair.folder

import com.android.launcher3.LauncherSettings.Favorites
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LargeFoldersTest {

    @Test
    fun onlyHomeScreenFoldersCanBeLarge() {
        assertThat(LargeFolders.wantsLarge(Favorites.CONTAINER_DESKTOP, 2, 2)).isTrue()
        assertThat(LargeFolders.wantsLarge(Favorites.CONTAINER_HOTSEAT, 2, 2)).isFalse()
        assertThat(LargeFolders.wantsLarge(Favorites.CONTAINER_DESKTOP, 1, 1)).isFalse()
        assertThat(LargeFolders.wantsLarge(Favorites.CONTAINER_DESKTOP, 2, 1)).isFalse()
        assertThat(LargeFolders.wantsLarge(Favorites.CONTAINER_DESKTOP, 3, 3)).isFalse()
    }

    @Test
    fun growsRightAndDownWhenFree() {
        val anchor = LargeFolders.findLargeAnchor(1, 1, 4, 5) { _, _ -> CellOwner.EMPTY }
        assertThat(anchor).asList().containsExactly(1, 1).inOrder()
    }

    @Test
    fun growsLeftOrUpAtTheGridEdge() {
        assertThat(LargeFolders.findLargeAnchor(3, 1, 4, 5) { _, _ -> CellOwner.EMPTY }).asList()
            .containsExactly(2, 1).inOrder()
        assertThat(LargeFolders.findLargeAnchor(1, 4, 4, 5) { _, _ -> CellOwner.EMPTY }).asList()
            .containsExactly(1, 3).inOrder()
        assertThat(LargeFolders.findLargeAnchor(3, 4, 4, 5) { _, _ -> CellOwner.EMPTY }).asList()
            .containsExactly(2, 3).inOrder()
    }

    @Test
    fun skipsOccupiedCells() {
        // (2,1) is taken, so growing right fails; growing left works.
        val anchor = LargeFolders.findLargeAnchor(1, 1, 4, 5) { x, y -> if (x == 2 && y == 1) CellOwner.BLOCKING else CellOwner.EMPTY }
        assertThat(anchor).asList().containsExactly(0, 1).inOrder()
    }

    @Test
    fun noSpaceReturnsNull() {
        assertThat(LargeFolders.findLargeAnchor(1, 1, 4, 5) { x, y -> if (!(x == 1 && y == 1)) CellOwner.BLOCKING else CellOwner.EMPTY }).isNull()
        assertThat(LargeFolders.findLargeAnchor(0, 0, 1, 5) { _, _ -> CellOwner.EMPTY }).isNull()
    }

    @Test
    fun slotsShowAppsThenPreviewTheRest() {
        assertThat(LargeFolders.slotContents(2).toList())
            .containsExactly(0, 1, LargeFolders.SLOT_EMPTY, LargeFolders.SLOT_EMPTY).inOrder()
        assertThat(LargeFolders.slotContents(4).toList()).containsExactly(0, 1, 2, 3).inOrder()
        assertThat(LargeFolders.slotContents(9).toList())
            .containsExactly(0, 1, 2, LargeFolders.SLOT_MORE).inOrder()
    }

    @Test
    fun slotAtMapsQuadrants() {
        assertThat(LargeFolders.slotAt(10f, 10f, 0f, 0f, 100f)).isEqualTo(0)
        assertThat(LargeFolders.slotAt(60f, 10f, 0f, 0f, 100f)).isEqualTo(1)
        assertThat(LargeFolders.slotAt(10f, 60f, 0f, 0f, 100f)).isEqualTo(2)
        assertThat(LargeFolders.slotAt(99f, 99f, 0f, 0f, 100f)).isEqualTo(3)
        assertThat(LargeFolders.slotAt(100f, 50f, 0f, 0f, 100f)).isEqualTo(-1)
    }

    @Test
    fun tileNeedsFullIconsForDirectAndPreviewSlotApps() {
        // Ranks 0-2 get their own slot and 3-6 share the preview slot once a folder has more than
        // four apps; with four or fewer, ranks 0-3 are direct. Rank 2 is outside the 1x1 preview.
        assertThat((0..10).filter { LargeFolders.drawsRank(it) })
            .containsExactly(0, 1, 2, 3, 4, 5, 6)
            .inOrder()
    }

    @Test
    fun growsOverWidgetCells() {
        val anchor = LargeFolders.findLargeAnchor(1, 1, 4, 5) { x, _ -> if (x == 2) CellOwner.WIDGET else CellOwner.EMPTY }
        assertThat(anchor).asList().containsExactly(1, 1).inOrder()
    }
}
