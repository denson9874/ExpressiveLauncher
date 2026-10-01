package app.lawnchair.folder

import com.android.launcher3.LauncherSettings.Favorites
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LargeFolderOverlapTest {

    private fun owners(map: Map<Pair<Int, Int>, CellOwner>): (Int, Int) -> CellOwner =
        { x, y -> map[x to y] ?: CellOwner.EMPTY }

    @Test
    fun canPlace_overEmptyAndWidgetCells() {
        val ownerAt = owners(mapOf((2 to 1) to CellOwner.WIDGET, (2 to 2) to CellOwner.WIDGET))
        assertThat(LargeFolderOverlap.canPlace(CellRect(1, 1, 2, 2), 4, 5, ownerAt)).isTrue()
    }

    @Test
    fun canPlace_blockedByIconOrGridEdge() {
        val ownerAt = owners(mapOf((2 to 2) to CellOwner.BLOCKING))
        assertThat(LargeFolderOverlap.canPlace(CellRect(1, 1, 2, 2), 4, 5, ownerAt)).isFalse()
        assertThat(LargeFolderOverlap.canPlace(CellRect(3, 1, 2, 2), 4, 5) { _, _ -> CellOwner.EMPTY }).isFalse()
        assertThat(LargeFolderOverlap.canPlace(CellRect(-1, 0, 2, 2), 4, 5) { _, _ -> CellOwner.EMPTY }).isFalse()
    }

    @Test
    fun resolveLargeFolders_keepsFolderOverWidgetInEitherOrder() {
        val folder = LoadItem(1, CellRect(0, 2, 1, 1), LoadKind.LARGE_FOLDER_CANDIDATE)
        val widget = LoadItem(2, CellRect(1, 2, 3, 2), LoadKind.WIDGET)
        assertThat(LargeFolderOverlap.resolveLargeFolders(4, 5, emptyList(), listOf(folder, widget))).containsExactly(1)
        assertThat(LargeFolderOverlap.resolveLargeFolders(4, 5, emptyList(), listOf(widget, folder))).containsExactly(1)
    }

    @Test
    fun resolveLargeFolders_iconLoadedAfterFolderStillBlocks() {
        val folder = LoadItem(1, CellRect(0, 2, 1, 1), LoadKind.LARGE_FOLDER_CANDIDATE)
        val icon = LoadItem(9, CellRect(1, 3, 1, 1), LoadKind.OTHER)
        assertThat(LargeFolderOverlap.resolveLargeFolders(4, 5, emptyList(), listOf(folder, icon))).isEmpty()
    }

    @Test
    fun resolveLargeFolders_searchBarBlocks() {
        val folder = LoadItem(1, CellRect(0, 0, 1, 1), LoadKind.LARGE_FOLDER_CANDIDATE)
        val searchBar = CellRect(0, 0, 4, 1)
        // The folder's own anchor is inside the search bar row only in corrupted data; still blocked.
        assertThat(LargeFolderOverlap.resolveLargeFolders(4, 5, listOf(searchBar), listOf(folder))).isEmpty()
    }

    @Test
    fun resolveLargeFolders_folderCannotCoverAnotherFolderAnchor() {
        val a = LoadItem(1, CellRect(0, 0, 1, 1), LoadKind.LARGE_FOLDER_CANDIDATE)
        val b = LoadItem(2, CellRect(1, 1, 1, 1), LoadKind.LARGE_FOLDER_CANDIDATE)
        // a's 2x2 would hide b's cell, so a stays 1x1; b's 2x2 doesn't touch a.
        assertThat(LargeFolderOverlap.resolveLargeFolders(4, 5, emptyList(), listOf(a, b))).containsExactly(2)
    }

    @Test
    fun resolveLargeFolders_secondFolderCannotCoverFirst() {
        // a covers (1..2, 0..1) and b covers (0..1, 1..2): neither anchor is inside the other's
        // square, but both want (1,1). The first one keeps 2x2; the second stays 1x1.
        val a = LoadItem(1, CellRect(1, 0, 1, 1), LoadKind.LARGE_FOLDER_CANDIDATE)
        val b = LoadItem(2, CellRect(0, 1, 1, 1), LoadKind.LARGE_FOLDER_CANDIDATE)
        assertThat(LargeFolderOverlap.resolveLargeFolders(4, 5, emptyList(), listOf(a, b))).containsExactly(1)
    }

    @Test
    fun resolveLargeFolders_outOfGridAfterGridShrinkDropsToOneByOne() {
        val folder = LoadItem(1, CellRect(3, 4, 1, 1), LoadKind.LARGE_FOLDER_CANDIDATE)
        assertThat(LargeFolderOverlap.resolveLargeFolders(4, 5, emptyList(), listOf(folder))).isEmpty()
    }

    @Test
    fun remarkAfterUnmark_restoresFolderCells() {
        val removedWidget = CellRect(1, 2, 3, 2)
        val folder = CellRect(0, 2, 2, 2)
        val farIcon = CellRect(0, 0, 1, 1)
        assertThat(LargeFolderOverlap.cellsToRemark(removedWidget, listOf(folder, farIcon))).containsExactly(folder)
    }

    @Test
    fun spanForContainer_dockIsAlwaysOneByOne() {
        assertThat(LargeFolderOverlap.spanForContainer(Favorites.CONTAINER_HOTSEAT, true)).isEqualTo(1)
        assertThat(LargeFolderOverlap.spanForContainer(Favorites.CONTAINER_DESKTOP, true)).isEqualTo(2)
        assertThat(LargeFolderOverlap.spanForContainer(Favorites.CONTAINER_DESKTOP, false)).isEqualTo(1)
    }
}
