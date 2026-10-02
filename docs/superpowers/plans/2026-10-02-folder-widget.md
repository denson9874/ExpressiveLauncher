# Folder widget Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the 4.0.3 large-folder mode with a Hanks-style Folder widget: a resizable, scrolling,
centered app grid on Home that behaves like a widget and keeps folder semantics.

**Architecture:** A Folder widget is a desktop folder row with a new option bit
(`FolderInfo.FLAG_FOLDER_WIDGET`) and a span of at least two cells. It is bound as `FolderWidgetView`
(a `FolderIcon` subclass) hosting `FolderWidgetPanel` (header + `RecyclerView` grid of real
`BubbleTextView`s). Placement, resize and drag reuse Launcher3's widget mechanics; per-widget style
lives in Expressive's Room database. The 4.0.2/4.0.3 large-folder commits are reverted first.

**Tech Stack:** Java/Kotlin Launcher3 (Lawnchair 16 fork, Android 17), Views + RecyclerView for the
widget, Jetpack Compose for sheets, Room, Robolectric + JUnit4 + Truth.

**Spec:** `docs/superpowers/specs/2026-10-02-folder-widget-design.md`

## Global Constraints

- Work only in `~/ExpressiveWorktrees/folder-widget` (branch `claude/folder-widget`). Do not edit
  `~/Developer/ExpressiveLauncher` (the release checkout) before the merge in Task 15.
- JDK: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`.
- Unit tests: `./gradlew testLawnWithQuickstepExpressiveDebugUnitTest --tests '<filter>'`; the full
  suite without a filter. CI contracts: `python3 -m unittest discover -s ci/tests`.
- Debug APK: `./gradlew assembleLawnWithQuickstepExpressiveDebug` →
  `build/outputs/apk/lawnWithQuickstepExpressive/debug/*.apk`, package
  `dev.launcher.expressive.l3.debug`.
- New Kotlin code: `lawnchair/src/app/lawnchair/folder/widget/` (UI sheets in `…/widget/ui/`); tests:
  `tests/expressiveUnit/app/lawnchair/folder/widget/`. Changes inside AOSP files carry `// LC-Note:`.
- Flag: `FolderInfo.FLAG_FOLDER_WIDGET = 0x00100000`.
- Size: at least two cells (1x2, 2x1 or larger), at most the grid; default 2x2.
- Auto columns = `clamp(round(spanX × 1.5), 1, 6)`; per-widget override 2–6.
- Icon size = `min(Home icon size, 0.75 × column width) × icon scale`; icon scale 70–110 %.
- Labels are hidden automatically when one row with a label does not fit.
- Resize: as `AppWidgetResizeFrame` — threshold `0.66` of a cell, `SNAP_DURATION_MS = 150`,
  `RESIZE_TRANSITION_DURATION_MS = 150`.
- Defaults: app names on, header on, name below off; background color `folderColor`, opacity
  `folderBackgroundOpacity`, corner radius `android.R.dimen.system_app_widget_background_radius`.
- Free: Folder widgets, resize, scrolling, columns, names, header, name below. Pro (`ProManager.isPro`):
  background color, background opacity, corner radius, icon size.
- Never delete a folder or its apps during migration or placement.
- Devices: Pixel 8 Pro `43191FDJG0017A` (its Home is `dev.launcher.expressive.l3` 2.0.8 — restore it to
  `dev.launcher.expressive.l3/app.lawnchair.LawnchairLauncher` after every session) and
  `emulator-5590`. In zsh call adb through wrapper scripts (variables don't word-split). Never clear
  data or uninstall the user's apps. Keep recordings and screenshots out of git.

## Review Focus

1. **All apps of a widget uninstalled at runtime** → the widget stays and shows "Add apps"; the adapter
   handles 0 items. Tests: Task 5 `emptyAdapter_showsAddAppsButton`, Task 2 `folderWidget_neverDissolves`.
2. **Tiny panel at large font or display size** → no negative sizes, ≥ 1 column, labels hidden before
   clipping. Test: Task 4 `tinyPanel_neverNegativeAndHidesLabels`.
3. **Two migrated widgets competing for one free area / every page full** → the second goes elsewhere,
   a full Home gets a new page, nothing is deleted. Tests: Task 3 `secondWidget_seesFirstWidgetsCells`,
   `fullPages_useNewScreen`.
4. **Pro deactivated after styling** → Pro styling stops applying (defaults show) but is kept. Test:
   Task 4 `freeUser_getsDefaultsForProFields`.
5. **A resize step that would leave one cell** → refused. Test: Task 7 `step_refusesSingleCell`.

---

### Task 1: Retire the 4.0.2/4.0.3 large-folder mode

**Files:** reverts every file touched by the large-folder commits; keeps `docs/PIXEL_PARITY.md`.

**Interfaces:**
- Produces: the pre-large-folder baseline. `LargeFolders`, `LargeFolderOverlap`, `LargeFolderLayout`,
  `LargeFolderTile`, `LargeFolderController`, `FolderResizeFrame`, `FolderResizeMath`,
  `LargeFolderAnimationGeometry`, `RoundRectRevealAnimator` and their tests are gone. Folders load as
  1x1 (`WorkspaceItemProcessor.processFolderOrAppPair` forces `spanX = spanY = 1`); a stored 2x2 row
  is not deleted.

- [ ] **Step 1: Prepare the worktree for builds**

```bash
cd ~/ExpressiveWorktrees/folder-widget
cp ~/Developer/ExpressiveLauncher/local.properties .
rmdir platform_frameworks_libs_systemui 2>/dev/null
cp -Rc ~/Developer/ExpressiveLauncher/platform_frameworks_libs_systemui platform_frameworks_libs_systemui
rm -f platform_frameworks_libs_systemui/.git
```

- [ ] **Step 2: Revert the large-folder code commits, newest first**

```bash
git revert --no-commit 95bba24 0027a97 5fa84b2 44c41be 8cbf9a9 88fa8ed ce0545f 9507967 \
  b40e8aa d6f55b6 793883c b5ad8d0 6b29f69 06dda68 2dc3957 fc4b6d8
git checkout HEAD -- docs/PIXEL_PARITY.md
```
Expected: no conflicts (`git status` shows no "both modified").

- [ ] **Step 3: Verify nothing refers to the old mode**

Run: `rg -n "LargeFolder|FolderResizeFrame|FolderResizeMath|RoundRectRevealAnimator|large_folder_" src lawnchair/src lawnchair/res quickstep/src tests`
Expected: no matches.

- [ ] **Step 4: Build and run the full unit suite**

Run: `./gradlew testLawnWithQuickstepExpressiveDebugUnitTest assembleLawnWithQuickstepExpressiveDebug`
Expected: BUILD SUCCESSFUL; the suite shrinks by exactly the tests of `LargeFoldersTest`,
`LargeFolderOverlapTest`, `FolderResizeMathTest` and `LargeFolderAnimationGeometryTest`.

- [ ] **Step 5: Commit**

```bash
git commit -m "revert: retire the 4.0.2/4.0.3 large-folder mode for the Folder widget"
```

---

### Task 2: Folder-widget flag and "never dissolve" guards

**Files:**
- Modify: `src/com/android/launcher3/model/data/FolderInfo.java` (flag constant)
- Create: `lawnchair/src/app/lawnchair/folder/widget/FolderWidgets.kt`
- Modify: `src/com/android/launcher3/folder/Folder.java` — every `getItemCount() <= 1` branch that
  dissolves (`replaceFolderWithFinalItem`, `mDeleteFolderOnDropCompleted = true`) or closes the folder,
  including the one in `removeFolderContent`, also requires `FolderWidgets.canDissolve(mInfo)`
- Modify: `src/com/android/launcher3/model/ModelDbController.java` (`deleteEmptyFolders` uses
  `FolderWidgets.emptyFoldersSelection()`)
- Test: `tests/expressiveUnit/app/lawnchair/folder/widget/FolderWidgetsTest.kt`

**Interfaces:**
- Produces:
  - `FolderInfo.FLAG_FOLDER_WIDGET: int = 0x00100000`
  - `object FolderWidgets` (all `@JvmStatic`): `const val MIN_CELLS = 2`, `const val DEFAULT_SPAN = 2`,
    `fun isFolderWidget(info: ItemInfo?): Boolean`,
    `fun wantsWidget(container: Int, options: Int, spanX: Int, spanY: Int): Boolean` (desktop and
    (flag or `spanX * spanY > 1`)), `fun isValidSpan(spanX: Int, spanY: Int): Boolean`
    (`spanX ≥ 1 && spanY ≥ 1 && spanX * spanY ≥ MIN_CELLS`), `fun canDissolve(info: FolderInfo): Boolean`,
    `fun acceptsDrop(item: ItemInfo): Boolean` (application, shortcut, deep shortcut),
    `fun emptyFoldersSelection(): String`

- [ ] **Step 1: Write the failing tests**

```kotlin
class FolderWidgetsTest {
    private fun folder(options: Int = 0) = FolderInfo().apply { this.options = options }

    @Test fun flag_marksAFolderWidget() {
        assertThat(FolderWidgets.isFolderWidget(folder(FolderInfo.FLAG_FOLDER_WIDGET))).isTrue()
        assertThat(FolderWidgets.isFolderWidget(folder())).isFalse()
        assertThat(FolderWidgets.isFolderWidget(null)).isFalse()
        assertThat(FolderWidgets.isFolderWidget(WorkspaceItemInfo())).isFalse()
    }

    @Test fun wantsWidget_onlyOnDesktopWithFlagOrBigSpan() {
        val desk = Favorites.CONTAINER_DESKTOP
        assertThat(FolderWidgets.wantsWidget(desk, 0, 2, 2)).isTrue()
        assertThat(FolderWidgets.wantsWidget(desk, FolderInfo.FLAG_FOLDER_WIDGET, 1, 1)).isTrue()
        assertThat(FolderWidgets.wantsWidget(desk, 0, 1, 1)).isFalse()
        assertThat(FolderWidgets.wantsWidget(Favorites.CONTAINER_HOTSEAT, 0, 2, 2)).isFalse()
    }

    @Test fun span_needsAtLeastTwoCells() {
        assertThat(FolderWidgets.isValidSpan(1, 1)).isFalse()
        assertThat(FolderWidgets.isValidSpan(2, 1)).isTrue()
        assertThat(FolderWidgets.isValidSpan(1, 2)).isTrue()
        assertThat(FolderWidgets.isValidSpan(0, 3)).isFalse()
    }

    @Test fun folderWidget_neverDissolves() {
        assertThat(FolderWidgets.canDissolve(folder(FolderInfo.FLAG_FOLDER_WIDGET))).isFalse()
        assertThat(FolderWidgets.canDissolve(folder())).isTrue()
    }

    @Test fun acceptsDrop_appsAndShortcutsOnly() {
        fun item(type: Int) = WorkspaceItemInfo().apply { itemType = type }
        assertThat(FolderWidgets.acceptsDrop(item(Favorites.ITEM_TYPE_APPLICATION))).isTrue()
        assertThat(FolderWidgets.acceptsDrop(item(Favorites.ITEM_TYPE_DEEP_SHORTCUT))).isTrue()
        assertThat(FolderWidgets.acceptsDrop(item(Favorites.ITEM_TYPE_SHORTCUT))).isTrue()
        assertThat(FolderWidgets.acceptsDrop(FolderInfo())).isFalse()
        assertThat(FolderWidgets.acceptsDrop(LauncherAppWidgetInfo())).isFalse()
    }

    @Test fun emptyFolderCleanup_skipsFolderWidgets() {
        val sql = FolderWidgets.emptyFoldersSelection()
        assertThat(sql).contains("${Favorites.ITEM_TYPE} = ${Favorites.ITEM_TYPE_FOLDER}")
        assertThat(sql).contains("NOT IN (SELECT ${Favorites.CONTAINER} FROM ${Favorites.TABLE_NAME})")
        assertThat(sql).contains("(${Favorites.OPTIONS} & ${FolderInfo.FLAG_FOLDER_WIDGET}) = 0")
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew testLawnWithQuickstepExpressiveDebugUnitTest --tests 'app.lawnchair.folder.widget.FolderWidgetsTest'`
Expected: compilation failure (`FolderWidgets`, `FLAG_FOLDER_WIDGET` unresolved).

- [ ] **Step 3: Implement** the constant, `FolderWidgets`, and the `Folder`/`ModelDbController` guards.

- [ ] **Step 4: Run to verify they pass** — same command; 6 PASS.

- [ ] **Step 5: Commit** — `git add` the files, then
  `git commit -m "feat(folder-widget): widget flag; folder widgets never dissolve or get cleaned up"`.

---

### Task 3: Loader — migrate large folders and place folder widgets last

**Files:**
- Create: `lawnchair/src/app/lawnchair/folder/widget/FolderWidgetPlacement.kt`
- Modify: `src/com/android/launcher3/model/WorkspaceItemProcessor.kt` (`processFolderOrAppPair`,
  `processFolderItems`, `finalizeData`)
- Modify: `src/com/android/launcher3/model/LoaderCursor.java`
- Test: `tests/expressiveUnit/app/lawnchair/folder/widget/FolderWidgetPlacementTest.kt`

**Interfaces:**
- Consumes: `FolderWidgets.wantsWidget`, `FLAG_FOLDER_WIDGET` (Task 2).
- Produces:
  - `data class GridRect(val x: Int, val y: Int, val spanX: Int, val spanY: Int)`
  - `data class Placement(val screenId: Int, val rect: GridRect)`
  - `object FolderWidgetPlacement`:
    `fun normalizeSpan(spanX: Int, spanY: Int, columns: Int, rows: Int): Pair<Int, Int>` (clamp to the
    grid; 1x1 → 2x2, or 1x2/2x1 when the grid is one cell wide/tall) and
    `fun place(wanted: GridRect, screenId: Int, screens: List<Int>, columns: Int, rows: Int, isVacant: (Int, GridRect) -> Boolean): Placement`
  - `LoaderCursor.deferFolderWidget(FolderInfo)`;
    `LoaderCursor.placeDeferredFolderWidgets(IntSparseArrayMap<ItemInfo> loadedItems, java.util.function.Consumer<FolderInfo> onMoved)`

`place`, in this order:
1. `wanted` is inside the grid and vacant on `screenId` → keep.
2. Shrink in place: `GridRect(x, y, min(spanX, columns − x), min(spanY, rows − y))` when it has ≥ 2 cells
   and is vacant.
3. Same screen: the vacant position of the normalized size nearest to `(x, y)` by squared distance
   (ties: smaller `y`, then smaller `x`).
4. Screens in `screens` with id > `screenId`, in list order: the first vacant position, row-major.
5. New screen `max(screens) + 1` at `(0, 0)`.

- [ ] **Step 1: Write the failing tests**

```kotlin
class FolderWidgetPlacementTest {
    private val cols = 4
    private val rows = 5
    private fun occupancy(vararg taken: Pair<Int, GridRect>): (Int, GridRect) -> Boolean = { s, r ->
        r.x >= 0 && r.y >= 0 && r.x + r.spanX <= cols && r.y + r.spanY <= rows &&
            taken.none { (ts, t) ->
                ts == s && r.x < t.x + t.spanX && t.x < r.x + r.spanX && r.y < t.y + t.spanY && t.y < r.y + r.spanY
            }
    }

    @Test fun keepsAVacantPlace() {
        assertThat(FolderWidgetPlacement.place(GridRect(0, 1, 2, 2), 0, listOf(0), cols, rows, occupancy()))
            .isEqualTo(Placement(0, GridRect(0, 1, 2, 2)))
    }

    @Test fun overlapMovesToNearestFreeAreaOnSamePage() {
        val clock = 0 to GridRect(1, 1, 2, 2)
        assertThat(FolderWidgetPlacement.place(GridRect(0, 1, 2, 2), 0, listOf(0), cols, rows, occupancy(clock)))
            .isEqualTo(Placement(0, GridRect(0, 3, 2, 2)))
    }

    @Test fun outOfGridShrinksInPlace() {
        assertThat(FolderWidgetPlacement.place(GridRect(3, 0, 2, 2), 0, listOf(0), cols, rows, occupancy()))
            .isEqualTo(Placement(0, GridRect(3, 0, 1, 2)))
    }

    @Test fun fullPage_usesLaterPage() {
        val full = 0 to GridRect(0, 0, cols, rows)
        assertThat(FolderWidgetPlacement.place(GridRect(0, 0, 2, 2), 0, listOf(0, 3), cols, rows, occupancy(full)))
            .isEqualTo(Placement(3, GridRect(0, 0, 2, 2)))
    }

    @Test fun fullPages_useNewScreen() {
        val taken = occupancy(0 to GridRect(0, 0, cols, rows), 1 to GridRect(0, 0, cols, rows))
        assertThat(FolderWidgetPlacement.place(GridRect(0, 0, 2, 2), 0, listOf(0, 1), cols, rows, taken))
            .isEqualTo(Placement(2, GridRect(0, 0, 2, 2)))
    }

    @Test fun secondWidget_seesFirstWidgetsCells() {
        val first = 0 to GridRect(0, 0, 2, 2)
        assertThat(FolderWidgetPlacement.place(GridRect(0, 0, 2, 2), 0, listOf(0), cols, rows, occupancy(first)).rect)
            .isEqualTo(GridRect(2, 0, 2, 2))
    }

    @Test fun normalizeSpan_neverOneCellAndFitsGrid() {
        assertThat(FolderWidgetPlacement.normalizeSpan(1, 1, 4, 5)).isEqualTo(2 to 2)
        assertThat(FolderWidgetPlacement.normalizeSpan(6, 9, 4, 5)).isEqualTo(4 to 5)
        assertThat(FolderWidgetPlacement.normalizeSpan(1, 1, 1, 5)).isEqualTo(1 to 2)
    }
}
```

- [ ] **Step 2: Run to verify they fail** —
  `--tests 'app.lawnchair.folder.widget.FolderWidgetPlacementTest'`; compilation failure.

- [ ] **Step 3: Implement `FolderWidgetPlacement`.**

- [ ] **Step 4: Wire the loader**
  - `processFolderOrAppPair`: for a `FolderInfo` with
    `FolderWidgets.wantsWidget(c.container, c.options, c.spanX, c.spanY)`: set the flag, set spans from
    `normalizeSpan(c.spanX, c.spanY, idp.numColumns, idp.numRows)`, call `c.deferFolderWidget(collection)`
    and return before `checkAndAddItem`.
  - `LoaderCursor.placeDeferredFolderWidgets`: per deferred folder (load order), get or create the
    screen's `GridOccupancy` exactly as `checkItemPlacement` does (including the first-screen
    search-bar row), call `place` with `isVacant` backed by `isRegionVacant`, `markCells`, put the
    folder into `loadedItems`, and call `onMoved` when its screen, cell or span changed.
  - `finalizeData`: before `processFolderItems()`, call `c.placeDeferredFolderWidgets(loadedItems)` with
    an `onMoved` that writes `SCREEN`, `CELLX`, `CELLY`, `SPANX`, `SPANY`, `OPTIONS` through
    `modelDbController.update(values, "${Favorites._ID}=?", arrayOf(f.id.toString()))`.
  - `processFolderItems`: for folder widgets, request `Favorites.DESKTOP_ICON_FLAG` icons for every app.

- [ ] **Step 5: Run the tests, then the full suite.** Expected: 7 new PASS; full suite green.

- [ ] **Step 6: Commit** — `feat(folder-widget): migrate large folders and place folder widgets after other items`.

---

### Task 4: Grid math and style rules

**Files:**
- Create: `lawnchair/src/app/lawnchair/folder/widget/FolderWidgetGridMath.kt`,
  `lawnchair/src/app/lawnchair/folder/widget/FolderWidgetStyle.kt`
- Test: `tests/expressiveUnit/app/lawnchair/folder/widget/FolderWidgetGridMathTest.kt`,
  `tests/expressiveUnit/app/lawnchair/folder/widget/FolderWidgetStyleTest.kt`

**Interfaces:**
- Produces:
  - `data class FolderWidgetGridInput(val widthPx: Int, val heightPx: Int, val spanX: Int, val itemCount: Int, val paddingPx: Int, val headerHeightPx: Int, val homeIconSizePx: Int, val labelHeightPx: Int, val iconLabelGapPx: Int, val rowSpacingPx: Int, val minTouchPx: Int, val columnOverride: Int? = null, val showLabels: Boolean = true, val iconScale: Float = 1f)`
    (`headerHeightPx` = 0 when hidden; `labelHeightPx` includes font scale)
  - `data class FolderWidgetGridSpec(val columns: Int, val columnWidthPx: Int, val iconSizePx: Int, val labelsVisible: Boolean, val rowHeightPx: Int, val gridLeftPx: Int, val gridTopPx: Int, val gridWidthPx: Int, val viewportHeightPx: Int, val contentHeightPx: Int, val scrollable: Boolean)`
  - `object FolderWidgetGridMath { fun autoColumns(spanX: Int): Int; fun compute(input: FolderWidgetGridInput): FolderWidgetGridSpec }`
  - `data class FolderWidgetStyle(val columns: Int? = null, val showLabels: Boolean = true, val showHeader: Boolean = true, val showNameBelow: Boolean = false, val backgroundColor: Int? = null, val backgroundOpacity: Float? = null, val cornerRadiusPx: Float? = null, val iconScale: Float? = null)`
  - `data class FolderWidgetDefaults(val backgroundColor: Int, val backgroundOpacity: Float, val cornerRadiusPx: Float)`
  - `data class ResolvedFolderWidgetStyle(val columns: Int?, val showLabels: Boolean, val showHeader: Boolean, val showNameBelow: Boolean, val backgroundColor: Int, val backgroundOpacity: Float, val cornerRadiusPx: Float, val iconScale: Float)`
  - `fun FolderWidgetStyle.resolve(isPro: Boolean, defaults: FolderWidgetDefaults): ResolvedFolderWidgetStyle`

`compute` (integer math floors; `round` is `Math.round`):
1. `innerWidth = max(0, width − 2·padding)`; `viewport = max(0, height − 2·padding − header)`.
2. `c0 = columnOverride?.coerceIn(2, 6) ?: autoColumns(spanX)`; `autoColumns = round(spanX·1.5).coerceIn(1, 6)`.
3. `columns` = the largest `c` in `1..c0` with `innerWidth / c ≥ minTouch`, else 1.
4. `columnWidth = innerWidth / columns`; `icon = (min(homeIcon, (0.75·columnWidth).toInt()) · iconScale).toInt()`.
5. `labeledRow = icon + gap + label`; `labelsVisible = showLabels && labeledRow ≤ viewport`.
6. If `!labelsVisible && icon > viewport`: `icon = viewport`.
7. `rowHeight = (labelsVisible ? labeledRow : icon) + rowSpacing`; `rows = ceil(itemCount / columns)`;
   `content = rows · rowHeight`; `scrollable = content > viewport`.
8. `gridWidth = columns · columnWidth`; `gridLeft = padding + (innerWidth − gridWidth) / 2`;
   `gridTop = padding + header + (scrollable ? 0 : (viewport − content) / 2)`.

`resolve`: columns `coerceIn(2, 6)` or null; the four Pro fields use the style value only when `isPro`,
else the default (icon scale default `1f`); opacity `coerceIn(0f, 1f)`, icon scale
`coerceIn(0.7f, 1.1f)`, radius `coerceAtLeast(0f)`.

- [ ] **Step 1: Write the failing tests**

```kotlin
class FolderWidgetGridMathTest {
    private val base = FolderWidgetGridInput(widthPx = 620, heightPx = 700, spanX = 2, itemCount = 6,
        paddingPx = 24, headerHeightPx = 96, homeIconSizePx = 150, labelHeightPx = 45,
        iconLabelGapPx = 12, rowSpacingPx = 18, minTouchPx = 144)

    @Test fun twoByTwo_isCenteredWithLabels() {
        val s = FolderWidgetGridMath.compute(base)
        assertThat(s.columns).isEqualTo(3)
        assertThat(s.columnWidthPx).isEqualTo(190)
        assertThat(s.iconSizePx).isEqualTo(142)
        assertThat(s.labelsVisible).isTrue()
        assertThat(s.rowHeightPx).isEqualTo(217)
        assertThat(s.contentHeightPx).isEqualTo(434)
        assertThat(s.scrollable).isFalse()
        assertThat(s.gridLeftPx).isEqualTo(25)
        assertThat(s.gridTopPx).isEqualTo(181)
    }

    @Test fun overflow_scrollsFromTheTop() {
        val s = FolderWidgetGridMath.compute(base.copy(itemCount = 12))
        assertThat(s.scrollable).isTrue()
        assertThat(s.gridTopPx).isEqualTo(120)
    }

    @Test fun autoColumns_followOneAndAHalfPerCell() {
        assertThat((1..5).map(FolderWidgetGridMath::autoColumns)).containsExactly(2, 3, 5, 6, 6).inOrder()
    }

    @Test fun override_isClampedThenFitsTouch() {
        assertThat(FolderWidgetGridMath.compute(base.copy(columnOverride = 9)).columns).isEqualTo(3)
        assertThat(FolderWidgetGridMath.compute(base.copy(columnOverride = 1)).columns).isEqualTo(2)
    }

    @Test fun narrowPanel_dropsColumnsForTouch() {
        assertThat(FolderWidgetGridMath.compute(base.copy(widthPx = 300, paddingPx = 0, spanX = 4)).columns)
            .isEqualTo(2)
    }

    @Test fun tinyPanel_neverNegativeAndHidesLabels() {
        val s = FolderWidgetGridMath.compute(base.copy(heightPx = 260))
        assertThat(s.labelsVisible).isFalse()
        assertThat(s.iconSizePx).isEqualTo(116)
        val t = FolderWidgetGridMath.compute(base.copy(widthPx = 10, heightPx = 10))
        assertThat(t.columns).isAtLeast(1)
        assertThat(t.viewportHeightPx).isAtLeast(0)
        assertThat(t.iconSizePx).isAtLeast(0)
    }

    @Test fun iconScale_appliesAfterTheCap() {
        assertThat(FolderWidgetGridMath.compute(base.copy(iconScale = 0.7f)).iconSizePx).isEqualTo(99)
    }

    @Test fun empty_isCenteredAndNotScrollable() {
        val s = FolderWidgetGridMath.compute(base.copy(itemCount = 0))
        assertThat(s.scrollable).isFalse()
        assertThat(s.gridTopPx).isEqualTo(398)
    }
}

class FolderWidgetStyleTest {
    private val d = FolderWidgetDefaults(backgroundColor = 0x112233, backgroundOpacity = 0.6f, cornerRadiusPx = 84f)
    private val custom = FolderWidgetStyle(columns = 4, showLabels = false, backgroundColor = 0xFF0000,
        backgroundOpacity = 0.2f, cornerRadiusPx = 10f, iconScale = 0.8f)

    @Test fun freeUser_getsDefaultsForProFields() {
        val r = custom.resolve(isPro = false, defaults = d)
        assertThat(r.columns).isEqualTo(4)
        assertThat(r.showLabels).isFalse()
        assertThat(r.backgroundColor).isEqualTo(0x112233)
        assertThat(r.backgroundOpacity).isEqualTo(0.6f)
        assertThat(r.cornerRadiusPx).isEqualTo(84f)
        assertThat(r.iconScale).isEqualTo(1f)
    }

    @Test fun proUser_getsCustomValues() {
        val r = custom.resolve(isPro = true, defaults = d)
        assertThat(r.backgroundColor).isEqualTo(0xFF0000)
        assertThat(r.iconScale).isEqualTo(0.8f)
    }

    @Test fun values_areClamped() {
        val r = FolderWidgetStyle(columns = 12, backgroundOpacity = 3f, iconScale = 2f, cornerRadiusPx = -5f)
            .resolve(isPro = true, defaults = d)
        assertThat(r.columns).isEqualTo(6)
        assertThat(r.backgroundOpacity).isEqualTo(1f)
        assertThat(r.iconScale).isEqualTo(1.1f)
        assertThat(r.cornerRadiusPx).isEqualTo(0f)
    }

    @Test fun defaults_matchTheSpec() {
        val r = FolderWidgetStyle().resolve(isPro = false, defaults = d)
        assertThat(r.showLabels).isTrue()
        assertThat(r.showHeader).isTrue()
        assertThat(r.showNameBelow).isFalse()
        assertThat(r.columns).isNull()
    }
}
```

- [ ] **Step 2: Run to verify they fail** — `--tests 'app.lawnchair.folder.widget.FolderWidget*'`.
- [ ] **Step 3: Implement both files.**
- [ ] **Step 4: Run to verify they pass.**
- [ ] **Step 5: Commit** — `feat(folder-widget): grid math and style rules`.

---

### Task 5: `FolderWidgetPanel` view

**Files:**
- Create: `lawnchair/src/app/lawnchair/folder/widget/FolderWidgetPanel.kt`,
  `lawnchair/res/drawable/ic_folder_widget_open.xml`
- Modify: `lawnchair/res/values/strings.xml` — `folder_widget_add_apps` "Add apps",
  `folder_widget_open_folder` "Open folder"
- Test: `tests/expressiveUnit/app/lawnchair/folder/widget/FolderWidgetPanelTest.kt` (Robolectric,
  `@Config(sdk = [37], application = Application::class)`, context
  `ContextThemeWrapper(app, R.style.LauncherTheme)` as in `BcSmartspaceGlanceMessageTest`)

**Interfaces:**
- Consumes: Task 4.
- Produces:
  - `data class FolderWidgetMetrics(val spanX: Int, val homeIconSizePx: Int, val labelHeightPx: Int, val iconLabelGapPx: Int, val rowSpacingPx: Int, val minTouchPx: Int, val paddingPx: Int, val headerHeightPx: Int)`
  - `class FolderWidgetPanel(context: Context, attrs: AttributeSet? = null) : FrameLayout` with
    `fun bind(title: CharSequence, adapter: RecyclerView.Adapter<*>, style: ResolvedFolderWidgetStyle, metrics: FolderWidgetMetrics)`,
    `val recyclerView: RecyclerView`, `val header: TextView`, `val openButton: View`,
    `val addAppsButton: View`, `val gridSpec: FolderWidgetGridSpec`, `val isScrollable: Boolean`,
    `var onOpenFolder: (() -> Unit)?`, `var onAddApps: (() -> Unit)?`, `fun scrollToEnd()`
- Behavior: rounded-rect background (style color × opacity, style radius) filling the view. `onLayout`
  computes the spec from the view size and the adapter's item count, places the header at the top
  (when `showHeader`), the `RecyclerView` at `(gridLeft, gridTop)` with width `gridWidth` and height
  `viewport − (gridTop − padding − header)`, `GridLayoutManager(columns)`, vertical scrollbar that fades,
  `isNestedScrollingEnabled = isScrollable`. The open button (32 dp, lower-right) shows only when the
  header is hidden; the add-apps button shows only when the adapter is empty. Header and open-button
  clicks call `onOpenFolder`; add-apps calls `onAddApps`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class FolderWidgetPanelTest {
    private val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Application>(), R.style.LauncherTheme)
    private val metrics = FolderWidgetMetrics(spanX = 2, homeIconSizePx = 150, labelHeightPx = 45,
        iconLabelGapPx = 12, rowSpacingPx = 18, minTouchPx = 144, paddingPx = 24, headerHeightPx = 96)
    private val style = FolderWidgetStyle().resolve(false, FolderWidgetDefaults(0x333333, 0.8f, 84f))

    private fun panel(items: Int, s: ResolvedFolderWidgetStyle = style) = FolderWidgetPanel(context).apply {
        bind("Google", FakeAdapter(items), s, metrics)
        measure(MeasureSpec.makeMeasureSpec(620, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(700, MeasureSpec.EXACTLY))
        layout(0, 0, 620, 700)
    }

    @Test fun gridIsCenteredHorizontally() {
        val p = panel(6)
        assertThat(p.recyclerView.left - 24).isWithin(1).of(620 - 24 - p.recyclerView.right)
        assertThat(p.recyclerView.top).isEqualTo(p.gridSpec.gridTopPx)
    }

    @Test fun scrollOnlyWhenOverflowing() {
        assertThat(panel(6).isScrollable).isFalse()
        val p = panel(40)
        assertThat(p.isScrollable).isTrue()
        assertThat(p.recyclerView.canScrollVertically(1)).isTrue()
    }

    @Test fun emptyAdapter_showsAddAppsButton() {
        val p = panel(0)
        assertThat(p.addAppsButton.visibility).isEqualTo(View.VISIBLE)
    }

    @Test fun hiddenHeader_showsOpenButton() {
        var opened = 0
        val p = panel(6, style.copy(showHeader = false)).apply { onOpenFolder = { opened++ } }
        assertThat(p.header.visibility).isEqualTo(View.GONE)
        assertThat(p.openButton.visibility).isEqualTo(View.VISIBLE)
        p.openButton.performClick()
        assertThat(opened).isEqualTo(1)
    }

    @Test fun headerClick_opensFolder() {
        var opened = 0
        val p = panel(6).apply { onOpenFolder = { opened++ } }
        p.header.performClick()
        assertThat(opened).isEqualTo(1)
    }
}
```
(`FakeAdapter(n)` is a private `RecyclerView.Adapter` in the test that binds `TextView`s.)

- [ ] **Step 2: Run to verify they fail** — `--tests 'app.lawnchair.folder.widget.FolderWidgetPanelTest'`.
- [ ] **Step 3: Implement the panel.**
- [ ] **Step 4: Run to verify they pass.**
- [ ] **Step 5: Commit** — `feat(folder-widget): panel with centered scrolling grid`.

---

### Task 6: `FolderWidgetView` bound on Home

**Files:**
- Create: `lawnchair/src/app/lawnchair/folder/widget/FolderWidgetView.kt`,
  `lawnchair/src/app/lawnchair/folder/widget/FolderWidgetAppsAdapter.kt`,
  `lawnchair/res/layout/folder_widget.xml` (root `app.lawnchair.folder.widget.FolderWidgetView`;
  children `FolderWidgetPanel` `@+id/folder_widget_panel` and the `DoubleShadowBubbleTextView`
  `@+id/folder_icon_name` copied from `res/layout/folder_icon.xml`, gravity bottom, `GONE` unless
  `showNameBelow`)
- Modify: `src/com/android/launcher3/util/ItemInflater.kt` (folder branch → `R.layout.folder_widget`
  when `FolderWidgets.isFolderWidget(item)`)
- Modify: `src/com/android/launcher3/BubbleTextView.java` — add `public void setIconSizeOverridePx(int px)`
  (sets `mIconSize` and re-applies the icon bounds)
- Modify: `src/com/android/launcher3/folder/FolderIcon.java` — widen to `protected` only the members the
  subclass uses; no behavior change

**Interfaces:**
- Consumes: Tasks 2, 4, 5.
- Produces: `class FolderWidgetView : FolderIcon` with `val panel: FolderWidgetPanel`,
  `fun visibleIconFor(match: java.util.function.Predicate<ItemInfo>): View?` (an app icon fully visible
  in the grid), `fun panelRectInDragLayer(out: Rect)`, `fun applyStyle(style: ResolvedFolderWidgetStyle)`;
  overrides `dispatchDraw` (no 1x1 preview), `onMeasure` (fills its cells), `getPreviewBounds`,
  `getSourceVisualDragBounds`, `getWorkspaceVisualDragBounds` (the panel rect), `onItemsChanged`
  (rebind adapter), `onTitleChanged` (header), `setTextVisible` (name below), `updateDotInfo` (calls
  `applyDotState(info, true)` on each bound grid icon), `drawLeaveBehindIfExists` /
  `clearLeaveBehindIfExists` (hide/show the grid), `setIconVisible`.
  - `panel.onOpenFolder = { folder.animateOpen() }`.
  - `FolderWidgetAppsAdapter(launcher: Launcher, folder: FolderInfo, onAppLongClick: (BubbleTextView) -> Boolean)`:
    inflates `R.layout.folder_application`, `applyFromWorkspaceItem`, `setIconSizeOverridePx(spec.iconSizePx)`,
    text visibility from `spec.labelsVisible`, `setOnClickListener(ItemClickHandler.INSTANCE)`,
    long-click → `onAppLongClick` (Task 8 fills it; until then `false`).
  - `FolderWidgetMetrics` from the `DeviceProfile`: `homeIconSizePx = dp.iconSizePx`,
    `labelHeightPx` = the line height of a `TextPaint` at `dp.folderChildTextSizePx`,
    `iconLabelGapPx = dp.folderChildDrawablePaddingPx`, `rowSpacingPx = 8dp`, `minTouchPx = 48dp`,
    `paddingPx = 12dp`, `headerHeightPx = 40dp` when shown.
  - Style until Task 13: `FolderWidgetStyle().resolve(isPro, defaults)`; defaults from `folderColor`,
    `folderBackgroundOpacity` and `system_app_widget_background_radius`.

- [ ] **Step 1: Build** — `./gradlew assembleLawnWithQuickstepExpressiveDebug`; BUILD SUCCESSFUL.

- [ ] **Step 2: Device check (emulator — it holds a 4.0.3-made 2x2 Google folder over the clock)**

```bash
A=<scratchpad>/a   # wrapper: exec adb -s emulator-5590 "$@"
$A install -r build/outputs/apk/lawnWithQuickstepExpressive/debug/*.apk
$A shell am start -W -a android.intent.action.MAIN -c android.intent.category.HOME -n dev.launcher.expressive.l3.debug/app.lawnchair.LawnchairLauncher
$A exec-out screencap -p > <scratchpad>/fw-task6.png
```
Expected: the Google folder is a 2x2 Folder widget beside the clock (moved off it by Task 3), filling
its cells, header "Google", centered 3-column grid with names; tapping Gmail launches it; the header
opens the full folder; notification dots appear on grid icons.

- [ ] **Step 3: Commit** — `feat(folder-widget): bind folder widgets on Home`.

---

### Task 7: `FolderWidgetResizeFrame`

**Files:**
- Create: `lawnchair/src/app/lawnchair/folder/widget/FolderWidgetResizeMath.kt`,
  `lawnchair/src/app/lawnchair/folder/widget/FolderWidgetResizeFrame.kt`,
  `lawnchair/res/layout/folder_widget_resize_frame.xml` (copy of `res/layout/app_widget_resize_frame.xml`
  with root `app.lawnchair.folder.widget.FolderWidgetResizeFrame`, same ids, drawables and dimens)
- Modify: `src/com/android/launcher3/Workspace.java` — in `onDrop`, next to both
  `getWidgetResizeFrameRunnable` calls, the same for a `FolderWidgetView` (not hotseat, not
  `options.isAccessibleDrag`): `() -> { if (!isPageInTransition()) FolderWidgetResizeFrame.show(mLauncher, fw); }`
- Test: `tests/expressiveUnit/app/lawnchair/folder/widget/FolderWidgetResizeMathTest.kt`

**Interfaces:**
- Consumes: `GridRect` (Task 3), `FolderWidgets.isValidSpan` (Task 2), `FolderWidgetView` (Task 6).
- Produces:
  - `object FolderWidgetResizeMath { fun step(current: GridRect, left: Boolean, top: Boolean, right: Boolean, bottom: Boolean, hDelta: Int, vDelta: Int, columns: Int, rows: Int): GridRect }`
    — per axis, the rules of `AppWidgetResizeFrame.IntRange.applyDeltaAndBound` with `minSize = 1`,
    `maxSize = maxEnd = columns` (or `rows`); a result that is not `isValidSpan` returns `current`.
  - `class FolderWidgetResizeFrame : AbstractFloatingView` with
    `companion object { fun show(launcher: Launcher, widget: FolderWidgetView) }` — a port of
    `AppWidgetResizeFrame`'s touch handling, snapping, `resizeWidgetIfNeeded` (using `step` and
    `CellLayout.createAreaForResize(x, y, spanX, spanY, widget, direction, commit)`; the final commit on
    dismiss writes the spans through `createAreaForResize`'s commit) and dismiss rules; constants
    `RESIZE_THRESHOLD = 0.66f`, `SNAP_DURATION_MS = 150`, `RESIZE_TRANSITION_DURATION_MS = 150`; no
    reconfigure button; the state announcer says the new size.

- [ ] **Step 1: Write the failing tests**

```kotlin
class FolderWidgetResizeMathTest {
    private val r = GridRect(0, 0, 2, 2)
    private fun step(c: GridRect, l: Boolean, t: Boolean, rt: Boolean, b: Boolean, h: Int, v: Int) =
        FolderWidgetResizeMath.step(c, l, t, rt, b, h, v, 4, 5)

    @Test fun growsRight() { assertThat(step(r, false, false, true, false, 1, 0)).isEqualTo(GridRect(0, 0, 3, 2)) }
    @Test fun growsDown() { assertThat(step(r, false, false, false, true, 0, 2)).isEqualTo(GridRect(0, 0, 2, 4)) }
    @Test fun stopsAtGridEdge() { assertThat(step(r, false, false, true, false, 5, 0)).isEqualTo(GridRect(0, 0, 4, 2)) }
    @Test fun leftEdgeMovesStart() { assertThat(step(GridRect(1, 0, 2, 2), true, false, false, false, -1, 0)).isEqualTo(GridRect(0, 0, 3, 2)) }
    @Test fun shrinksToStrip() { assertThat(step(r, false, false, false, true, 0, -1)).isEqualTo(GridRect(0, 0, 2, 1)) }
    @Test fun step_refusesSingleCell() { assertThat(step(GridRect(0, 0, 2, 1), false, false, true, false, -1, 0)).isEqualTo(GridRect(0, 0, 2, 1)) }
}
```

- [ ] **Step 2: Run to verify they fail** — `--tests 'app.lawnchair.folder.widget.FolderWidgetResizeMathTest'`.
- [ ] **Step 3: Implement** the math and the frame.
- [ ] **Step 4: Run to verify they pass**, then build.
- [ ] **Step 5: Device check (Pixel 8 Pro)** — install, launch the debug Home; long-press the migrated or a
  test widget's header and release → frame. Right handle one cell → 3x2 with neighbours pushed; bottom
  handle up → 3x1 strip; it never becomes one cell; restart the launcher → size kept.
- [ ] **Step 6: Commit** — `feat(folder-widget): widget-style resize frame`.

---

### Task 8: Touch, menus, drag in and out

**Files:**
- Modify: `FolderWidgetView.kt`, `FolderWidgetAppsAdapter.kt`
- Create: `lawnchair/src/app/lawnchair/folder/widget/FolderWidgetMenus.kt`
- Modify: `src/com/android/launcher3/CellLayout.java` (`getFolderCreationRadius`)
- Modify: `src/com/android/launcher3/touch/ItemLongClickListener.java` (`onWorkspaceItemLongClick`)
- Modify: `lawnchair/res/values/strings.xml` — `folder_widget_make_widget` "Make widget",
  `folder_widget_make_folder` "Make normal folder", `folder_widget_customize` "Customize",
  `folder_widget_remove` "Remove"
- Test: `tests/expressiveUnit/app/lawnchair/folder/widget/FolderWidgetMenusTest.kt`

**Interfaces:**
- Consumes: Tasks 2, 6, 7. Task 9 and Task 14 fill in the menu actions; until then each action just
  closes the menu.
- Produces:
  - `object FolderWidgetMenus { fun itemsFor(info: FolderInfo): List<Int>; fun show(launcher: Launcher, icon: FolderIcon): DragOptions.PreDragCondition }`
    — `itemsFor`: desktop non-widget folder → `[folder_widget_make_widget]`; widget →
    `[folder_widget_customize, folder_widget_make_folder, folder_widget_remove]`; others → empty.
    `show` opens an `OptionsPopupView` anchored to the icon's rect in the drag layer and returns a
    condition with `shouldStartDrag(d) = d > ViewConfiguration.get(launcher).scaledTouchSlop` and
    `onPreDragEnd(_, dragStarted)`: started → close the menu; not started and the icon is a
    `FolderWidgetView` → `FolderWidgetResizeFrame.show(launcher, icon)`.
- Behavior:
  - `onWorkspaceItemLongClick`: for a `FolderIcon` whose `FolderWidgetMenus.itemsFor` is not empty, pass
    `DragOptions().apply { preDragCondition = FolderWidgetMenus.show(launcher, v) }` to `beginDrag`.
  - App long-press (adapter): `launcher.dragLayer.requestDisallowInterceptTouchEvent(false)`; options with
    `preDragCondition = icon.startLongPressAction()` when `icon.canShowLongPressPopup()`;
    `widget.folder.startDrag(icon, options)`.
  - Long-press on empty grid space: a `RecyclerView.OnItemTouchListener` with a `GestureDetector` calls
    the widget's `performLongClick()` when no child is under the touch.
  - Scrolling: on `ACTION_DOWN` in the grid, when `panel.isScrollable`, call
    `launcher.dragLayer.requestDisallowInterceptTouchEvent(true)`.
  - Drops: `acceptDrop(item) = FolderWidgets.acceptsDrop(item)`; `onDragEnter` shows an outline and springs
    the panel to 1.03× (`SpringAnimation`, `STIFFNESS_MEDIUM`, `DAMPING_RATIO_NO_BOUNCY`); `onDragExit`
    springs back; `onDrop` → `folder.addFolderContent(item)` then `panel.scrollToEnd()`.
  - `CellLayout.getFolderCreationRadius(targetCell)` returns `Float.MAX_VALUE` when
    `getChildAt(targetCell[0], targetCell[1]) instanceof FolderWidgetView`.

- [ ] **Step 1: Write the failing tests**

```kotlin
class FolderWidgetMenusTest {
    @Test fun normalFolder_offersMakeWidget() {
        val f = FolderInfo().apply { container = Favorites.CONTAINER_DESKTOP }
        assertThat(FolderWidgetMenus.itemsFor(f)).containsExactly(R.string.folder_widget_make_widget)
    }

    @Test fun folderWidget_offersCustomizeMakeFolderRemove() {
        val f = FolderInfo().apply { container = Favorites.CONTAINER_DESKTOP; options = FolderInfo.FLAG_FOLDER_WIDGET }
        assertThat(FolderWidgetMenus.itemsFor(f)).containsExactly(R.string.folder_widget_customize,
            R.string.folder_widget_make_folder, R.string.folder_widget_remove).inOrder()
    }

    @Test fun dockFolder_offersNothing() {
        assertThat(FolderWidgetMenus.itemsFor(FolderInfo().apply { container = Favorites.CONTAINER_HOTSEAT })).isEmpty()
    }
}
```

- [ ] **Step 2: Run to verify they fail**, **Step 3: implement**, **Step 4: run to verify they pass**.

- [ ] **Step 5: Device check (emulator, then Pixel 8 Pro)** — long-press Gmail in the widget → shortcuts
  popup; drag it to an empty cell → it leaves the widget. Drag Chrome from the dock over the widget →
  outline + spring; drop → appended, grid scrolled to it. With ≥ 13 apps, swipe up/down inside → the grid
  scrolls, the drawer/shade do not open; swipe left/right → pages change; with ≤ 6 apps, swipe up over the
  widget → the app drawer opens. Long-press a normal folder → "Make widget" menu, moving starts a drag.

- [ ] **Step 6: Commit** — `feat(folder-widget): menus, app long-press, drag in and out, scroll vs Home gestures`.

---

### Task 9: Create, convert and remove

**Files:**
- Create: `lawnchair/src/app/lawnchair/folder/widget/FolderWidgetController.kt`
- Modify: `FolderWidgetMenus.kt` (wire Make widget, Make normal folder, Remove);
  `src/com/android/launcher3/folder/Folder.java` (footer: "Make widget" for desktop folders; for widgets a
  "Make normal folder" button — the gear is added in Task 14)
- Modify: `lawnchair/res/values/strings.xml` — `folder_widget_no_room` "No room for a widget here.",
  `folder_widget_remove_title` "Remove %1$s?", `folder_widget_put_back` "Put apps back on Home",
  `folder_widget_remove_all` "Remove all"
- Test: `tests/expressiveUnit/app/lawnchair/folder/widget/FolderWidgetControllerTest.kt`

**Interfaces:**
- Consumes: Tasks 2, 3, 6, 7, 8.
- Produces: `object FolderWidgetController` with
  - `fun makeWidget(launcher: Launcher, icon: FolderIcon): Boolean` — target `GridRect(cellX, cellY, 2, 2)`;
    `CellLayout.createAreaForResize(..., commit = true)`; on success set the flag and spans,
    `modelWriter.modifyItemInDatabase(...)`, and rebind the view (remove the old `FolderIcon`, bind the same
    `FolderInfo`); on failure toast `folder_widget_no_room` and return false.
  - `fun makeNormalFolder(launcher: Launcher, widget: FolderWidgetView)` — clear the flag, 1x1 at the
    top-left cell, rebind (style deletion is added in Task 13).
  - `fun remove(launcher: Launcher, widget: FolderWidgetView, putBack: Boolean)` — `putBack`: each app
    becomes a 1x1 desktop item at the next `putBackPlan` placement (`addOrMoveItemInDatabase`), then the
    folder row is deleted; otherwise the folder and its items are deleted as removing a folder does.
  - `fun putBackPlan(count: Int, screenId: Int, screens: List<Int>, columns: Int, rows: Int, isVacant: (Int, GridRect) -> Boolean): List<Placement>`
    — 1x1 cells: same screen row-major, then screens with a larger id in list order, then new screens
    `max + 1`, `max + 2`, … each filled row-major before the next.
  - The menu's Remove and the Remove drop target show a dialog with `folder_widget_put_back` /
    `folder_widget_remove_all`.

- [ ] **Step 1: Write the failing tests**

```kotlin
class FolderWidgetControllerTest {
    private val cols = 4
    private val rows = 5
    private fun vacant(takenByScreen: Map<Int, Set<Pair<Int, Int>>>): (Int, GridRect) -> Boolean =
        { s, r -> (takenByScreen[s] ?: emptySet()).none { it == r.x to r.y } && r.x < cols && r.y < rows }

    @Test fun putBack_fillsSamePageFirst() {
        val taken = (0 until cols).flatMap { x -> (0 until rows).map { y -> x to y } }.toSet() - setOf(2 to 4, 3 to 4)
        val plan = FolderWidgetController.putBackPlan(3, 0, listOf(0), cols, rows, vacant(mapOf(0 to taken)))
        assertThat(plan).containsExactly(
            Placement(0, GridRect(2, 4, 1, 1)), Placement(0, GridRect(3, 4, 1, 1)), Placement(1, GridRect(0, 0, 1, 1)),
        ).inOrder()
    }

    @Test fun putBack_opensNewPagesWhenFull() {
        val all = (0 until cols).flatMap { x -> (0 until rows).map { y -> x to y } }.toSet()
        val plan = FolderWidgetController.putBackPlan(21, 0, listOf(0, 1), cols, rows, vacant(mapOf(0 to all, 1 to all)))
        assertThat(plan.take(20).map { it.screenId }.toSet()).containsExactly(2)
        assertThat(plan[19]).isEqualTo(Placement(2, GridRect(3, 4, 1, 1)))
        assertThat(plan[20]).isEqualTo(Placement(3, GridRect(0, 0, 1, 1)))
    }

    @Test fun putBack_returnsOnePlacementPerApp() {
        assertThat(FolderWidgetController.putBackPlan(5, 0, listOf(0), cols, rows, vacant(emptyMap()))).hasSize(5)
    }
}
```

- [ ] **Step 2–4:** run (fail) → implement → run (pass).

- [ ] **Step 5: Device check (Pixel 8 Pro)** — make a 3-app folder; long-press → Make widget → 2x2, neighbours
  pushed; menu → Make normal folder → 1x1; Make widget again; drag to Remove → dialog → Put apps back on
  Home → three icons, no folder.

- [ ] **Step 6: Commit** — `feat(folder-widget): make widget, make normal folder, remove with put-back`.

---

### Task 10: Widget picker entry and app picker

**Files:**
- Modify: `src/com/android/launcher3/widget/custom/CustomWidgetManager.java` — add
  `public void addBuiltInWidget(ComponentName provider, CustomWidgetPlugin plugin)`: like
  `onPluginConnected` but with the given provider (`new ComponentName(context.getPackageName(), CLS_CUSTOM_WIDGET_PREFIX + "folder")`).
- Create: `lawnchair/src/app/lawnchair/folder/widget/FolderWidgetPickerEntry.kt` — a `CustomWidgetPlugin`
  whose `updateWidgetInfo` sets `label = "Folder"` (string `folder_widget_label`), `spanX = spanY = 2`,
  `minSpanX = minSpanY = 1`, `resizeMode = AppWidgetProviderInfo.RESIZE_BOTH`,
  `previewImage = R.drawable.folder_widget_preview`; `onViewCreated` inflates a static preview;
  `fun register(context: Context)` runs once per process.
- Create: `lawnchair/res/drawable/folder_widget_preview.xml` (vector: rounded panel with a 3x2 icon grid)
- Modify: `lawnchair/src/app/lawnchair/LawnchairLauncher.kt` (`onCreate` → `FolderWidgetPickerEntry.register(this)`)
- Modify: `src/com/android/launcher3/Launcher.java` (`addPendingItem`: a `PendingAddWidgetInfo` whose
  `componentName` is the folder entry → `FolderWidgetController.createFromPicker(this, info)`; return)
- Create: `lawnchair/src/app/lawnchair/folder/widget/FolderWidgetAppSelection.kt`,
  `lawnchair/src/app/lawnchair/folder/widget/ui/FolderWidgetAppPicker.kt` (Compose in `ComposeBottomSheet`:
  name field + searchable multi-select list from `appsState()`, current apps pre-checked, Save/Cancel)
- Modify: `FolderWidgetController.kt` (`createFromPicker`, `showAppPicker`); `FolderWidgetView.kt`
  (`panel.onAddApps = { FolderWidgetController.showAppPicker(launcher, this) }`); `Folder.java` footer
  ("Add apps" for widgets)
- Test: `tests/expressiveUnit/app/lawnchair/folder/widget/FolderWidgetAppSelectionTest.kt`

**Interfaces:**
- Consumes: Tasks 2, 6, 9.
- Produces:
  - `object FolderWidgetAppSelection { fun diff(current: List<ComponentKey>, selected: List<ComponentKey>): Pair<List<ComponentKey>, List<ComponentKey>> }`
    → `(toAdd in selected order, toRemove in current order)`
  - `FolderWidgetController.createFromPicker(launcher: Launcher, info: PendingAddItemInfo)` — a flagged
    `FolderInfo` titled "Folder" at `info.screenId/cellX/cellY` with normalized `info.spanX/spanY`,
    `addItemToDatabase`, bound, then `showAppPicker`.
  - `FolderWidgetController.showAppPicker(launcher: Launcher, widget: FolderWidgetView)` — applies the diff:
    new items from `AppInfo.makeWorkspaceItem(launcher)` through `folder.addFolderContent`, unchecked ones
    through `folder.removeFolderContent` + `modelWriter.deleteItemFromDatabase`, title through
    `FolderInfo.setTitle(title, modelWriter)`.

- [ ] **Step 1: Write the failing tests**

```kotlin
class FolderWidgetAppSelectionTest {
    private fun key(p: String) = ComponentKey(ComponentName(p, "$p.Main"), Process.myUserHandle())

    @Test fun diff_keepsOrderAndFindsChanges() {
        val (add, remove) = FolderWidgetAppSelection.diff(listOf(key("a"), key("b")), listOf(key("c"), key("a")))
        assertThat(add).containsExactly(key("c"))
        assertThat(remove).containsExactly(key("b"))
    }

    @Test fun diff_noChanges() {
        val (add, remove) = FolderWidgetAppSelection.diff(listOf(key("a")), listOf(key("a")))
        assertThat(add).isEmpty()
        assertThat(remove).isEmpty()
    }
}
```

- [ ] **Step 2–4:** run (fail) → implement → run (pass).
- [ ] **Step 5: Device check (Pixel 8 Pro)** — Home long-press → Widgets → "Folder" under Expressive →
  drop → app picker → pick 5 apps, name "Work" → 2x2 widget "Work" with 5 apps; repeat with Cancel →
  empty widget showing "Add apps", which opens the picker.
- [ ] **Step 6: Commit** — `feat(folder-widget): widget picker entry and app picker`.

---

### Task 11: Open/close and launch/return animations

**Files:**
- Create: `lawnchair/src/app/lawnchair/folder/widget/FolderWidgetAnimations.kt`
- Create: `lawnchair/src/app/lawnchair/folder/widget/RoundRectRevealAnimator.kt` — restore from
  `git show ce0545f:lawnchair/src/app/lawnchair/folder/RoundRectRevealAnimator.kt` with the new package
- Modify: `src/com/android/launcher3/folder/FolderAnimationManager.java` (when the folder icon is a
  `FolderWidgetView`, the animator-set factory returns `FolderWidgetAnimations.create(folder, widget, isOpening)`)
- Modify: `src/com/android/launcher3/Launcher.java` (`getFirstHomeElementForAppClose`: when the match is a
  `FolderWidgetView`, return `widget.visibleIconFor(<the same predicate>)` if not null)
- Modify: `FolderWidgetController.kt` (Make widget / Make normal folder morph: spring the panel's scale from
  the 1x1 icon bounds, `DAMPING_RATIO_NO_BOUNCY`, `STIFFNESS_MEDIUM`)
- Test: `tests/expressiveUnit/app/lawnchair/folder/widget/FolderWidgetAnimationsTest.kt`

**Interfaces:**
- Consumes: Tasks 6, 9.
- Produces:
  - `data class RoundRect(val rect: RectF, val radius: Float)`
  - `object FolderWidgetAnimations { fun revealEndpoints(widget: RectF, panel: RectF, widgetRadius: Float, panelRadius: Float, opening: Boolean): Pair<RoundRect, RoundRect>; fun create(folder: Folder, widget: FolderWidgetView, opening: Boolean): AnimatorSet }`
  - `create`: the folder's reveal clip runs between the two `RoundRect`s; each visible grid icon
    translates/scales to its panel cell; the rest fade; the widget's grid is hidden for the duration;
    duration `R.integer.config_materialFolderExpandDuration`, delay `R.integer.config_folderDelay`,
    interpolators as `FolderAnimationManager` uses for normal folders.

- [ ] **Step 1: Write the failing tests**

```kotlin
class FolderWidgetAnimationsTest {
    private val widget = RectF(0f, 0f, 600f, 600f)
    private val panel = RectF(50f, 300f, 1290f, 1500f)

    @Test fun opening_startsAtTheWidgetAndEndsAtThePanel() {
        val (start, end) = FolderWidgetAnimations.revealEndpoints(widget, panel, 84f, 60f, opening = true)
        assertThat(start).isEqualTo(RoundRect(widget, 84f))
        assertThat(end).isEqualTo(RoundRect(panel, 60f))
    }

    @Test fun closing_isTheReverse() {
        val (start, end) = FolderWidgetAnimations.revealEndpoints(widget, panel, 84f, 60f, opening = false)
        assertThat(start).isEqualTo(RoundRect(panel, 60f))
        assertThat(end).isEqualTo(RoundRect(widget, 84f))
    }
}
```

- [ ] **Step 2–4:** run (fail) → implement → run (pass).
- [ ] **Step 5: Device check (Pixel 8 Pro)** — `screenrecord --time-limit 10 /sdcard/fw-anim.mp4` while
  opening (header), closing (Back), launching an app and returning Home; pull it and split with
  `ffmpeg -i fw-anim.mp4 -vf fps=30 <scratchpad>/frame-%03d.png`. Expected: the reveal starts on the
  widget's rounded rect, close lands on it with no 1x1 circle, the app grows from the tapped icon and
  returns into it (into the widget center when scrolled away).
- [ ] **Step 6: Commit** — `feat(folder-widget): open, close, launch and return from the widget`.

---

### Task 12: Accessibility

**Files:**
- Create: `lawnchair/src/app/lawnchair/folder/widget/FolderWidgetAccessibility.kt`
- Modify: `src/com/android/launcher3/accessibility/LauncherAccessibilityDelegate.java` (actions for a
  `FolderWidgetView`: open, customize, wider, narrower, taller, shorter, make normal folder, remove; for a
  desktop `FolderIcon`: make widget)
- Modify: `FolderWidgetView.kt` (content description); `lawnchair/res/values/strings.xml` —
  plural `folder_widget_description` "Folder widget, %1$s, %2$d app" / "…apps", `folder_widget_wider`
  "Make wider", `folder_widget_narrower` "Make narrower", `folder_widget_taller` "Make taller",
  `folder_widget_shorter` "Make shorter"
- Test: `tests/expressiveUnit/app/lawnchair/folder/widget/FolderWidgetAccessibilityTest.kt`

**Interfaces:**
- Consumes: `GridRect`, `FolderWidgetResizeMath.step` (Task 7).
- Produces: `object FolderWidgetAccessibility { fun resizeActions(rect: GridRect, columns: Int, rows: Int, isVacant: (GridRect) -> Boolean): List<Int> }`
  — of `[wider, narrower, taller, shorter]` (in that order), those whose one-cell `step` (wider: right +1,
  narrower: right −1, taller: bottom +1, shorter: bottom −1) changes `rect` and whose new cells outside
  `rect` are vacant.

- [ ] **Step 1: Write the failing tests**

```kotlin
class FolderWidgetAccessibilityTest {
    private val all = { _: GridRect -> true }

    @Test fun twoByTwoInOpenSpace_offersAllFour() {
        assertThat(FolderWidgetAccessibility.resizeActions(GridRect(0, 0, 2, 2), 4, 5, all)).containsExactly(
            R.string.folder_widget_wider, R.string.folder_widget_narrower,
            R.string.folder_widget_taller, R.string.folder_widget_shorter).inOrder()
    }

    @Test fun stripCannotBecomeOneCell() {
        assertThat(FolderWidgetAccessibility.resizeActions(GridRect(0, 0, 2, 1), 4, 5, all))
            .containsNoneOf(R.string.folder_widget_narrower, R.string.folder_widget_shorter)
    }

    @Test fun atGridEdge_noWider() {
        assertThat(FolderWidgetAccessibility.resizeActions(GridRect(2, 0, 2, 2), 4, 5, all))
            .doesNotContain(R.string.folder_widget_wider)
    }

    @Test fun blockedCells_noWider() {
        assertThat(FolderWidgetAccessibility.resizeActions(GridRect(0, 0, 2, 2), 4, 5) { it.x + it.spanX <= 2 })
            .doesNotContain(R.string.folder_widget_wider)
    }
}
```

- [ ] **Step 2–4:** run (fail) → implement → run (pass).
- [ ] **Step 5: Device check** — TalkBack on the phone reads "Folder widget, Work, 5 apps"; each app is
  focusable; the actions menu lists the resize and conversion actions and they work.
- [ ] **Step 6: Commit** — `feat(folder-widget): TalkBack labels and actions`.

---

### Task 13: Per-widget style storage

**Files:**
- Create: `lawnchair/src/app/lawnchair/data/folderwidget/FolderWidgetStyleEntity.kt`
  (`@Entity(tableName = "FolderWidgetStyles")`; `@PrimaryKey val folderId: Int`; `val columns: Int?`,
  `val showLabels: Boolean`, `val showHeader: Boolean`, `val showNameBelow: Boolean`,
  `val backgroundColor: Int?`, `val backgroundOpacity: Float?`, `val cornerRadiusPx: Float?`,
  `val iconScale: Float?`; `fun toStyle(): FolderWidgetStyle`, `companion fun from(folderId, style)`)
- Create: `lawnchair/src/app/lawnchair/data/folderwidget/FolderWidgetStyleDao.kt`
  (`fun observe(folderId: Int): Flow<FolderWidgetStyleEntity?>`, `suspend fun upsert(e: FolderWidgetStyleEntity)`,
  `suspend fun delete(folderId: Int)`, `@RawQuery fun checkpoint(q: SupportSQLiteQuery): Int`)
- Create: `lawnchair/src/app/lawnchair/data/folderwidget/FolderWidgetStyleRepository.kt`
  (`INSTANCE = MainThreadInitializedObject(::FolderWidgetStyleRepository)`;
  `fun observe(folderId: Int): Flow<FolderWidgetStyle>` (missing row → `FolderWidgetStyle()`),
  `suspend fun save(folderId: Int, style: FolderWidgetStyle)`, `suspend fun delete(folderId: Int)`)
- Modify: `lawnchair/src/app/lawnchair/data/AppDatabase.kt` — add the entity, `version = 4`,
  `abstract fun folderWidgetStyleDao()`, its checkpoint, and
  `val MIGRATION_3_4 = object : Migration(3, 4) { override fun migrate(db) = db.execSQL(<CREATE TABLE IF NOT EXISTS \`FolderWidgetStyles\` … copied verbatim from the generated AppDatabase_Impl after one build>) }`
  added with `.addMigrations(MIGRATION_3_4)`
- Modify: `FolderWidgetView.kt` (observe the repository while attached; `applyStyle` on change),
  `FolderWidgetController.kt` (`makeNormalFolder` and `remove` call `delete`)
- Test: `tests/expressiveUnit/app/lawnchair/data/folderwidget/FolderWidgetStyleRepositoryTest.kt` (Robolectric)

**Interfaces:**
- Consumes: `FolderWidgetStyle` (Task 4).
- Produces: the repository API above (also constructible with an `AppDatabase` for tests:
  `FolderWidgetStyleRepository(db: AppDatabase)`).

- [ ] **Step 1: Write the failing tests**

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class FolderWidgetStyleRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val repo = FolderWidgetStyleRepository(db)
    private val custom = FolderWidgetStyle(columns = 4, showLabels = false, backgroundOpacity = 0.3f)

    @After fun close() = db.close()

    @Test fun missingRow_isDefaultStyle() = runTest { assertThat(repo.observe(7).first()).isEqualTo(FolderWidgetStyle()) }

    @Test fun saveThenObserve_roundTrips() = runTest {
        repo.save(7, custom)
        assertThat(repo.observe(7).first()).isEqualTo(custom)
    }

    @Test fun delete_returnsToDefaults() = runTest {
        repo.save(7, custom); repo.delete(7)
        assertThat(repo.observe(7).first()).isEqualTo(FolderWidgetStyle())
    }

    @Test fun migration3to4_createsTheSameTableAsRoom() {
        fun normalized(sql: String) = sql.replace("IF NOT EXISTS ", "").replace(Regex("\\s+"), " ").trim()
        val roomSql = db.openHelper.readableDatabase
            .query("SELECT sql FROM sqlite_master WHERE name = 'FolderWidgetStyles'").use { it.moveToFirst(); it.getString(0) }
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null).callback(object : SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        AppDatabase.MIGRATION_3_4.migrate(helper.writableDatabase)
        val migratedSql = helper.readableDatabase
            .query("SELECT sql FROM sqlite_master WHERE name = 'FolderWidgetStyles'").use { it.moveToFirst(); it.getString(0) }
        assertThat(normalized(migratedSql)).isEqualTo(normalized(roomSql))
    }
}
```

- [ ] **Step 2–4:** run (fail) → implement → run (pass).
- [ ] **Step 5: Commit** — `feat(folder-widget): per-widget style storage (Room v4)`.

---

### Task 14: Settings sheet and Pro styling

**Files:**
- Create: `lawnchair/src/app/lawnchair/folder/widget/FolderWidgetStyleEditor.kt`
- Create: `lawnchair/src/app/lawnchair/folder/widget/ui/FolderWidgetSettingsSheet.kt` — Compose in
  `ComposeBottomSheet`: live preview (`AndroidView { FolderWidgetPanel }` at the widget's size, scaled to
  fit, bound to the folder's apps); free rows Columns (Auto, 2–6), Show app names, Show header, Show name
  below; Pro rows Background color (the color picker used by `ColorPreference`), Background opacity
  (0–100 %), Corner radius (0 → half the shorter side), Icon size (70–110 %), each showing the Pro lock
  for free users and opening the Pro sheet on tap (as `ProGate` does); Reset. Writes go through
  `FolderWidgetStyleRepository.save`.
- Modify: `FolderWidgetMenus.kt` (Customize → sheet), `Folder.java` footer (gear for widgets → sheet)
- Modify: `lawnchair/res/values/strings.xml` — `folder_widget_columns` "Columns",
  `folder_widget_columns_auto` "Auto", `folder_widget_show_names` "Show app names",
  `folder_widget_show_header` "Show header", `folder_widget_show_name_below` "Show name below",
  `folder_widget_background_color` "Background color", `folder_widget_background_opacity`
  "Background opacity", `folder_widget_corner_radius` "Corner radius", `folder_widget_icon_size`
  "Icon size", `folder_widget_reset` "Reset"
- Test: `tests/expressiveUnit/app/lawnchair/folder/widget/FolderWidgetStyleEditorTest.kt`

**Interfaces:**
- Consumes: Tasks 4, 13; `ProManager.INSTANCE.get(context).isPro`.
- Produces: `class FolderWidgetStyleEditor(private val isPro: Boolean)` with
  `withColumns(s, c: Int?)`, `withShowLabels(s, v)`, `withShowHeader(s, v)`, `withShowNameBelow(s, v)` (always
  applied) and `withBackgroundColor(s, c: Int?)`, `withBackgroundOpacity(s, o: Float?)`,
  `withCornerRadius(s, r: Float?)`, `withIconScale(s, k: Float?)` (return `s` unchanged when `!isPro`), each
  returning `FolderWidgetStyle`; `fun reset(): FolderWidgetStyle = FolderWidgetStyle()`.

- [ ] **Step 1: Write the failing tests**

```kotlin
class FolderWidgetStyleEditorTest {
    @Test fun freeUser_canChangeFreeRows() {
        assertThat(FolderWidgetStyleEditor(false).withColumns(FolderWidgetStyle(), 4).columns).isEqualTo(4)
        assertThat(FolderWidgetStyleEditor(false).withShowHeader(FolderWidgetStyle(), false).showHeader).isFalse()
    }

    @Test fun freeUser_cannotChangeProRows() {
        val e = FolderWidgetStyleEditor(false)
        assertThat(e.withIconScale(FolderWidgetStyle(), 0.8f).iconScale).isNull()
        assertThat(e.withBackgroundColor(FolderWidgetStyle(), 0xFF00FF).backgroundColor).isNull()
    }

    @Test fun proUser_canChangeProRows() {
        assertThat(FolderWidgetStyleEditor(true).withBackgroundOpacity(FolderWidgetStyle(), 0.3f).backgroundOpacity)
            .isEqualTo(0.3f)
    }

    @Test fun reset_returnsDefaults() {
        assertThat(FolderWidgetStyleEditor(true).reset()).isEqualTo(FolderWidgetStyle())
    }
}
```

- [ ] **Step 2–4:** run (fail) → implement → run (pass).
- [ ] **Step 5: Device check (Pixel 8 Pro)** — Customize → Columns 4 and names off → the widget updates live;
  Pro rows show the lock. With the Cakey test license from `CakeyEditionTest` activated on the debug build
  (Expressive Pro), the four Pro rows apply live and survive a restart; deactivating shows the defaults;
  reactivating restores the custom values.
- [ ] **Step 6: Commit** — `feat(folder-widget): settings sheet with Pro styling`.

---

### Task 15: Verification, docs and merge

- [ ] **Step 1: Full suites** — `./gradlew testLawnWithQuickstepExpressiveDebugUnitTest` and
  `python3 -m unittest discover -s ci/tests`. Expected: green; record the counts.

- [ ] **Step 2: Device matrix (Pixel 8 Pro, then emulator)** — install the debug APK, make it Home
  (`cmd package set-home-activity dev.launcher.expressive.l3.debug/app.lawnchair.LawnchairLauncher`, or
  `am start -n` if it doesn't stick) and walk spec §7: picker add; Make widget / Make normal folder; resize
  in every direction with pushing; scroll vs Home gestures and flings; long-press shortcuts and drag-out;
  drops from Home and the drawer; open/close/launch/return recordings; TalkBack; "Remove animations"
  (`settings put global animator_duration_scale 0`, then restore the previous value); a launcher restart;
  an empty widget surviving a restart; and, on the emulator, the 4.0.3 upgrade (the 2x2 folder over the
  clock becomes a widget beside it). Restore the phone's Home to `dev.launcher.expressive.l3`. Two-panel
  layouts are not available on either device; record that limit. Notes (no media) in
  `artifacts/folder-widget-2026-10-02/NOTES.md`.

- [ ] **Step 3: Docs** — `docs/PIXEL_PARITY.md`: replace the "Large Home folders v2" section with a "Folder
  widget" section (behaviour, limits, evidence); update the XDA-014 entry in
  `~/Library/Application Support/Expressive CI/feedback/xda/backlog.json` (follow-up delivered, evidence);
  append the run-log entry.

- [ ] **Step 4: Review** — one whole-branch review (superpowers:requesting-code-review); fix findings with tests.

- [ ] **Step 5: Merge** — in `~/Developer/ExpressiveLauncher` (clean, on `codex/pixel-parity`, level with
  origin): `git merge --ff-only claude/folder-widget` (a merge commit if it can't fast-forward); push
  `codex/pixel-parity`. The next daily QA run ships it. Draft the Telegram reply to Adriano and the XDA
  follow-up for the user's approval.
