# Large Home folders v2 — design

Date: 2026-10-01. Branch: `claude/large-folders-v2` (from `codex/pixel-parity` at dcf7897).
Status: approved in conversation, pending written-spec review.

## Background

Large 2x2 Home folders (XDA-014) shipped in QA 4.0.2 (51) from fc4b6d8 and 2dc3957. User
feedback on 2026-10-01 called the result half-baked:

1. Making a folder large refuses ("No room…") when a widget is next to it. The folder should
   overlay the widget instead.
2. There is no discoverable way to change the size back. Today the only ways are a footer button in
   the open folder and a TalkBack action.
3. Every interaction animation is wrong: open, close, app launch from the tile, size switch, and
   drag/drop.

Root cause of (3): all animation paths still assume a 1x1 folder. `FolderAnimationManager` animates
from the circular `PreviewBackground` and `ClippedFolderIconLayoutRule` preview positions. The app
launch and return animations use the whole `FolderIcon` or its 1x1 preview. Size switching sets new
layout params and redraws at once. The drag accept effect jumps to 1.06x scale, and drop-into
animations target 1x1 preview positions.

## Goals

- A large folder can cover widgets (decision A: the widget stays in place and is partly covered,
  the folder wins touches where they overlap, and shrinking the folder uncovers it).
- Resize handles snap between 1x1 and 2x2 (decision B). The footer button and TalkBack action stay
  as fallbacks.
- All five interactions animate from and to the real tile geometry.

## Non-goals

- Large folders in the dock or app drawer.
- Covering icons, folders or the search bar (a hidden icon would be unreachable).
- Sizes other than 1x1 and 2x2.
- Shrinking, clipping or moving the covered widget.

## 1. Overlap model (approach 1: the folder stays a real 2x2 grid item)

**Storage.** Unchanged: a large folder is a desktop folder row with `spanX = spanY = 2`.

**Loading** (`LoaderCursor` / `WorkspaceItemProcessor`).
- A stored 2x2 folder loads at 2x2 when every covered cell is inside the grid and is either free or
  owned by a widget (`LauncherAppWidgetInfo`). Otherwise it loads at 1x1 at its top-left cell and
  its row is rewritten to 1x1, so it is never deleted.
- Load order does not matter. A widget that loads after a large folder is still placed at its
  stored cells when the only conflicts are cells held by large folders. Those cells pass to the
  widget, and the folder keeps the rest.
- Icons, folders, app pairs and the search bar block the folder.

**Ownership.** `GridOccupancy` keeps one owner per cell.
- The folder owns the cells it covers that no widget uses.
- Widget cells stay the widget's.
- When a covering widget is removed, moved or resized away, the freed cells under the folder are
  marked as the folder's again (`CellLayout.markCellsAsUnoccupiedForView` path + a re-mark hook).

**New unit: `app.lawnchair.folder.LargeFolderOverlap`** (pure Kotlin, unit-tested):
- `canCover(cellOwner)`: whether the folder may cover a cell with this owner (empty or widget: yes;
  anything else: no).
- `ownedCells(folderRect, widgetRects)`: the cells the folder owns.
- `loadSpan(region, occupantsAt)`: whether the loader keeps the folder at 2x2 or drops it to 1x1.
- `cellsToReclaim(folderRect, removedWidgetRect)`: the cells the folder takes back when a widget
  leaves.

**Drawing and touch.**
- Large folders get a raised Z inside `ShortcutAndWidgetContainer`: a small `translationZ` with no
  outline provider, so no shadow.
- Android dispatches touches in Z order, so wherever the two overlap the folder receives taps and
  long-presses. The uncovered part of the widget keeps working.

**Moving things.**
- Widget reorder, move and resize treat cells under a large folder as taken, so a widget can't be
  pushed or stretched under a folder.
- A dragged large folder may land over widgets but not over icons. The drop outline is 2x2.
- A widget dropped onto a large folder's cells goes elsewhere (unchanged behaviour).

## 2. Resize frame

**New `app.lawnchair.folder.FolderResizeFrame`**: an `AbstractFloatingView` modelled on
`AppWidgetResizeFrame`, reduced to two sizes.

- **Trigger:** a long-press on a Home folder that ends without movement. This is the same hook
  `Workspace` uses to show the widget resize frame after a drop without movement.
- **Handles (1x1 folder):** four, one per side. Dragging outward previews the tile growing that
  way. The grow anchor comes from the pulled handle(s): the right handle anchors left, so the folder
  grows right; the left handle grows left; and so on.
- **Snap:** past half a cell, the frame snaps to 2x2 with haptic feedback and a target outline.
  Below that it springs back.
- **Handles (large folder):** the same four handles. Dragging inward shrinks the folder to 1x1 in
  the cell on the side being pulled toward.
- **Blocked directions:** if the target region contains an icon, folder or search bar, or leaves
  the grid, the handle resists (rubber-band) and nothing is committed. Widget-only regions are
  allowed (section 1).
- **Commit:** the change is saved on handle release, through the same code path as the footer
  toggle (`LargeFolderController`, extended to take an explicit target rectangle).
- **Dismiss:** tap outside, Back, Home or the start of a drag. A committed change is never lost.
- **Fallbacks:** the footer button and the TalkBack action stay, using the existing
  auto-direction (grow right/down first, shrink to the top-left cell).
- **Accessibility:** each handle gets a TalkBack label ("Expand right", …) and an action.

**Pure logic, unit-tested:** `FolderResizeMath`, which handles snap thresholds, mapping a handle to
its target rectangle, and blocked-direction checks.

## 3. Animations

**Investigation first.**
- Baseline each interaction on the emulator (Android 17 QPR2 Beta 5): `screenrecord` plus
  `dumpsys gfxinfo <pkg> framestats`.
- Fixes are compared frame by frame against the baseline.
- Evidence goes in `artifacts/large-folders-2026-10-0x/`. Recordings and screenshots are kept out
  of git (repo policy); only text notes are committed.

**Shared geometry** (pure, unit-tested `LargeFolderAnimationGeometry`):
- the rectangle of each tile slot and of each mini icon in the "more" slot, in DragLayer
  coordinates
- the tile's corner radius
- the clip rectangles and icon transforms at the start and end of each animation.

1. **Open.**
   - A large-folder branch in `FolderAnimationManager`.
   - The panel's reveal clip starts as the tile's rounded rectangle (same bounds and radius) and
     grows into the panel.
   - Direct-slot apps translate and scale from their tile slots to their panel cells.
   - "More"-slot apps come out of their mini icons; the remaining apps fade in.
   - The tile is hidden for the same frame the animation starts.
2. **Close.**
   - The exact reverse of open.
   - The tile is shown again in the frame the animation ends. No 1x1 circle appears, and the tile
     doesn't redraw late.
3. **Launch from the tile and return Home.**
   - The launch transition (FloatingIconView / `QuickstepTransitionManager`) uses the tapped slot's
     rectangle and drawable, not the whole `FolderIcon`.
   - The app-close (return Home) target is that slot for direct-slot apps and the "more" slot for
     other apps in the folder.
4. **Size switch** (handles, footer button and TalkBack).
   - The tile morphs between the 1x1 circle and the 2x2 rounded rectangle with a spring of about
     300 ms.
   - Icons interpolate between the 1x1 preview positions and the tile slots, and the label moves
     with the tile.
   - No other Home item moves.
5. **Drag and drop.**
   - The drag preview of a large folder is the real tile at real size, with a 2x2 drop outline.
   - Accept feedback: a spring scale (replacing the instant 1.06 jump) and a highlight on the slot
     the dragged app would take.
   - Drop-into animates to that real slot (or the "more" slot). Moving away springs back.

**Motion.**
- Durations and springs come from the existing Launcher3 motion tokens and interpolators, so the
  motion matches the rest of Home.
- Everything respects the system animator duration scale, including "Remove animations".

## 4. Edge cases

- **Grid change or backup restore:** a folder that no longer fits loads 1x1 and its row is rewritten
  to 1x1. It is never deleted.
- **Widget removed or app uninstalled:** the freed cells go back to the folder.
- **Folder down to one app:** the remaining app is placed 1x1 at the folder's top-left cell (as
  today), and any covered widget is uncovered.
- **Landscape, foldable and two-panel layouts:** the frame and animations work per page. They are
  tested on the emulator's foldable/tablet profile. If a layout can't be made correct in this
  project, large folders are disabled on that layout and the limit is recorded, rather than
  shipped broken.

## 5. Testing

**Unit tests**
- `LargeFolderOverlap`: can-cover rules, owned cells, reclaim, and load span.
- `FolderResizeMath`: snap thresholds, direction to anchor, and blocked directions.
- `LargeFolderAnimationGeometry`: slot rectangles, clip endpoints, and icon start/end transforms.
- Loader regressions: an overlapping folder survives a reload in either load order, and an
  icon-overlap folder drops to 1x1 without being deleted.
- Existing `LargeFoldersTest` stays green.
- Full suites: `./gradlew testLawnWithQuickstepExpressiveDebugUnitTest` and
  `python3 -m unittest discover -s ci/tests`.

**Emulator**
- All five interactions, before and after, compared frame by frame.
- Overlap: the folder wins taps and long-presses where it covers the widget, and the widget works
  outside the folder.
- Restart: the overlap survives a launcher restart.
- Widget removal: the freed cells go to the folder.
- Resize in every direction, including blocked ones.
- TalkBack: handle actions and the footer action.
- "Remove animations" setting.

## 6. Delivery

- Worktree `~/ExpressiveWorktrees/large-folders-v2` on `claude/large-folders-v2`.
- Three commits, in order:
  1. overlap model and loading
  2. resize frame
  3. animations.
- Merge into `codex/pixel-parity` only after the unit suites, CI tests and emulator checks pass.
  A daily QA run then ships it.
- After release:
  - Update `docs/PIXEL_PARITY.md` and the XDA-014 backlog entry.
  - Draft an XDA follow-up for approval.
