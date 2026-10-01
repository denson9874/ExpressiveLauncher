# Large Home folders v2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Large 2x2 Home folders that can overlay widgets, resize with Pixel-style handles, and animate correctly for open, close, launch, resize, and drag/drop.

**Architecture:** A large folder stays a real 2x2 `CellLayout` child. Overlap rules live in pure Kotlin
helpers (`LargeFolderOverlap`, `FolderResizeMath`, `LargeFolderAnimationGeometry`) with unit tests.
Thin hooks in Launcher3 call into them: `LoaderCursor`/`WorkspaceItemProcessor`, `CellLayout`,
`Workspace`, `FolderIcon`, `FolderAnimationManager`, `FloatingIconView` and `Launcher`. The large
folder is drawn above widgets through view elevation, so Android's Z-ordered touch dispatch gives it
the overlapping touches.

**Tech Stack:** Kotlin and Java (Launcher3 / Lawnchair fork), JUnit 4 + Truth for JVM unit tests in
`tests/expressiveUnit`, Gradle, and an Android 17 QPR2 Beta 5 emulator with adb for device checks.

**Spec:** `docs/superpowers/specs/2026-10-01-large-folders-v2-design.md`

## Global Constraints

- Work only in the worktree `~/ExpressiveWorktrees/large-folders-v2` on branch `claude/large-folders-v2`. Never switch branches in `~/Documents/ChatGPT/New project`.
- JDK: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` (native ARM64 JDK 21). Never use `/usr/bin/java`.
- Mark every edit to upstream Launcher3 files with an `// LC-Note:` comment, as existing large-folder code does.
- Large folders exist only for `Favorites.CONTAINER_DESKTOP`. Dock and app-drawer folders stay 1x1.
- A large folder may cover empty cells and widget cells only. Icons, folders, app pairs and the search/smartspace container block it.
- A folder is never deleted because of its size: anything that doesn't fit becomes 1x1 and its DB row is rewritten to 1x1.
- Widgets are never moved, resized or clipped because of a large folder.
- Motion uses existing Launcher3 interpolators/springs and respects the animator duration scale (including "Remove animations").
- Never commit APKs, recordings, screenshots or logs. Evidence goes in `artifacts/large-folders-2026-10-01/` as text notes only.
- Commit messages are conventional (`feat(folders): …`, `fix(folders): …`, `test(folders): …`) and end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Commit order follows the spec's three areas: overlap and loading (Tasks 1–4), resize frame (Tasks 5–6), animations (Tasks 7–11).

## Review Focus

- **A large folder dragged into the dock.** Expect it to land as a 1x1 folder (span rewritten to 1x1) and not be rejected or lost. Test: `LargeFolderOverlapTest.spanForContainer_dockIsAlwaysOneByOne` (Task 1) plus the Workspace hook in Task 4.
- **Two large folders next to each other.** Expect a large folder never to cover another large folder, either at load or when moved or resized. Test: `resolveLargeFolders_secondFolderCannotCoverFirst` (Task 1) and `blockedWhenTargetCoversAnotherFolder` (Task 5).
- **An icon saved in a cell the folder covers (corrupted DB or restore), in either load order.** Expect the folder to load 1x1 and the icon to stay. Test: `resolveLargeFolders_iconLoadedAfterFolderStillBlocks` (Task 1).
- **A widget removed while it sits under a large folder.** Expect the freed cells to stay occupied, so nothing can be dropped under the tile. Test: `remarkAfterUnmark_restoresFolderCells` (Task 1), wired into `CellLayout` in Task 3 and checked on the emulator in Task 3 step 5d.
- **"Remove animations" (duration scale 0).** Expect open, close, resize and drop to finish instantly with the final state correct: the tile visible and no hidden icons. Test: emulator check in Task 11 step 3, plus `LargeFolderAnimationGeometryTest.fractionClampsToZeroAndOne` (Task 7).

---

### Task 0: Worktree ready and animation baseline recorded

**Files:**
- Create: `artifacts/large-folders-2026-10-01/BASELINE.md`

- [ ] **Step 1: Initialize the systemui submodule in the worktree**

Run:
```bash
cd ~/ExpressiveWorktrees/large-folders-v2
git submodule update --init --reference "$HOME/Documents/ChatGPT/New project/platform_frameworks_libs_systemui" platform_frameworks_libs_systemui
```
Expected: `Submodule path 'platform_frameworks_libs_systemui': checked out 'e12acf09…'`.

- [ ] **Step 2: Baseline unit suite**

Run:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew testLawnWithQuickstepExpressiveDebugUnitTest -q && python3 -m unittest discover -s ci/tests 2>&1 | tail -3
```
Expected: Gradle exit 0 (426 tests), `Ran 291 tests … OK`.

- [ ] **Step 3: Build and install the current debug build on the emulator**

Run:
```bash
emulator -avd Claude_Discover_Debug -no-window -no-audio -no-snapshot-save -port 5590 &   # needs sandbox disabled
adb -s emulator-5590 wait-for-device
until [ "$(adb -s emulator-5590 shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 2; done
./gradlew assembleLawnWithQuickstepExpressiveDebug -q
adb -s emulator-5590 install -r build/outputs/apk/lawnWithQuickstepExpressive/debug/*.apk
```
Expected: `Success`. The package is `dev.launcher.expressive.l3.debug`.

- [ ] **Step 4: Record the five interactions (before)**

Set up the Home screen with a 2x2 large folder of 6 apps next to a 4x2 widget (for example Clock). For each interaction run:
```bash
adb -s emulator-5590 shell dumpsys gfxinfo dev.launcher.expressive.l3.debug reset
adb -s emulator-5590 shell screenrecord --bit-rate 20000000 /sdcard/lf-<name>-before.mp4 &   # stop with kill after the gesture
# perform the gesture with uiautomator-located coordinates (see memory: tap by node text)
adb -s emulator-5590 shell dumpsys gfxinfo dev.launcher.expressive.l3.debug framestats > ~/ExpressiveWorktrees/lf-evidence/<name>-before-framestats.txt
adb -s emulator-5590 pull /sdcard/lf-<name>-before.mp4 ~/ExpressiveWorktrees/lf-evidence/
```
Names: `open`, `close`, `launch` (tap the top-left tile app, then Home), `resize` (footer button), `drop` (drag an app onto the tile). Extract frames with `ffmpeg -i X.mp4 -vf fps=60 frames/%04d.png`. Keep the evidence outside the repo.

- [ ] **Step 5: Write BASELINE.md (text only)**

For each interaction, note the frame numbers where it goes wrong: for example "open: frames 3–9, panel grows from top-left 1x1 circle; tile visible under the panel until frame 12". Also note the janky-frame count from `framestats` (frames whose total duration is over 16.6 ms).

- [ ] **Step 6: Commit**

```bash
git add artifacts/large-folders-2026-10-01/BASELINE.md
git commit -m "test(folders): record large-folder animation baseline

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 1: `LargeFolderOverlap`: pure overlap and load rules

**Files:**
- Create: `lawnchair/src/app/lawnchair/folder/LargeFolderOverlap.kt`
- Test: `tests/expressiveUnit/app/lawnchair/folder/LargeFolderOverlapTest.kt`

**Interfaces:**
- Produces (used by Tasks 2, 3, 4, 5):
  - `data class CellRect(val x: Int, val y: Int, val spanX: Int, val spanY: Int)` with `fun contains(cx: Int, cy: Int): Boolean`, `fun intersects(o: CellRect): Boolean`, `fun cells(): List<Pair<Int, Int>>`
  - `enum class CellOwner { EMPTY, WIDGET, BLOCKING }`
  - `LargeFolderOverlap.canPlace(rect: CellRect, countX: Int, countY: Int, ownerAt: (Int, Int) -> CellOwner): Boolean`
  - `data class LoadItem(val id: Int, val rect: CellRect, val kind: LoadKind)`; `enum class LoadKind { LARGE_FOLDER_CANDIDATE, WIDGET, OTHER }`
  - `LargeFolderOverlap.resolveLargeFolders(countX: Int, countY: Int, blocked: List<CellRect>, items: List<LoadItem>): Set<Int>`: the ids of candidates that stay 2x2
  - `LargeFolderOverlap.cellsToRemark(unmarked: CellRect, others: List<CellRect>): List<CellRect>`: the rects of other items intersecting `unmarked`, which must be re-marked occupied
  - `LargeFolderOverlap.spanForContainer(container: Int, wantsLarge: Boolean): Int`

- [ ] **Step 1: Write the failing tests**

```kotlin
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
    fun resolveLargeFolders_secondFolderCannotCoverFirst() {
        val a = LoadItem(1, CellRect(0, 0, 1, 1), LoadKind.LARGE_FOLDER_CANDIDATE)
        val b = LoadItem(2, CellRect(1, 1, 1, 1), LoadKind.LARGE_FOLDER_CANDIDATE)
        // a takes (0..1, 0..1); b's 2x2 at (1,1) would cover (1,1), which a owns.
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
```

- [ ] **Step 2: Run them and confirm they fail**

Run: `./gradlew testLawnWithQuickstepExpressiveDebugUnitTest --tests 'app.lawnchair.folder.LargeFolderOverlapTest' -q`
Expected: compilation FAIL (`Unresolved reference: LargeFolderOverlap`).

- [ ] **Step 3: Implement**

```kotlin
package app.lawnchair.folder

import com.android.launcher3.LauncherSettings.Favorites

/** A rectangle of grid cells. */
data class CellRect(val x: Int, val y: Int, val spanX: Int, val spanY: Int) {
    fun contains(cx: Int, cy: Int): Boolean = cx in x until x + spanX && cy in y until y + spanY
    fun intersects(o: CellRect): Boolean =
        x < o.x + o.spanX && o.x < x + spanX && y < o.y + o.spanY && o.y < y + spanY
    fun cells(): List<Pair<Int, Int>> =
        (x until x + spanX).flatMap { cx -> (y until y + spanY).map { cy -> cx to cy } }
}

/** What holds a grid cell, as far as a large folder is concerned. */
enum class CellOwner { EMPTY, WIDGET, BLOCKING }

enum class LoadKind { LARGE_FOLDER_CANDIDATE, WIDGET, OTHER }

/** A Home item seen by the loader. Large-folder candidates are given at their 1x1 anchor. */
data class LoadItem(val id: Int, val rect: CellRect, val kind: LoadKind)

/**
 * Large folders v2: a large (2x2) Home folder may cover empty cells and widgets, never icons,
 * folders, app pairs or the search bar. Widgets are never moved for it.
 */
object LargeFolderOverlap {

    @JvmStatic
    fun canPlace(rect: CellRect, countX: Int, countY: Int, ownerAt: (Int, Int) -> CellOwner): Boolean {
        if (rect.x < 0 || rect.y < 0 || rect.x + rect.spanX > countX || rect.y + rect.spanY > countY) {
            return false
        }
        return rect.cells().all { (cx, cy) -> ownerAt(cx, cy) != CellOwner.BLOCKING }
    }

    /**
     * Which stored large folders keep 2x2 once every Home item on the page is known, independent
     * of load order. Candidates are checked in list order; a promoted folder blocks later ones.
     */
    @JvmStatic
    fun resolveLargeFolders(
        countX: Int,
        countY: Int,
        blocked: List<CellRect>,
        items: List<LoadItem>,
    ): Set<Int> {
        val promoted = mutableListOf<CellRect>()
        val result = linkedSetOf<Int>()
        for (candidate in items.filter { it.kind == LoadKind.LARGE_FOLDER_CANDIDATE }) {
            val target = CellRect(candidate.rect.x, candidate.rect.y, LargeFolders.SPAN, LargeFolders.SPAN)
            val ok = canPlace(target, countX, countY) { cx, cy ->
                when {
                    blocked.any { it.contains(cx, cy) } -> CellOwner.BLOCKING
                    promoted.any { it.contains(cx, cy) } -> CellOwner.BLOCKING
                    items.any { it.id != candidate.id && it.kind != LoadKind.WIDGET && it.rect.contains(cx, cy) } ->
                        CellOwner.BLOCKING
                    items.any { it.kind == LoadKind.WIDGET && it.rect.contains(cx, cy) } -> CellOwner.WIDGET
                    else -> CellOwner.EMPTY
                }
            }
            if (ok) {
                promoted += target
                result += candidate.id
            }
        }
        return result
    }

    /** After [unmarked] was cleared from the occupancy grid, these overlapping items must be re-marked. */
    @JvmStatic
    fun cellsToRemark(unmarked: CellRect, others: List<CellRect>): List<CellRect> =
        others.filter { it.intersects(unmarked) }

    @JvmStatic
    fun spanForContainer(container: Int, wantsLarge: Boolean): Int =
        if (wantsLarge && container == Favorites.CONTAINER_DESKTOP) LargeFolders.SPAN else 1
}
```

- [ ] **Step 4: Run the tests and confirm they pass**

Run: `./gradlew testLawnWithQuickstepExpressiveDebugUnitTest --tests 'app.lawnchair.folder.*' -q`
Expected: PASS (LargeFolderOverlapTest 9, LargeFoldersTest unchanged).

- [ ] **Step 5: Commit**

```bash
git add lawnchair/src/app/lawnchair/folder/LargeFolderOverlap.kt tests/expressiveUnit/app/lawnchair/folder/LargeFolderOverlapTest.kt
git commit -m "feat(folders): overlap rules for large folders over widgets

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Loader: order-independent 2x2 decision that never deletes

**Files:**
- Modify: `src/com/android/launcher3/model/WorkspaceItemProcessor.kt:519-540` (`processFolderOrAppPair`) and `finalizeData` (around line 749)
- Modify: `src/com/android/launcher3/model/LoaderCursor.java` (`checkItemPlacement` ~603, `isDesktopRegionFree` ~690)
- Test: `tests/expressiveUnit/app/lawnchair/folder/LargeFolderOverlapTest.kt` (load rules already covered in Task 1; this task adds glue)

**Interfaces:**
- Consumes: `LargeFolderOverlap.resolveLargeFolders`, `LoadItem`, `LoadKind`, `CellRect` (Task 1)
- Produces: `LoaderCursor.markLargeFolderCandidate(FolderInfo)`, `LoaderCursor.getLargeFolderCandidates(): List<FolderInfo>`, and `LoaderCursor.getSearchBarRect(int screenId): CellRect?`

- [ ] **Step 1: Load every folder 1x1 first and record candidates**

In `WorkspaceItemProcessor.processFolderOrAppPair`, replace the `val span = if (…isDesktopRegionFree…)` block with:
```kotlin
        // LC-Note: Large folders v2. Every folder loads 1x1 at its anchor; stored 2x2 folders are
        // promoted in finalizeData once all Home items are known, so load order doesn't matter.
        val wantsLarge = collection is FolderInfo &&
            LargeFolders.wantsLarge(collection.container, c.spanX, c.spanY)
        collection.spanX = 1
        collection.spanY = 1
        if (wantsLarge) c.markLargeFolderCandidate(collection as FolderInfo)
```
(Remove the old `collection.spanX = span` / `collection.spanY = span` lines.)

- [ ] **Step 2: Add the candidate list and search-bar rect to LoaderCursor**

Add to `LoaderCursor.java` (and delete `isDesktopRegionFree`, now unused; `grep -rn isDesktopRegionFree` must return nothing):
```java
    // LC-Note: Large folders v2. Stored 2x2 folders, resolved after all items load.
    private final List<FolderInfo> mLargeFolderCandidates = new ArrayList<>();

    public void markLargeFolderCandidate(FolderInfo info) {
        mLargeFolderCandidates.add(info);
    }

    public List<FolderInfo> getLargeFolderCandidates() {
        return mLargeFolderCandidates;
    }

    /** LC-Note: The search/smartspace row reserved on the first screen, or null. */
    @Nullable
    public app.lawnchair.folder.CellRect getSearchBarRect(int screenId) {
        if (screenId == Workspace.FIRST_SCREEN_ID
                && PreferenceCacheExtensionsKt.firstCached(preferenceManager2.getEnableSmartspace())) {
            return new app.lawnchair.folder.CellRect(0, 0, mIDP.numSearchContainerColumns, 1);
        }
        return null;
    }

    public int getGridColumns() { return mIDP.numColumns; }
    public int getGridRows() { return mIDP.numRows; }
```

- [ ] **Step 3: Promote in finalizeData before folder items are processed**

In `WorkspaceItemProcessor.finalizeData`, right after `val itemsDeleted = c.commitDeleted()`, call `promoteLargeFolders(modelDbController)` and add:
```kotlin
    /** LC-Note: Large folders v2. Decide 2x2 vs 1x1 per page; rewrite rows that no longer fit. */
    private fun promoteLargeFolders(modelDbController: ModelDbController) {
        val candidates = c.largeFolderCandidates.filter { loadedItems.get(it.id) === it }
        if (candidates.isEmpty()) return
        for ((screenId, onScreen) in candidates.groupBy { it.screenId }) {
            val items = buildList {
                loadedItems.forEach { info ->
                    if (info.container != Favorites.CONTAINER_DESKTOP || info.screenId != screenId) return@forEach
                    val kind = when {
                        onScreen.any { it === info } -> LoadKind.LARGE_FOLDER_CANDIDATE
                        info is LauncherAppWidgetInfo -> LoadKind.WIDGET
                        else -> LoadKind.OTHER
                    }
                    add(LoadItem(info.id, CellRect(info.cellX, info.cellY, info.spanX, info.spanY), kind))
                }
            }
            val blocked = listOfNotNull(c.getSearchBarRect(screenId))
            val large = LargeFolderOverlap.resolveLargeFolders(c.gridColumns, c.gridRows, blocked, items)
            for (folder in onScreen) {
                if (folder.id in large) {
                    folder.spanX = LargeFolders.SPAN
                    folder.spanY = LargeFolders.SPAN
                } else {
                    val values = ContentValues().apply {
                        put(Favorites.SPANX, 1)
                        put(Favorites.SPANY, 1)
                    }
                    modelDbController.update(Favorites.TABLE_NAME, values, "${Favorites._ID} = ?", arrayOf(folder.id.toString()))
                }
            }
        }
    }
```
Imports: `android.content.ContentValues`, `app.lawnchair.folder.CellRect`, `app.lawnchair.folder.LargeFolderOverlap`, `app.lawnchair.folder.LoadItem`, `app.lawnchair.folder.LoadKind`, `com.android.launcher3.model.data.LauncherAppWidgetInfo`.

Check that `ModelDbController.update(String table, ContentValues values, String selection, String[] selectionArgs)` exists (`grep -n "public int update" src/com/android/launcher3/model/ModelDbController.java`). If the signature differs, use the one present.

- [ ] **Step 4: Compile and run the unit suite**

Run: `./gradlew testLawnWithQuickstepExpressiveDebugUnitTest -q`
Expected: exit 0. `processFolderItems` still upgrades tile icons because `LargeFolders.isLarge` sees span 2 after promotion; promotion runs before `processFolderItems`, so confirm the order in the diff.

- [ ] **Step 5: Emulator check, overlap survives a restart in both orders**

Install the debug build. Using sqlite through `adb shell run-as dev.launcher.expressive.l3.debug sqlite3 databases/launcher*.db`, set a folder row to spanX=2,spanY=2 at a cell next to a 4x2 widget so they overlap. Force-stop and relaunch. Expect a 2x2 tile over the widget, with the widget still at its cells. Repeat with the widget's `_id` lower than the folder's (swap ids via a copy row if needed). Put an icon row in a covered cell: expect the folder to load 1x1, the icon to stay, and the folder row's span to read 1,1. Write the results in `artifacts/large-folders-2026-10-01/NOTES.md`.

- [ ] **Step 6: Commit**

```bash
git add src/com/android/launcher3/model/WorkspaceItemProcessor.kt src/com/android/launcher3/model/LoaderCursor.java artifacts/large-folders-2026-10-01/NOTES.md
git commit -m "feat(folders): load large folders over widgets in any order, never delete them

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Runtime occupancy, drawing order and reorder locks

**Files:**
- Create: `lawnchair/src/app/lawnchair/folder/LargeFolderLayout.kt` (CellLayout-side glue)
- Modify: `src/com/android/launcher3/CellLayout.java:1889-1912` (`markCellsAsOccupiedForView`, `markCellsAsUnoccupiedForView`)
- Modify: `src/com/android/launcher3/folder/FolderIcon.java` (`onSizeModeChanged`, `setLayoutParams` path)
- Modify: `lawnchair/res/values/id.xml` (add `large_folder_reorder_lock`)
- Modify: `lawnchair/res/values/dimens.xml` (add `large_folder_elevation` = `1dp`)

**Interfaces:**
- Consumes: `CellRect`, `CellOwner`, `LargeFolderOverlap.cellsToRemark` (Task 1)
- Produces: `LargeFolderLayout.ownerAt(cellLayout: CellLayout, self: View?, x: Int, y: Int): CellOwner`, `LargeFolderLayout.rectOf(view: View): CellRect`, `LargeFolderLayout.refresh(cellLayout: CellLayout)`, `LargeFolderLayout.remarkOverlapping(cellLayout: CellLayout, unmarked: View)`

- [ ] **Step 1: Write LargeFolderLayout**

```kotlin
package app.lawnchair.folder

import android.view.View
import com.android.launcher3.CellLayout
import com.android.launcher3.R
import com.android.launcher3.celllayout.CellLayoutLayoutParams
import com.android.launcher3.folder.FolderIcon
import com.android.launcher3.widget.LauncherAppWidgetHostView

/** Large folders v2: CellLayout glue for large folders drawn over widgets. */
object LargeFolderLayout {

    @JvmStatic
    fun rectOf(view: View): CellRect {
        val lp = view.layoutParams as CellLayoutLayoutParams
        return CellRect(lp.cellX, lp.cellY, lp.cellHSpan, lp.cellVSpan)
    }

    private fun children(cellLayout: CellLayout): List<View> {
        val container = cellLayout.shortcutsAndWidgets
        return (0 until container.childCount).map { container.getChildAt(it) }
    }

    /** Who holds ([x], [y]) for a large folder; [self] (the folder being placed) counts as empty. */
    @JvmStatic
    fun ownerAt(cellLayout: CellLayout, self: View?, x: Int, y: Int): CellOwner {
        var owner = CellOwner.EMPTY
        for (child in children(cellLayout)) {
            if (child === self || child.layoutParams !is CellLayoutLayoutParams) continue
            if (!rectOf(child).contains(x, y)) continue
            if (child !is LauncherAppWidgetHostView) return CellOwner.BLOCKING
            owner = CellOwner.WIDGET
        }
        return owner
    }

    /** Re-marks items that overlap [unmarked] after its cells were cleared. */
    @JvmStatic
    fun remarkOverlapping(cellLayout: CellLayout, unmarked: View) {
        if (unmarked.layoutParams !is CellLayoutLayoutParams) return
        val others = children(cellLayout).filter { it !== unmarked && it.layoutParams is CellLayoutLayoutParams }
        val toRemark = LargeFolderOverlap.cellsToRemark(rectOf(unmarked), others.map(::rectOf))
        others.filter { rectOf(it) in toRemark }.forEach { cellLayout.markCellsForViewRaw(it, true) }
    }

    /**
     * Locks a large folder and the widgets it covers against reorder, so neither is pushed while
     * something else is dragged. Only locks set here are released here.
     */
    @JvmStatic
    fun refresh(cellLayout: CellLayout) {
        val all = children(cellLayout).filter { it.layoutParams is CellLayoutLayoutParams }
        val folders = all.filter { it is FolderIcon && it.isLarge }
        val widgets = all.filterIsInstance<LauncherAppWidgetHostView>()
        for (view in all) {
            val lp = view.layoutParams as CellLayoutLayoutParams
            val rect = rectOf(view)
            val overlapped = when (view) {
                in folders -> widgets.any { rectOf(it).intersects(rect) }
                is LauncherAppWidgetHostView -> folders.any { rectOf(it).intersects(rect) }
                else -> false
            }
            val lockedHere = view.getTag(R.id.large_folder_reorder_lock) == true
            if (overlapped && lp.canReorder) {
                lp.canReorder = false
                view.setTag(R.id.large_folder_reorder_lock, true)
            } else if (!overlapped && lockedHere) {
                lp.canReorder = true
                view.setTag(R.id.large_folder_reorder_lock, null)
            }
        }
    }
}
```

- [ ] **Step 2: Hook CellLayout**

In `CellLayout.java`, add a raw helper and call the glue. Replace the two methods' non-widget tails so they read:
```java
    public void markCellsAsOccupiedForView(View view) {
        if (view instanceof LauncherAppWidgetHostView
                && view.getTag() instanceof LauncherAppWidgetInfo info) {
            CellPos pos = mActivity.getCellPosMapper().mapModelToPresenter(info);
            mOccupied.markCells(pos.cellX, pos.cellY, info.spanX, info.spanY, true);
            LargeFolderLayout.refresh(this); // LC-Note: large folders v2
            return;
        }
        if (view == null || view.getParent() != mShortcutsAndWidgets) return;
        markCellsForViewRaw(view, true);
        LargeFolderLayout.refresh(this); // LC-Note: large folders v2
    }

    public void markCellsAsUnoccupiedForView(View view) {
        if (view instanceof LauncherAppWidgetHostView
                && view.getTag() instanceof LauncherAppWidgetInfo info) {
            CellPos pos = mActivity.getCellPosMapper().mapModelToPresenter(info);
            mOccupied.markCells(pos.cellX, pos.cellY, info.spanX, info.spanY, false);
            // LC-Note: large folders v2. A large folder over this widget keeps its cells.
            LargeFolderLayout.remarkOverlapping(this, view);
            LargeFolderLayout.refresh(this);
            return;
        }
        if (view == null || view.getParent() != mShortcutsAndWidgets) return;
        markCellsForViewRaw(view, false);
        LargeFolderLayout.remarkOverlapping(this, view); // LC-Note: widgets under a large folder keep theirs
        LargeFolderLayout.refresh(this);
    }

    /** LC-Note: Marks a child's own cells without touching overlapping items. */
    public void markCellsForViewRaw(View view, boolean value) {
        CellLayoutLayoutParams lp = (CellLayoutLayoutParams) view.getLayoutParams();
        mOccupied.markCells(lp.getCellX(), lp.getCellY(), lp.cellHSpan, lp.cellVSpan, value);
    }
```
Import `app.lawnchair.folder.LargeFolderLayout`. Note: `LargeFolderLayout.rectOf` reads `lp.cellX`; if `CellLayoutLayoutParams` exposes only `getCellX()`, use `lp.getCellX()` in Kotlin (`lp.cellX` works through the getter).

- [ ] **Step 3: Draw large folders above widgets**

In `FolderIcon.onSizeModeChanged()` and at the end of `FolderIcon.inflateIcon(...)` (after `icon.setOnClickListener` setup), add a call to a new method:
```java
    /** LC-Note: Large folders draw above widgets they cover and get their touches (Z order). */
    private void updateLargeElevation() {
        boolean large = isLarge();
        setOutlineProvider(large ? null : ViewOutlineProvider.BACKGROUND);
        setElevation(large ? getResources().getDimension(R.dimen.large_folder_elevation) : 0f);
    }
```
Resources: add `<item name="large_folder_reorder_lock" type="id"/>` to `lawnchair/res/values/id.xml` and `<dimen name="large_folder_elevation">1dp</dimen>` to `lawnchair/res/values/dimens.xml`.

- [ ] **Step 4: Unit suite**

Run: `./gradlew testLawnWithQuickstepExpressiveDebugUnitTest -q`
Expected: exit 0.

- [ ] **Step 5: Emulator check**

With a folder made 2x2 over a widget (from Task 2's DB setup):
- (a) Tap an app on the tile where it covers the widget: the app opens, not the widget.
- (b) Long-press there: the folder drag starts.
- (c) Tap the widget outside the tile: the widget responds.
- (d) Remove the widget (drag to Remove): `adb shell dumpsys activity com.android.launcher3 …` isn't available, so check by dragging an app onto the freed cell under the tile. The app must go into the folder or be placed elsewhere, never under the tile.
- (e) Drag another widget across the folder: the folder never moves.

Note the results in NOTES.md.

- [ ] **Step 6: Commit**

```bash
git add lawnchair/src/app/lawnchair/folder/LargeFolderLayout.kt src/com/android/launcher3/CellLayout.java src/com/android/launcher3/folder/FolderIcon.java lawnchair/res/values/id.xml lawnchair/res/values/dimens.xml artifacts/large-folders-2026-10-01/NOTES.md
git commit -m "feat(folders): large folders sit above widgets and keep their cells

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Placement: make large, move and dock drop with widget overlap

**Files:**
- Modify: `lawnchair/src/app/lawnchair/folder/LargeFolders.kt` (`findLargeAnchor` takes a `CellOwner` lookup)
- Modify: `lawnchair/src/app/lawnchair/folder/LargeFolderController.kt` (`toggle` → `applySize(launcher, icon, target: CellRect)` + `toggle` uses it)
- Modify: `src/com/android/launcher3/Workspace.java` (`onDrop`, ~2286–2310 and ~2340)
- Test: `tests/expressiveUnit/app/lawnchair/folder/LargeFoldersTest.kt`

**Interfaces:**
- Consumes: `CellRect`, `CellOwner`, `LargeFolderOverlap.canPlace`, `LargeFolderOverlap.spanForContainer` (Task 1); `LargeFolderLayout.ownerAt`, `LargeFolderLayout.rectOf` (Task 3)
- Produces: `LargeFolders.findLargeAnchor(cellX, cellY, countX, countY, ownerAt: (Int, Int) -> CellOwner): IntArray?`, `LargeFolderController.applySize(launcher: Launcher, icon: FolderIcon, target: CellRect): Boolean`, `LargeFolderController.overlayDropAnchor(layout: CellLayout, icon: FolderIcon, nearest: IntArray): IntArray?`

- [ ] **Step 1: Update the existing tests to the new lookup and add a widget case**

In `LargeFoldersTest.kt`, change every `{ _, _ -> false }` to `{ _, _ -> CellOwner.EMPTY }` and `{ x, y -> x == 2 && y == 1 }` to `{ x, y -> if (x == 2 && y == 1) CellOwner.BLOCKING else CellOwner.EMPTY }`, then add:
```kotlin
    @Test
    fun growsOverWidgetCells() {
        val anchor = LargeFolders.findLargeAnchor(1, 1, 4, 5) { x, _ -> if (x == 2) CellOwner.WIDGET else CellOwner.EMPTY }
        assertThat(anchor).asList().containsExactly(1, 1).inOrder()
    }
```

- [ ] **Step 2: Run and confirm compile failure**

Run: `./gradlew testLawnWithQuickstepExpressiveDebugUnitTest --tests 'app.lawnchair.folder.LargeFoldersTest' -q`
Expected: FAIL (type mismatch: Boolean vs CellOwner).

- [ ] **Step 3: Implement the new findLargeAnchor**

```kotlin
    @JvmStatic
    fun findLargeAnchor(
        cellX: Int,
        cellY: Int,
        countX: Int,
        countY: Int,
        ownerAt: (x: Int, y: Int) -> CellOwner,
    ): IntArray? {
        val candidates = listOf(cellX to cellY, cellX - 1 to cellY, cellX to cellY - 1, cellX - 1 to cellY - 1)
        for ((x, y) in candidates) {
            if (LargeFolderOverlap.canPlace(CellRect(x, y, SPAN, SPAN), countX, countY, ownerAt)) {
                return intArrayOf(x, y)
            }
        }
        return null
    }
```

- [ ] **Step 4: Controller: shared applySize, toggle and drop anchor**

Replace the body of `LargeFolderController` with:
```kotlin
object LargeFolderController {

    @JvmStatic
    fun canResize(icon: FolderIcon?): Boolean = icon?.mInfo?.container == Favorites.CONTAINER_DESKTOP

    /** Footer button / TalkBack: grow right/down first, shrink to the top-left cell. */
    @JvmStatic
    fun toggle(launcher: Launcher, icon: FolderIcon): Boolean {
        val info = icon.mInfo
        if (!canResize(icon)) return false
        val cellLayout = launcher.workspace.getScreenWithId(info.screenId) ?: return false
        val target = if (LargeFolders.isLarge(info)) {
            CellRect(info.cellX, info.cellY, 1, 1)
        } else {
            val anchor = LargeFolders.findLargeAnchor(info.cellX, info.cellY, cellLayout.countX, cellLayout.countY) { x, y ->
                LargeFolderLayout.ownerAt(cellLayout, icon, x, y)
            }
            if (anchor == null) {
                Toast.makeText(launcher, R.string.large_folder_no_space, Toast.LENGTH_SHORT).show()
                return false
            }
            CellRect(anchor[0], anchor[1], LargeFolders.SPAN, LargeFolders.SPAN)
        }
        return applySize(launcher, icon, target)
    }

    /** Moves/resizes [icon] to [target] (1x1 or 2x2), saving it. False if [target] is blocked. */
    @JvmStatic
    fun applySize(launcher: Launcher, icon: FolderIcon, target: CellRect): Boolean {
        val info = icon.mInfo
        val cellLayout = launcher.workspace.getScreenWithId(info.screenId) ?: return false
        val lp = icon.layoutParams as? CellLayoutLayoutParams ?: return false
        if (target.spanX > 1 && !LargeFolderOverlap.canPlace(target, cellLayout.countX, cellLayout.countY) { x, y ->
                LargeFolderLayout.ownerAt(cellLayout, icon, x, y)
            }
        ) {
            return false
        }
        cellLayout.markCellsAsUnoccupiedForView(icon)
        lp.setCellX(target.x)
        lp.setCellY(target.y)
        lp.setTmpCellX(target.x)
        lp.setTmpCellY(target.y)
        lp.cellHSpan = target.spanX
        lp.cellVSpan = target.spanY
        launcher.modelWriter.modifyItemInDatabase(info, info.container, info.screenId, target.x, target.y, target.spanX, target.spanY)
        cellLayout.markCellsAsOccupiedForView(icon)
        icon.onSizeModeChanged()
        icon.announceForAccessibility(
            launcher.getString(if (target.spanX > 1) R.string.large_folder_made_large else R.string.large_folder_made_small),
        )
        return true
    }

    /** Where a dragged large folder lands when it may cover widgets near [nearest], or null. */
    @JvmStatic
    fun overlayDropAnchor(layout: CellLayout, icon: FolderIcon, nearest: IntArray): IntArray? {
        if (!icon.isLarge) return null
        val rect = CellRect(nearest[0], nearest[1], LargeFolders.SPAN, LargeFolders.SPAN)
        return if (LargeFolderOverlap.canPlace(rect, layout.countX, layout.countY) { x, y ->
                LargeFolderLayout.ownerAt(layout, icon, x, y)
            }
        ) nearest.copyOf() else null
    }
}
```
Imports: `com.android.launcher3.CellLayout`.

- [ ] **Step 5: Workspace drop hooks**

In `Workspace.onDrop`, right before `boolean returnToOriginalCellToPreventShuffling = …`, add:
```java
                // LC-Note: Large folders v2. A large folder may land over widgets without reorder.
                int[] largeOverlay = (!hasMovedIntoHotseat && cell instanceof FolderIcon fi)
                        ? LargeFolderController.overlayDropAnchor(dropTargetLayout, fi, mTargetCell)
                        : null;
```
Then change the reorder branch to:
```java
                if (largeOverlay != null) {
                    mTargetCell = largeOverlay;
                    resultSpan[0] = spanX;
                    resultSpan[1] = spanY;
                } else if (returnToOriginalCellToPreventShuffling) {
```
(The original `if (returnToOriginalCellToPreventShuffling) {` becomes `else if`, and the declaration of `resultSpan` must come before the new `if`.)

For the dock, just before `addInScreen(cell, container, …)` in the `hasMovedLayouts` branch and before `lp.cellHSpan = item.spanX;`, add:
```java
                    // LC-Note: Large folders v2. Dock folders are always 1x1.
                    if (cell instanceof FolderIcon && LargeFolders.isLarge(info)
                            && container != CONTAINER_DESKTOP) {
                        int span = LargeFolderOverlap.spanForContainer(container, true);
                        info.spanX = info.spanY = item.spanX = item.spanY = span;
                    }
```
After the drop completes for a FolderIcon, call `((FolderIcon) cell).onSizeModeChanged()` (inside the same `if (foundCell)` block after `modifyItemInDatabase`).

During drag-over, `findNearestArea`/`performReorder(MODE_DRAG_OVER)` would show a reorder preview. In `Workspace.onDragOver` (search `MODE_DRAG_OVER`), skip `performReorder` and call `mDragTargetLayout.visualizeDropLocation(...)` at the overlay anchor when `LargeFolderController.overlayDropAnchor(...) != null`, using the same anchor computation.

- [ ] **Step 6: Tests and emulator**

Run: `./gradlew testLawnWithQuickstepExpressiveDebugUnitTest -q`, expect exit 0. On the emulator:
- Footer button grows the folder over a neighbouring widget.
- Dragging the large folder onto a widget area lands it there with the 2x2 outline shown during the drag, and the widget doesn't move.
- Dragging it onto an icon falls back to normal reorder or vacant placement.
- Dragging it into the dock gives a 1x1 dock folder; after a restart it's still 1x1.

Note the results in NOTES.md.

- [ ] **Step 7: Commit**

```bash
git add lawnchair/src/app/lawnchair/folder/LargeFolders.kt lawnchair/src/app/lawnchair/folder/LargeFolderController.kt src/com/android/launcher3/Workspace.java tests/expressiveUnit/app/lawnchair/folder/LargeFoldersTest.kt artifacts/large-folders-2026-10-01/NOTES.md
git commit -m "feat(folders): grow and move large folders over widgets; dock keeps them 1x1

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: `FolderResizeMath`: snapping, direction and blocking

**Files:**
- Create: `lawnchair/src/app/lawnchair/folder/FolderResizeMath.kt`
- Test: `tests/expressiveUnit/app/lawnchair/folder/FolderResizeMathTest.kt`

**Interfaces:**
- Consumes: `CellRect`, `CellOwner`, `LargeFolderOverlap.canPlace` (Task 1)
- Produces:
  - `enum class ResizeHandle { LEFT, TOP, RIGHT, BOTTOM }`
  - `FolderResizeMath.targetFor(current: CellRect, handle: ResizeHandle): CellRect`: the rect this handle snaps to (grow outward from 1x1, or shrink toward the pulled side from 2x2)
  - `FolderResizeMath.shouldSnap(dragPx: Float, cellPx: Float, current: CellRect, handle: ResizeHandle): Boolean`: true past half a cell in the meaningful direction (outward for 1x1, inward for 2x2)
  - `FolderResizeMath.isAllowed(target: CellRect, countX: Int, countY: Int, ownerAt: (Int, Int) -> CellOwner): Boolean`
  - `FolderResizeMath.rubberBand(dragPx: Float, cellPx: Float): Float`: the visual offset when blocked, capped at 0.15 × cell

- [ ] **Step 1: Write the failing tests**

```kotlin
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
        assertThat(FolderResizeMath.isAllowed(CellRect(1, 1, 1, 1), 4, 5) { _, _ -> CellOwner.BLOCKING }).isTrue()
    }

    @Test
    fun rubberBandIsCapped() {
        assertThat(FolderResizeMath.rubberBand(1000f, 100f)).isWithin(0.01f).of(15f)
        assertThat(FolderResizeMath.rubberBand(-1000f, 100f)).isWithin(0.01f).of(-15f)
        assertThat(FolderResizeMath.rubberBand(10f, 100f)).isLessThan(10f)
    }
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `./gradlew testLawnWithQuickstepExpressiveDebugUnitTest --tests 'app.lawnchair.folder.FolderResizeMathTest' -q`
Expected: compilation FAIL.

- [ ] **Step 3: Implement**

```kotlin
package app.lawnchair.folder

import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.tanh

enum class ResizeHandle { LEFT, TOP, RIGHT, BOTTOM }

/** Large folders v2: resize-frame math. A folder is either 1x1 or 2x2. */
object FolderResizeMath {

    private const val SNAP_FRACTION = 0.5f
    private const val RUBBER_BAND_FRACTION = 0.15f

    private fun outwardSign(handle: ResizeHandle): Float = when (handle) {
        ResizeHandle.RIGHT, ResizeHandle.BOTTOM -> 1f
        ResizeHandle.LEFT, ResizeHandle.TOP -> -1f
    }

    @JvmStatic
    fun targetFor(current: CellRect, handle: ResizeHandle): CellRect {
        return if (current.spanX == 1) {
            when (handle) {
                ResizeHandle.RIGHT, ResizeHandle.BOTTOM -> CellRect(current.x, current.y, 2, 2)
                ResizeHandle.LEFT -> CellRect(current.x - 1, current.y, 2, 2)
                ResizeHandle.TOP -> CellRect(current.x, current.y - 1, 2, 2)
            }
        } else {
            when (handle) {
                ResizeHandle.RIGHT, ResizeHandle.BOTTOM -> CellRect(current.x, current.y, 1, 1)
                ResizeHandle.LEFT -> CellRect(current.x + 1, current.y, 1, 1)
                ResizeHandle.TOP -> CellRect(current.x, current.y + 1, 1, 1)
            }
        }
    }

    @JvmStatic
    fun shouldSnap(dragPx: Float, cellPx: Float, current: CellRect, handle: ResizeHandle): Boolean {
        val wanted = if (current.spanX == 1) outwardSign(handle) else -outwardSign(handle)
        return dragPx * wanted > cellPx * SNAP_FRACTION
    }

    @JvmStatic
    fun isAllowed(target: CellRect, countX: Int, countY: Int, ownerAt: (Int, Int) -> CellOwner): Boolean =
        if (target.spanX == 1) {
            target.x in 0 until countX && target.y in 0 until countY
        } else {
            LargeFolderOverlap.canPlace(target, countX, countY, ownerAt)
        }

    @JvmStatic
    fun rubberBand(dragPx: Float, cellPx: Float): Float {
        val max = cellPx * RUBBER_BAND_FRACTION
        return sign(dragPx) * max * tanh(abs(dragPx) / (2 * max))
    }
}
```

- [ ] **Step 4: Run and confirm pass**

Run: `./gradlew testLawnWithQuickstepExpressiveDebugUnitTest --tests 'app.lawnchair.folder.*' -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add lawnchair/src/app/lawnchair/folder/FolderResizeMath.kt tests/expressiveUnit/app/lawnchair/folder/FolderResizeMathTest.kt
git commit -m "feat(folders): resize math for folder handles

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: `FolderResizeFrame` view, trigger and accessibility

**Files:**
- Create: `lawnchair/src/app/lawnchair/folder/FolderResizeFrame.kt`
- Create: `lawnchair/res/layout/folder_resize_frame.xml`
- Modify: `lawnchair/res/values/strings.xml` (handle labels)
- Modify: `src/com/android/launcher3/Workspace.java` (`onDrop`, before `final CellLayout parent = …`)
- Modify: `src/com/android/launcher3/AbstractFloatingView.java` (add the `TYPE_FOLDER_RESIZE_FRAME` bit, if types are bit flags there)

**Interfaces:**
- Consumes: `ResizeHandle`, `FolderResizeMath.*` (Task 5); `LargeFolderController.applySize`, `LargeFolderLayout.ownerAt`, `LargeFolderLayout.rectOf` (Tasks 3–4); `FolderIcon.animateSizeChange(fromRect: CellRect)` from Task 10 (until Task 10 lands, `applySize` already calls `onSizeModeChanged`, which redraws)
- Produces: `FolderResizeFrame.show(launcher: Launcher, icon: FolderIcon)`

- [ ] **Step 1: Strings**

Add to `lawnchair/res/values/strings.xml` under the large-folder block:
```xml
    <string name="large_folder_resize_frame">Resize %1$s</string>
    <string name="large_folder_handle_left">Expand left</string>
    <string name="large_folder_handle_top">Expand up</string>
    <string name="large_folder_handle_right">Expand right</string>
    <string name="large_folder_handle_bottom">Expand down</string>
    <string name="large_folder_handle_shrink">Make folder small</string>
```

- [ ] **Step 2: Layout**

`lawnchair/res/layout/folder_resize_frame.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<app.lawnchair.folder.FolderResizeFrame xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:importantForAccessibility="yes">
    <ImageView android:id="@+id/folder_handle_left" android:layout_width="24dp" android:layout_height="24dp"
        android:src="@drawable/ic_widget_resize_handle" android:layout_gravity="start|center_vertical" />
    <ImageView android:id="@+id/folder_handle_top" android:layout_width="24dp" android:layout_height="24dp"
        android:src="@drawable/ic_widget_resize_handle" android:layout_gravity="top|center_horizontal" />
    <ImageView android:id="@+id/folder_handle_right" android:layout_width="24dp" android:layout_height="24dp"
        android:src="@drawable/ic_widget_resize_handle" android:layout_gravity="end|center_vertical" />
    <ImageView android:id="@+id/folder_handle_bottom" android:layout_width="24dp" android:layout_height="24dp"
        android:src="@drawable/ic_widget_resize_handle" android:layout_gravity="bottom|center_horizontal" />
</app.lawnchair.folder.FolderResizeFrame>
```
Check that `ic_widget_resize_handle` exists: `ls res/drawable*/ic_widget_resize_handle*`. If it's named differently, use the drawable referenced in `res/layout/app_widget_resize_frame.xml`.

- [ ] **Step 3: The frame**

```kotlin
package app.lawnchair.folder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.Launcher
import com.android.launcher3.R
import com.android.launcher3.dragndrop.DragLayer
import com.android.launcher3.folder.FolderIcon

/** Large folders v2: handles that snap a Home folder between 1x1 and 2x2. */
class FolderResizeFrame(context: Context, attrs: AttributeSet?) : AbstractFloatingView(context, attrs) {

    private lateinit var launcher: Launcher
    private lateinit var icon: FolderIcon
    private val handles = mutableMapOf<ResizeHandle, View>()
    private var active: ResizeHandle? = null
    private var downX = 0f
    private var downY = 0f
    private var snapped = false
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2
    }
    private val tmp = Rect()

    override fun onFinishInflate() {
        super.onFinishInflate()
        handles[ResizeHandle.LEFT] = findViewById(R.id.folder_handle_left)
        handles[ResizeHandle.TOP] = findViewById(R.id.folder_handle_top)
        handles[ResizeHandle.RIGHT] = findViewById(R.id.folder_handle_right)
        handles[ResizeHandle.BOTTOM] = findViewById(R.id.folder_handle_bottom)
        setWillNotDraw(false)
    }

    private fun current(): CellRect = LargeFolderLayout.rectOf(icon)

    private fun cellLayout() = launcher.workspace.getScreenWithId(icon.mInfo.screenId)

    private fun allowed(handle: ResizeHandle): Boolean {
        val layout = cellLayout() ?: return false
        val target = FolderResizeMath.targetFor(current(), handle)
        return FolderResizeMath.isAllowed(target, layout.countX, layout.countY) { x, y ->
            LargeFolderLayout.ownerAt(layout, icon, x, y)
        }
    }

    /** Places the frame over the folder's cells in DragLayer coordinates. */
    fun snapToFolder() {
        val layout = cellLayout() ?: return close(false)
        val r = current()
        layout.cellToRect(r.x, r.y, r.spanX, r.spanY, tmp)
        val scale = launcher.dragLayer.getDescendantRectRelativeToSelf(layout.shortcutsAndWidgets, Rect())
        val lp = layoutParams as DragLayer.LayoutParams
        val origin = IntArray(2)
        launcher.dragLayer.getDescendantCoordRelativeToSelf(layout.shortcutsAndWidgets, origin)
        lp.x = origin[0] + (tmp.left * scale).toInt()
        lp.y = origin[1] + (tmp.top * scale).toInt()
        lp.width = (tmp.width() * scale).toInt()
        lp.height = (tmp.height() * scale).toInt()
        val large = r.spanX > 1
        handles.forEach { (h, v) ->
            v.contentDescription = context.getString(
                if (large) R.string.large_folder_handle_shrink else when (h) {
                    ResizeHandle.LEFT -> R.string.large_folder_handle_left
                    ResizeHandle.TOP -> R.string.large_folder_handle_top
                    ResizeHandle.RIGHT -> R.string.large_folder_handle_right
                    ResizeHandle.BOTTOM -> R.string.large_folder_handle_bottom
                },
            )
            v.alpha = if (allowed(h)) 1f else 0.38f
            v.setOnClickListener { if (allowed(h)) commit(h) }
        }
        requestLayout()
    }

    override fun onControllerInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN && !launcher.dragLayer.isEventOverView(this, ev)) {
            close(true)
            return true
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active = handles.entries.firstOrNull { (_, v) -> v.isHit(ev.x, ev.y) }?.key ?: return false
                downX = ev.x; downY = ev.y; snapped = false
            }
            MotionEvent.ACTION_MOVE -> {
                val h = active ?: return false
                val drag = if (h == ResizeHandle.LEFT || h == ResizeHandle.RIGHT) ev.x - downX else ev.y - downY
                val cellPx = cellLayout()?.cellWidth?.toFloat() ?: return false
                val snap = FolderResizeMath.shouldSnap(drag, cellPx, current(), h) && allowed(h)
                if (snap != snapped) {
                    snapped = snap
                    if (snap) performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
                }
                val offset = if (allowed(h)) drag else FolderResizeMath.rubberBand(drag, cellPx)
                handles[h]?.let { v ->
                    if (h == ResizeHandle.LEFT || h == ResizeHandle.RIGHT) v.translationX = offset else v.translationY = offset
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val h = active ?: return false
                handles.values.forEach { it.animate().translationX(0f).translationY(0f).start() }
                if (snapped && ev.actionMasked == MotionEvent.ACTION_UP) commit(h)
                active = null; snapped = false
            }
        }
        return true
    }

    private fun View.isHit(x: Float, y: Float): Boolean {
        val slop = resources.displayMetrics.density * 16
        return x >= left - slop && x <= right + slop && y >= top - slop && y <= bottom + slop
    }

    private fun commit(handle: ResizeHandle) {
        val target = FolderResizeMath.targetFor(current(), handle)
        if (LargeFolderController.applySize(launcher, icon, target)) {
            post { snapToFolder() }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = active
        if (h != null && snapped) {
            outlinePaint.color = icon.folderBackgroundColorForOutline()
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), outlinePaint)
        }
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        handles.forEach { (h, v) ->
            if (allowed(h)) info.addAction(AccessibilityNodeInfo.AccessibilityAction(v.id, v.contentDescription))
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
        val h = handles.entries.firstOrNull { it.value.id == action }?.key
        if (h != null && allowed(h)) { commit(h); return true }
        return super.performAccessibilityAction(action, arguments)
    }

    override fun handleClose(animate: Boolean) {
        launcher.dragLayer.removeView(this)
    }

    override fun isOfType(type: Int): Boolean = type and TYPE_WIDGET_RESIZE_FRAME != 0

    companion object {
        @JvmStatic
        fun show(launcher: Launcher, icon: FolderIcon) {
            if (!LargeFolderController.canResize(icon) || icon.parent == null) return
            closeAllOpenViews(launcher)
            val frame = launcher.layoutInflater.inflate(R.layout.folder_resize_frame, launcher.dragLayer, false) as FolderResizeFrame
            frame.launcher = launcher
            frame.icon = icon
            frame.contentDescription = launcher.getString(R.string.large_folder_resize_frame, icon.mInfo.title)
            (frame.layoutParams as DragLayer.LayoutParams).customPosition = true
            launcher.dragLayer.addView(frame)
            frame.mIsOpen = true
            frame.post { frame.snapToFolder() }
        }
    }
}
```
Notes:
- The frame reuses `TYPE_WIDGET_RESIZE_FRAME`, so existing "close the resize frame on drag/state change" code also closes it.
- Add `public int folderBackgroundColorForOutline() { return mBackground.getBgColor(); }` to `FolderIcon`.
- If `getDescendantRectRelativeToSelf` returns `float` (it does in Launcher3), the `scale` line must read `val scale = launcher.dragLayer.getDescendantRectRelativeToSelf(layout.shortcutsAndWidgets, Rect())`, with `scale` used as a Float.

- [ ] **Step 4: Trigger from Workspace**

In `Workspace.onDrop`, before `final CellLayout parent = (CellLayout) cell.getParent().getParent();`, add:
```java
            // LC-Note: Large folders v2. Releasing a long-pressed Home folder in place opens the
            // resize frame, like widgets.
            if (droppedOnOriginalCell && cell instanceof FolderIcon folderIcon
                    && LargeFolderController.canResize(folderIcon) && !options.isAccessibleDrag) {
                onCompleteRunnable = () -> {
                    if (!isPageInTransition()) FolderResizeFrame.show(mLauncher, folderIcon);
                };
            }
```
(`options` is `onDrop`'s `DragOptions` parameter. Check its name in the method signature.)

- [ ] **Step 5: Build, unit suite and emulator**

Run: `./gradlew testLawnWithQuickstepExpressiveDebugUnitTest assembleLawnWithQuickstepExpressiveDebug -q`, expect exit 0. On the emulator:
- Long-press a Home folder and release: the frame appears with four handles.
- Drag right past half a cell: a haptic tick, the outline shows, release gives 2x2.
- On the large folder, drag the left handle inward: the folder ends 1x1 in the right column.
- A handle toward an icon is dimmed and rubber-bands.
- Tap outside, Back and Home each close the frame.
- TalkBack on (`adb shell settings put secure enabled_accessibility_services com.google.android.marvin.talkback/.TalkBackService` only if TalkBack is installed; otherwise use `uiautomator dump` to check the node actions and labels).

Note the results in NOTES.md.

- [ ] **Step 6: Commit**

```bash
git add lawnchair/src/app/lawnchair/folder/FolderResizeFrame.kt lawnchair/res/layout/folder_resize_frame.xml lawnchair/res/values/strings.xml src/com/android/launcher3/Workspace.java src/com/android/launcher3/folder/FolderIcon.java artifacts/large-folders-2026-10-01/NOTES.md
git commit -m "feat(folders): resize handles snap Home folders between 1x1 and 2x2

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: `LargeFolderAnimationGeometry`: pure geometry for all animations

**Files:**
- Create: `lawnchair/src/app/lawnchair/folder/LargeFolderAnimationGeometry.kt`
- Modify: `lawnchair/src/app/lawnchair/folder/LargeFolderTile.kt` (expose slot rects through the geometry)
- Test: `tests/expressiveUnit/app/lawnchair/folder/LargeFolderAnimationGeometryTest.kt`

**Interfaces:**
- Produces (Tasks 8–11):
  - `data class Box(val left: Float, val top: Float, val size: Float)` with `centerX`, `centerY`
  - `LargeFolderAnimationGeometry.slotBox(tile: Box, slot: Int, iconFraction: Float = LargeFolderTile.ICON_FRACTION): Box`: the icon box of direct slot 0–3
  - `LargeFolderAnimationGeometry.miniBox(tile: Box, index: Int): Box`: the icon box of preview index 0–3 inside the "more" slot
  - `LargeFolderAnimationGeometry.boxForRank(tile: Box, rank: Int, itemCount: Int): Box?`: where an app at `rank` is drawn on the tile (direct slot, mini, or null if not drawn)
  - `LargeFolderAnimationGeometry.cornerRadius(tile: Box): Float`
  - `LargeFolderAnimationGeometry.lerp(a: Box, b: Box, t: Float): Box` and `fraction(t: Float): Float` (clamped 0..1)

- [ ] **Step 1: Write the failing tests**

```kotlin
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
```

- [ ] **Step 2: Run and confirm failure**

Run: `./gradlew testLawnWithQuickstepExpressiveDebugUnitTest --tests 'app.lawnchair.folder.LargeFolderAnimationGeometryTest' -q`. Expected: compilation FAIL.

- [ ] **Step 3: Implement**

```kotlin
package app.lawnchair.folder

data class Box(val left: Float, val top: Float, val size: Float) {
    val centerX: Float get() = left + size / 2f
    val centerY: Float get() = top + size / 2f
}

/** Large folders v2: where the tile draws each app, shared by drawing and every animation. */
object LargeFolderAnimationGeometry {

    @JvmStatic
    @JvmOverloads
    fun slotBox(tile: Box, slot: Int, iconFraction: Float = LargeFolderTile.ICON_FRACTION): Box {
        val slotSize = tile.size / 2f
        val slotLeft = tile.left + (slot % 2) * slotSize
        val slotTop = tile.top + (slot / 2) * slotSize
        val icon = slotSize * iconFraction
        return Box(slotLeft + (slotSize - icon) / 2f, slotTop + (slotSize - icon) / 2f, icon)
    }

    @JvmStatic
    fun miniBox(tile: Box, index: Int): Box {
        val more = slotBox(tile, LargeFolders.DIRECT_SLOTS - 1)
        val cell = more.size / 2f
        val icon = cell * LargeFolderTile.MINI_ICON_FRACTION
        val left = more.left + (index % 2) * cell + (cell - icon) / 2f
        val top = more.top + (index / 2) * cell + (cell - icon) / 2f
        return Box(left, top, icon)
    }

    @JvmStatic
    fun boxForRank(tile: Box, rank: Int, itemCount: Int): Box? {
        val slots = LargeFolders.slotContents(itemCount)
        val direct = slots.indexOf(rank)
        if (direct >= 0) return slotBox(tile, direct)
        val firstMini = LargeFolders.DIRECT_SLOTS - 1
        val mini = rank - firstMini
        return if (slots.contains(LargeFolders.SLOT_MORE) && mini in 0 until 4) miniBox(tile, mini) else null
    }

    @JvmStatic
    fun cornerRadius(tile: Box): Float = tile.size * LargeFolderTile.CORNER_FRACTION

    @JvmStatic
    fun fraction(t: Float): Float = t.coerceIn(0f, 1f)

    @JvmStatic
    fun lerp(a: Box, b: Box, t: Float): Box {
        val f = fraction(t)
        return Box(a.left + (b.left - a.left) * f, a.top + (b.top - a.top) * f, a.size + (b.size - a.size) * f)
    }
}
```

- [ ] **Step 4: Make LargeFolderTile draw through the geometry**

In `LargeFolderTile.draw`, replace the per-slot math with:
```kotlin
        val box = Box(bounds.left, bounds.top, bounds.width())
        slots.forEachIndexed { slot, content ->
            when {
                content >= 0 -> drawIconIn(canvas, drawables[content], LargeFolderAnimationGeometry.slotBox(box, slot))
                content == LargeFolders.SLOT_MORE -> for (i in 0 until 4) {
                    val d = drawables.getOrNull(LargeFolders.DIRECT_SLOTS - 1 + i) ?: break
                    drawIconIn(canvas, d, LargeFolderAnimationGeometry.miniBox(box, i))
                }
            }
        }
```
and add:
```kotlin
    private fun drawIconIn(canvas: Canvas, drawable: Drawable?, b: Box) {
        drawable ?: return
        drawable.setBounds(b.left.roundToInt(), b.top.roundToInt(), (b.left + b.size).roundToInt(), (b.top + b.size).roundToInt())
        drawable.draw(canvas)
    }

    /** Bounds of the app at [rank] in this tile, or null when the tile doesn't draw it. */
    fun boxForRank(rank: Int): Box? =
        LargeFolderAnimationGeometry.boxForRank(Box(bounds.left, bounds.top, bounds.width()), rank, items.size)

    /** The drawable for [rank], for launch/drop animations. */
    fun drawableForRank(rank: Int): Drawable? = drawables.getOrNull(rank)

    /** Hides one rank while an animation draws it elsewhere (-1 = none). */
    var hiddenRank = -1
```
Skip drawing `content == hiddenRank` and mini index `first + i == hiddenRank`. Delete the old `drawMore`/`drawIcon` helpers.

- [ ] **Step 5: Run and confirm pass**

Run: `./gradlew testLawnWithQuickstepExpressiveDebugUnitTest --tests 'app.lawnchair.folder.*' -q`. Expected: PASS. On the emulator, the tile looks identical to before: screenshot it and compare with the baseline frame.

- [ ] **Step 6: Commit**

```bash
git add lawnchair/src/app/lawnchair/folder/LargeFolderAnimationGeometry.kt lawnchair/src/app/lawnchair/folder/LargeFolderTile.kt tests/expressiveUnit/app/lawnchair/folder/LargeFolderAnimationGeometryTest.kt
git commit -m "refactor(folders): shared tile geometry for drawing and animations

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Open and close animation from the real tile

**Files:**
- Modify: `src/com/android/launcher3/folder/FolderAnimationManager.java` (`createAnimatorSet`, `addPreviewItemAnimators`)
- Modify: `src/com/android/launcher3/folder/FolderIcon.java` (add `getLargeTileBoundsInDragLayer`, `setLargeTileVisible`)

**Interfaces:**
- Consumes: `Box`, `LargeFolderAnimationGeometry.cornerRadius/boxForRank` (Task 7); `FolderIcon.isLarge()`
- Produces: `FolderIcon.getLargeTileBox(): Box` (in FolderIcon coordinates), `FolderIcon.setLargeTileVisible(boolean)`

- [ ] **Step 1: FolderIcon accessors**

```java
    /** LC-Note: Large folders v2. The tile, in this view's coordinates. */
    public app.lawnchair.folder.Box getLargeTileBox() {
        RectF b = getLargeTile().getBounds();
        return new app.lawnchair.folder.Box(b.left, b.top, b.width());
    }

    /** LC-Note: Hides the tile while the folder animates out of it. */
    public void setLargeTileVisible(boolean visible) {
        mBackgroundIsVisible = visible;
        invalidate();
    }

    /** LC-Note: Where the tile draws the app at [rank], in this view's coordinates, or null. */
    @Nullable
    public app.lawnchair.folder.Box getLargeTileBoxForRank(int rank) {
        return getLargeTile().boxForRank(rank);
    }
```

- [ ] **Step 2: Large-folder branch in createAnimatorSet**

At the top of `createAnimatorSet` (after `mIsOpening = isOpening;`), add:
```java
        // LC-Note: Large folders v2. Reveal from the 2x2 tile, not the 1x1 preview circle.
        if (mFolderIcon.isLarge()) {
            return createLargeFolderAnimatorSet();
        }
```
Then add the method. It mirrors the upstream code with tile geometry: the start rect is the tile box scaled into DragLayer space, the radius is the tile radius, and items animate from their tile slots.
```java
    private AnimatorSet createLargeFolderAnimatorSet() {
        final BaseDragLayer.LayoutParams lp = (BaseDragLayer.LayoutParams) mFolder.getLayoutParams();
        final Rect iconPos = new Rect();
        float s = mFolder.mActivityContext.getDragLayer().getDescendantRectRelativeToSelf(mFolderIcon, iconPos);
        app.lawnchair.folder.Box tile = mFolderIcon.getLargeTileBox();
        float tileLeft = iconPos.left + tile.getLeft() * s;
        float tileTop = iconPos.top + tile.getTop() * s;
        float tileSize = tile.getSize() * s;
        float tileRadius = app.lawnchair.folder.LargeFolderAnimationGeometry.cornerRadius(tile) * s;

        // Folder panel starts with its top-left on the tile's top-left, clipped to the tile.
        float xDistance = tileLeft - lp.x;
        float yDistance = tileTop - lp.y;
        Rect startRect = new Rect(0, 0, Math.round(tileSize), Math.round(tileSize));
        Rect endRect = new Rect(0, 0, lp.width, lp.height);
        float finalRadius = mFolderBackground.getCornerRadius();

        int initialColor = LawnchairUtilsKt.resolveFolderPreviewColor(mContext);
        int finalColor = LawnchairUtilsKt.resolveFolderBackgroundColor(mContext);
        mFolderBackground.mutate();
        mFolderBackground.setColor(mIsOpening ? initialColor : finalColor);
        mFolder.setPivotX(0);
        mFolder.setPivotY(0);
        mFolder.mContent.setScaleX(1f);
        mFolder.mContent.setScaleY(1f);
        mFolder.mFooter.setScaleX(1f);
        mFolder.mFooter.setScaleY(1f);

        AnimatorSet a = new AnimatorSet();
        mBgColorAnimator = getAnimator(mFolderBackground, "color", initialColor, finalColor);
        play(a, mBgColorAnimator);
        play(a, getAnimator(mFolder, View.TRANSLATION_X, xDistance, 0f));
        play(a, getAnimator(mFolder, View.TRANSLATION_Y, yDistance, 0f));

        ShapeDelegate shape = ThemeManager.INSTANCE.get(mContext).getFolderShape();
        play(a, new app.lawnchair.folder.RoundRectRevealAnimator(mFolder, startRect, endRect,
                tileRadius, finalRadius, !mIsOpening).create());
        int page = mIsOpening ? mContent.getCurrentPage() : mContent.getDestinationPage();
        int left = page * lp.width;
        Rect contentStart = new Rect(left, 0, left + Math.round(tileSize), Math.round(tileSize));
        Rect contentEnd = new Rect(left, 0, left + lp.width, lp.height);
        play(a, new app.lawnchair.folder.RoundRectRevealAnimator(mFolder.getContent(), contentStart,
                contentEnd, tileRadius, finalRadius, !mIsOpening).create());

        // Footer and title fade, like upstream, but over the whole duration.
        play(a, getAnimator(mFolder.mFooter, ALPHA, 0, 1f));
        mFolder.getFolderName().setAlpha(mIsOpening ? 0f : 1f);
        play(a, getAnimator(mFolder.getFolderName(), View.ALPHA, 0, 1));

        addLargeFolderItemAnimators(a, iconPos, s);

        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                mFolderIcon.setLargeTileVisible(false);
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                mFolder.setTranslationX(0f);
                mFolder.setTranslationY(0f);
                mFolder.getFolderName().setAlpha(1f);
                if (!mIsOpening) mFolderIcon.setLargeTileVisible(true);
            }
        });
        for (Animator anim : a.getChildAnimations()) anim.setDuration(mDuration);
        return a;
    }

    /** Each item flies between its tile slot and its panel cell; items not on the tile fade. */
    private void addLargeFolderItemAnimators(AnimatorSet a, Rect iconPos, float s) {
        List<View> items = mFolder.getItemsOnPage(mFolder.mContent.getCurrentPage());
        int count = mFolder.getItemCount();
        for (View v : items) {
            BubbleTextView btv = getBubbleTextView(v);
            int rank = ((ItemInfo) v.getTag()).rank;
            app.lawnchair.folder.Box onTile = mFolderIcon.getLargeTileBoxForRank(rank);
            if (onTile == null) {
                play(a, getAnimator(v, View.ALPHA, 0f, 1f));
                continue;
            }
            Rect cell = new Rect();
            mFolder.mActivityContext.getDragLayer().getDescendantRectRelativeToSelf(btv, cell);
            Rect iconBounds = new Rect();
            btv.getIconBounds(iconBounds);
            float iconLeft = cell.left + iconBounds.left;
            float iconTop = cell.top + iconBounds.top;
            float startScale = onTile.getSize() * s / iconBounds.width();
            float dx = iconPos.left + onTile.getLeft() * s - iconLeft;
            float dy = iconPos.top + onTile.getTop() * s - iconTop;
            btv.setPivotX(iconBounds.left);
            btv.setPivotY(iconBounds.top);
            btv.setTextVisibility(!mIsOpening);
            play(a, getAnimator(btv, View.TRANSLATION_X, dx, 0f));
            play(a, getAnimator(btv, View.TRANSLATION_Y, dy, 0f));
            play(a, getAnimator(btv, SCALE_PROPERTY, startScale, 1f));
            ObjectAnimator text = btv.createTextAlphaAnimator(mIsOpening);
            play(a, text);
            a.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    btv.setTranslationX(0f);
                    btv.setTranslationY(0f);
                    btv.setScaleX(1f);
                    btv.setScaleY(1f);
                    btv.setTextVisibility(true);
                }
            });
        }
    }
```
`getAnimator(view, prop, from, to)` already swaps from/to when closing, the same as the upstream calls. Check that its signature handles `mIsOpening` (it does for the existing translation calls).

- [ ] **Step 3: RoundRectRevealAnimator (the corner radius animates from tile to panel)**

Create `lawnchair/src/app/lawnchair/folder/RoundRectRevealAnimator.kt`:
```kotlin
package app.lawnchair.folder

import android.animation.ValueAnimator
import android.graphics.Outline
import android.graphics.Rect
import android.view.View
import android.view.ViewOutlineProvider

/** Clips [view] to a rounded rect that moves from [start] (radius [r0]) to [end] (radius [r1]). */
class RoundRectRevealAnimator(
    private val view: View,
    private val start: Rect,
    private val end: Rect,
    private val r0: Float,
    private val r1: Float,
    private val reversed: Boolean,
) {
    fun create(): ValueAnimator {
        val cur = Rect()
        var radius = r0
        val provider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) = outline.setRoundRect(cur, radius)
        }
        return ValueAnimator.ofFloat(if (reversed) 1f else 0f, if (reversed) 0f else 1f).apply {
            addUpdateListener {
                val t = it.animatedValue as Float
                cur.set(
                    (start.left + (end.left - start.left) * t).toInt(),
                    (start.top + (end.top - start.top) * t).toInt(),
                    (start.right + (end.right - start.right) * t).toInt(),
                    (start.bottom + (end.bottom - start.bottom) * t).toInt(),
                )
                radius = r0 + (r1 - r0) * t
                view.invalidateOutline()
            }
            doOnStartEnd(
                onStart = { view.outlineProvider = provider; view.clipToOutline = true },
                onEnd = { view.clipToOutline = false; view.outlineProvider = ViewOutlineProvider.BACKGROUND },
            )
        }
    }

    private fun ValueAnimator.doOnStartEnd(onStart: () -> Unit, onEnd: () -> Unit) {
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationStart(a: android.animation.Animator) = onStart()
            override fun onAnimationEnd(a: android.animation.Animator) = onEnd()
        })
    }
}
```
If `mFolder`/`mContent` already uses an outline provider for its background, store the previous provider in `onStart` and restore it in `onEnd` instead of `BACKGROUND`.

- [ ] **Step 4: Emulator comparison**

Record `open` and `close` again (Task 0 step 4 with suffix `-after`) and step through the frames:
- **Open:** frame 0 shows the panel exactly over the tile (same corners), the 4 apps start on their slots, and no tile is visible underneath.
- **Close:** the last frame matches the tile and the tile is visible in the next frame with no gap.

Write the frame numbers in NOTES.md. If either is off by more than 2 px or 1 frame, fix the offsets and repeat before moving on.

- [ ] **Step 5: Unit suite and commit**

```bash
./gradlew testLawnWithQuickstepExpressiveDebugUnitTest -q
git add src/com/android/launcher3/folder/FolderAnimationManager.java src/com/android/launcher3/folder/FolderIcon.java lawnchair/src/app/lawnchair/folder/RoundRectRevealAnimator.kt artifacts/large-folders-2026-10-01/NOTES.md
git commit -m "feat(folders): large folders open from and close into their tile

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Launch from the tile slot and return to it

**Files:**
- Modify: `src/com/android/launcher3/views/FloatingIconView.java:252-277` (`getLocationBoundsForView`) and `getIconResult` (drawable source)
- Modify: `src/com/android/launcher3/folder/FolderIcon.java` (`performClick` records the launching rank)
- Modify: `src/com/android/launcher3/Launcher.java:2414` (`getFirstHomeElementForAppClose`)

**Interfaces:**
- Consumes: `FolderIcon.getLargeTileBoxForRank(int)` (Task 8), `LargeFolderTile.drawableForRank`, `hiddenRank` (Task 7)
- Produces: `FolderIcon.setLaunchingRank(int)`, `FolderIcon.getLaunchingRank(): int` (-1 if none), `FolderIcon.getLaunchingDrawable(): Drawable?`

- [ ] **Step 1: FolderIcon remembers which slot is launching**

In `performClick`'s large branch, before `ItemClickHandler.onClickAppShortcut(this, app, launcher)`, call `setLaunchingRank(app.rank);`. Add:
```java
    private int mLaunchingRank = -1;

    /** LC-Note: Large folders v2. The rank whose app is launching from or returning to the tile. */
    public void setLaunchingRank(int rank) {
        mLaunchingRank = rank;
        if (mLargeTile != null) {
            mLargeTile.setHiddenRank(-1);
        }
    }

    public int getLaunchingRank() { return mLaunchingRank; }

    @Nullable
    public Drawable getLaunchingDrawable() {
        return mLaunchingRank >= 0 && mLargeTile != null ? mLargeTile.drawableForRank(mLaunchingRank) : null;
    }

    /** LC-Note: Hides the launching slot while FloatingIconView draws it. */
    public void setLaunchingSlotVisible(boolean visible) {
        if (mLargeTile != null) mLargeTile.setHiddenRank(visible ? -1 : mLaunchingRank);
        invalidate();
    }
```

- [ ] **Step 2: FloatingIconView uses the slot**

In `getLocationBoundsForView`, change the FolderIcon branch to:
```java
        } else if (v instanceof FolderIcon fi) {
            app.lawnchair.folder.Box slot = fi.isLarge() && fi.getLaunchingRank() >= 0
                    ? fi.getLargeTileBoxForRank(fi.getLaunchingRank()) : null;
            if (slot != null) {
                // LC-Note: Large folders v2. Launch from / return to the app's slot on the tile.
                outViewBounds.set(Math.round(slot.getLeft()), Math.round(slot.getTop()),
                        Math.round(slot.getLeft() + slot.getSize()), Math.round(slot.getTop() + slot.getSize()));
            } else {
                fi.getPreviewBounds(outViewBounds);
            }
        }
```
In `getIconResult`, where `drawable` is chosen for non-BTV views, add a first case:
```java
        if (originalView instanceof FolderIcon fi && fi.getLaunchingDrawable() != null) {
            drawable = fi.getLaunchingDrawable().getConstantState().newDrawable().mutate(); // LC-Note
        } else …
```
Where FloatingIconView hides the original view (`setIconAndDotVisible(originalView, false)`), add for a launching large folder: `fi.setLaunchingSlotVisible(false)`, and `true` where it restores visibility. Find both with `grep -n "setIconAndDotVisible" src/com/android/launcher3/views/FloatingIconView.java`. A large folder must keep the rest of the tile visible, so for it skip hiding the whole view.

- [ ] **Step 3: Return Home targets the same slot**

In `Launcher.getFirstHomeElementForAppClose`, after `View match = visibleContainer.getFirstMatch(…)` (assign the result to a local first), add:
```java
        // LC-Note: Large folders v2. Closing an app that lives in a large folder animates into its
        // slot on the tile, or the "more" slot for apps further back.
        if (match instanceof FolderIcon fi && fi.isLarge()) {
            ItemInfo target = null;
            for (ItemInfo i : fi.getFolder().getInfo().getContents()) {
                if (preferredItem.test(i) || packageAndUserAndApp.test(i)) { target = i; break; }
            }
            int rank = target == null ? -1 : target.rank;
            if (rank >= 0 && fi.getLargeTileBoxForRank(rank) == null) {
                rank = app.lawnchair.folder.LargeFolders.DIRECT_SLOTS - 1; // the "more" slot
            }
            fi.setLaunchingRank(rank);
        }
        return match;
```

- [ ] **Step 4: Emulator**

Record `launch` (tap the slot-1 app, wait, press Home) as the `-after` evidence:
- The launch starts from the slot-1 icon's exact bounds, the other tile icons stay visible, and the slot is hidden only while the floating icon is up.
- Returning Home lands in slot 1.
- Repeat with an app at rank 5 opened from the panel: return lands in the "more" slot.

Note the results in NOTES.md.

- [ ] **Step 5: Unit suite and commit**

```bash
./gradlew testLawnWithQuickstepExpressiveDebugUnitTest -q
git add src/com/android/launcher3/views/FloatingIconView.java src/com/android/launcher3/folder/FolderIcon.java src/com/android/launcher3/Launcher.java artifacts/large-folders-2026-10-01/NOTES.md
git commit -m "feat(folders): apps launch from and return to their large-folder slot

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: Animated size change

**Files:**
- Modify: `src/com/android/launcher3/folder/FolderIcon.java` (`onSizeModeChanged` → animated, `dispatchDraw` large/1x1 morph)
- Modify: `lawnchair/src/app/lawnchair/folder/LargeFolderController.kt` (`applySize` passes the old cell rect)

**Interfaces:**
- Consumes: `LargeFolderAnimationGeometry.lerp`, `Box` (Task 7)
- Produces: `FolderIcon.animateSizeChange(fromWidthPx: int, fromHeightPx: int, fromLeftPx: int, fromTopPx: int)`

- [ ] **Step 1: Capture the old visual bounds in applySize**

In `LargeFolderController.applySize`, before `markCellsAsUnoccupiedForView`, capture:
```kotlin
        val oldBounds = android.graphics.Rect()
        icon.getWorkspaceVisualDragBounds(oldBounds)
        oldBounds.offset(icon.left, icon.top)
```
After `cellLayout.markCellsAsOccupiedForView(icon)`, replace `icon.onSizeModeChanged()` with `icon.animateSizeChange(oldBounds)`.

- [ ] **Step 2: Morph in FolderIcon**

```java
    @Nullable private ValueAnimator mSizeAnimator;
    private final Rect mSizeFrom = new Rect();
    private float mSizeProgress = 1f;

    /** LC-Note: Large folders v2. Morphs between the 1x1 circle and the 2x2 tile. */
    public void animateSizeChange(Rect oldBoundsInParent) {
        onSizeModeChanged();
        mSizeFrom.set(oldBoundsInParent);
        if (mSizeAnimator != null) mSizeAnimator.cancel();
        mSizeAnimator = ValueAnimator.ofFloat(0f, 1f);
        mSizeAnimator.setInterpolator(Interpolators.EMPHASIZED);
        mSizeAnimator.setDuration(300);
        mSizeAnimator.addUpdateListener(a -> {
            mSizeProgress = (float) a.getAnimatedValue();
            invalidate();
        });
        mSizeAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mSizeProgress = 1f;
                mSizeAnimator = null;
                invalidate();
            }
        });
        mSizeProgress = 0f;
        post(mSizeAnimator::start); // after the new layout pass, so the end bounds are measured
    }
```
In `dispatchDraw`, before drawing the tile or the 1x1 preview, when `mSizeProgress < 1f`:
```java
        if (mSizeProgress < 1f) {
            // LC-Note: Draw at the end size, transformed so it starts at the old bounds.
            Rect to = new Rect();
            getWorkspaceVisualDragBounds(to);
            to.offset(getLeft(), getTop());
            float sx = mSizeFrom.width() / (float) to.width();
            float sy = mSizeFrom.height() / (float) to.height();
            float t = mSizeProgress;
            float scaleX = sx + (1f - sx) * t;
            float scaleY = sy + (1f - sy) * t;
            float dx = (mSizeFrom.centerX() - to.centerX()) * (1f - t);
            float dy = (mSizeFrom.centerY() - to.centerY()) * (1f - t);
            canvas.save();
            canvas.translate(dx, dy);
            canvas.scale(scaleX, scaleY, to.centerX() - getLeft(), to.centerY() - getTop());
        }
```
After the tile/preview drawing (both branches), add `if (mSizeProgress < 1f) canvas.restore();`. The label is a child view, so offset it with `mFolderName.setTranslationY(dy)` in the update listener and reset it to 0 at the end. `ValueAnimator` honours the global duration scale, so "Remove animations" gives an instant jump.

Use `Interpolators.EMPHASIZED`; it exists in `com.android.app.animation.Interpolators`. If it doesn't, use `Interpolators.STANDARD`.

- [ ] **Step 3: Emulator**

Record `resize` with the footer button and with the handles, in both directions:
- The tile grows smoothly from the circle with no flash.
- The label moves with it, and neighbours don't move.
- With "Remove animations" (`adb shell settings put global animator_duration_scale 0`) the change is instant and correct. Reset the setting to 1 afterwards.

Note the results in NOTES.md.

- [ ] **Step 4: Unit suite and commit**

```bash
./gradlew testLawnWithQuickstepExpressiveDebugUnitTest -q
git add src/com/android/launcher3/folder/FolderIcon.java lawnchair/src/app/lawnchair/folder/LargeFolderController.kt artifacts/large-folders-2026-10-01/NOTES.md
git commit -m "feat(folders): animate switching between 1x1 and 2x2

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: Drag preview, accept spring and drop into the slot; final verification

**Files:**
- Modify: `src/com/android/launcher3/graphics/DragPreviewProvider.java` (`createDrawable`, ~89)
- Modify: `src/com/android/launcher3/folder/FolderIcon.java` (`onDragEnter`/`onDragExit` spring, `onDrop` target via `getLocalCenterForIndex`)
- Modify: `lawnchair/src/app/lawnchair/folder/LargeFolderTile.kt` (slot highlight)
- Modify: `docs/PIXEL_PARITY.md`

**Interfaces:**
- Consumes: `Box`, `LargeFolderAnimationGeometry.boxForRank` (Task 7), `LargeFolderTile.hiddenRank`
- Produces: `LargeFolderTile.highlightRank: Int` (-1 none)

- [ ] **Step 1: Drag preview is the real tile**

In `DragPreviewProvider.createDrawable`, at the top:
```java
        // LC-Note: Large folders v2. Drag the 2x2 tile at its real size, not the whole cell area.
        if (mView instanceof FolderIcon fi && fi.isLarge()) {
            Rect b = new Rect();
            fi.getWorkspaceVisualDragBounds(b);
            Bitmap bmp = BitmapRenderer.createHardwareBitmap(b.width(), b.height(), c -> {
                c.translate(-b.left, -b.top);
                fi.draw(c);
            });
            return new FastBitmapDrawable(bmp);
        }
```
Check `getScaleAndPosition(Drawable, int[])` positions the drawable using `getWorkspaceVisualDragBounds`. If it uses the view's full bounds, add a matching LC-Note branch that offsets by `b.left/b.top`.

- [ ] **Step 2: Accept spring and slot highlight**

Replace the instant `setAcceptScale(LARGE_ACCEPT_SCALE)` in `onDragEnter` and the reset in `onDragExit` with a spring:
```java
    @Nullable private SpringAnimation mAcceptSpring;

    private void springAcceptScale(float target) {
        LargeFolderTile tile = getLargeTile();
        if (mAcceptSpring == null) {
            mAcceptSpring = new SpringAnimation(new FloatValueHolder(tile.getAcceptScale()))
                    .setSpring(new SpringForce().setStiffness(SpringForce.STIFFNESS_MEDIUM)
                            .setDampingRatio(SpringForce.DAMPING_RATIO_LOW_BOUNCY));
            mAcceptSpring.addUpdateListener((a, v, vel) -> { tile.setAcceptScale(v); invalidate(); });
        }
        mAcceptSpring.animateToFinalPosition(target);
    }
```
In `onDragEnter` (large branch): `springAcceptScale(LARGE_ACCEPT_SCALE); getLargeTile().setHighlightRank(Math.min(mInfo.getContents().size(), LargeFolders.DIRECT_SLOTS - 1));`. In `onDragExit`: `springAcceptScale(1f); getLargeTile().setHighlightRank(-1);`. Imports: `androidx.dynamicanimation.animation.{SpringAnimation, SpringForce, FloatValueHolder}`. With duration scale 0, `SpringAnimation` jumps, which is acceptable.

In `LargeFolderTile.draw`, before the icons, draw the highlight:
```kotlin
        if (highlightRank >= 0) {
            val hb = LargeFolderAnimationGeometry.slotBox(box, minOf(highlightRank, LargeFolders.DIRECT_SLOTS - 1), 1f)
            paint.color = ColorUtils.setAlphaComponent(backgroundColor, 0xFF)
            canvas.drawRoundRect(hb.left, hb.top, hb.left + hb.size, hb.top + hb.size, hb.size * CORNER_FRACTION, hb.size * CORNER_FRACTION, paint)
        }
```
with `var highlightRank = -1`.

- [ ] **Step 3: Drop into the real slot**

In `FolderIcon.getLocalCenterForIndex`, at the top:
```java
        // LC-Note: Large folders v2. Drop into the tile slot the new app will take.
        if (isLarge()) {
            app.lawnchair.folder.Box b = getLargeTile().boxForRankAfterAdd(index);
            center[0] = Math.round(b.getCenterX());
            center[1] = Math.round(b.getCenterY());
            return b.getSize() / mPreviewItemManager.getIntrinsicIconSize();
        }
```
Add to `LargeFolderTile`:
```kotlin
    /** Where an app added at [rank] will be drawn (the "more" slot if past the direct slots). */
    fun boxForRankAfterAdd(rank: Int): Box {
        val box = Box(bounds.left, bounds.top, bounds.width())
        return LargeFolderAnimationGeometry.boxForRank(box, rank, items.size + 1)
            ?: LargeFolderAnimationGeometry.slotBox(box, LargeFolders.DIRECT_SLOTS - 1)
    }
```
In `onDrop`, for large folders the `index >= MAX_NUM_ITEMS_IN_PREVIEW` branch must still animate to the slot: set `finalAlpha = 1f` when `isLarge()`. At the end of the drop animation, call `getLargeTile().setItems(mInfo.getContents()); invalidate();` (in the existing completion lambda) so the tile shows the new app in the same frame the drag view disappears.

- [ ] **Step 4: Full verification**

Run:
```bash
./gradlew testLawnWithQuickstepExpressiveDebugUnitTest -q && python3 -m unittest discover -s ci/tests 2>&1 | tail -3
./gradlew assembleLawnWithQuickstepExpressiveDebug -q && adb -s emulator-5590 install -r build/outputs/apk/lawnWithQuickstepExpressive/debug/*.apk
```
Expected: all unit tests pass (426 + new), `Ran 291 tests … OK`, install `Success`. Then on the emulator:
1. Record all five interactions `-after` and compare with the baseline, frame by frame. Each BASELINE problem gets a "fixed at frame N" line in NOTES.md. Janky frames from framestats must be no worse than the baseline.
2. Overlap checks (Task 3 step 5 a–e) again on the final build. Restart and check the overlap survives.
3. "Remove animations": `adb shell settings put global animator_duration_scale 0`. Run open, close, resize and drop: the final state must be correct (tile visible, no hidden icons). Set it back to `1`.
4. TalkBack labels and actions on the frame handles and the footer button (`uiautomator dump`).
5. Foldable/landscape: start the `Pixel_Fold`-type AVD if available (`emulator -list-avds`), or rotate (`adb shell settings put system user_rotation 1` with auto-rotate off). Check grow, open and launch. If it's wrong and can't be fixed in this branch, gate `LargeFolderController.canResize` on `!deviceProfile.isTwoPanels && !deviceProfile.isLandscape`, record the limitation in NOTES.md, and tell the user.

- [ ] **Step 5: Parity ledger**

Add a "Large Home folders v2" section at the top of `docs/PIXEL_PARITY.md`. Cover the overlap over widgets (folder wins touches; widgets never move), the resize handles, and the five fixed animations with their evidence (frame notes, tests). List any remaining limits.

- [ ] **Step 6: Commit**

```bash
git add src/com/android/launcher3/graphics/DragPreviewProvider.java src/com/android/launcher3/folder/FolderIcon.java lawnchair/src/app/lawnchair/folder/LargeFolderTile.kt docs/PIXEL_PARITY.md artifacts/large-folders-2026-10-01/NOTES.md
git commit -m "feat(folders): real-size drag preview, spring accept and drop into the slot

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 7: Hand back for merge**

Do not merge on your own. Report the test counts, the NOTES.md summary and any limitation from step 4.5 to the user. The merge into `codex/pixel-parity` (fast-forward from the worktree branch) happens after their go-ahead, and a daily QA run ships it.
