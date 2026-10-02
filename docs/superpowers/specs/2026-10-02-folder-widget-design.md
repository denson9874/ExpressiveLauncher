# Folder widget — design

Date: 2026-10-02. Branch: `claude/folder-widget` (from `codex/pixel-parity` at 68ee55a, v4.0.3 (52)).
Status: approved section by section in conversation; pending written-spec review.

## Background

Large Home folders shipped in QA 4.0.2 (2x2 tile) and were reworked in 4.0.3 (tiles cover
widgets, 1x1↔2x2 resize handles, tile animations). The user and testers are not happy with how
folders are managed and expanded:

- Telegram (Expressive Launcher Feedback, 2026-10-02 02:04): Adriano, replying to the 4.0.3 post,
  "Folders are not centered". The user answered that placement, expansion and resizing will be
  reworked.
- Reproduced on the emulator (debug build of 4.0.3 code): a folder made 2x2 draws a small square
  tile low in its 2x2 area, with a large gap above and the label colliding with the bottom edge. The
  tile covers the clock widget next to it. Only 1x1 and 2x2 exist, and extra apps are squeezed into
  a "more" slot.

The user asked to adapt how Hanks' **Folder Widget** (`pub.hanks.appfolderwidget`, video
"Make big folder! Enhancing your Android launcher!") handles big folders:

- A big folder is a widget on Home: resized cell by cell with the widget resize frame, to many
  sizes (2x2, 3x3, 4x2, 2x3, 1x4, 1x5, custom M×N).
- Its apps fill the panel as a grid (columns × rows), optionally with app names and a folder
  name. Background color, radius and padding are configurable.
- "Grid (scroll)": the grid scrolls vertically inside the panel when there are more apps than fit.
  Tapping an app launches it directly. A touch on the lower-right corner opens the full folder.
- Being a widget, it never overlaps other Home items.

## Decisions (from the conversation)

| Topic | Decision |
|---|---|
| Model | **B: a built-in Folder widget**, separate from normal folders. Normal folders go back to plain 1x1 Pixel folders. |
| Overlap | Like a widget: takes its own cells, pushes other items or stops; never covers anything. 4.0.3's overlap is removed. |
| Overflow | Scroll inside the panel. |
| Grid | Auto (about 1.5 apps per Home cell), with a per-widget column override (2–6). |
| Text | App names under icons: on. Folder name in a header row: on (tap opens the full folder). Name below the panel: off. Each is a per-widget toggle. |
| Styling | Per widget: background opacity, background color, corner radius, icon size. |
| Pro | Folder widgets, resizing, scrolling, columns and the text toggles are free. The four styling options are Pro. |
| Long-press | On an app: that app's shortcuts menu; dragging takes it out. On the header or empty space: picks up the widget. |
| Existing large folders | Converted automatically into Folder widgets (same size, name, apps and place; moved to free cells if they cover something). |
| Contents | Like a folder: a Home icon dropped on the widget moves inside; a drawer app is added; the full folder view has "Add apps"; removing the widget offers to put its apps back on Home. |

## Goals

- A Folder widget users can add from the widget picker, resize to any size of at least two cells,
  scroll, and launch apps from directly.
- The panel fills its cells and its grid is centered (fixes "Folders are not centered").
- Folder widgets behave exactly like widgets for placement, reorder, resize and drag.
- Existing 4.0.2/4.0.3 large folders survive the update as Folder widgets. Nothing is deleted.
- All interactions animate from the real geometry (real icon views, real panel bounds).

## Non-goals

- Folder widgets in the dock or the app drawer.
- Hanks extras: shortcut types other than apps (settings, files, web pages, shell), circle/list
  layouts, templates, folder-name shadow, notification-dot styles.
- Horizontal scrolling inside the panel.
- Covering or overlapping any Home item.
- Making Folder widgets usable in other launchers (it is an in-process widget).

## 1. What people see and do

**Adding one**
- **Widget picker:** a built-in "Folder" widget, 2x2 by default. After it is dropped, an app picker
  opens to choose apps and a name. Cancelling the picker leaves an empty widget with an
  "Add apps" button.
- **From an existing folder:** long-press a normal Home folder → **Make widget**. Launcher3 shows no
  menu for folders today, so a small menu is added (like an app's shortcuts menu; moving the finger
  closes it and starts the usual drag). The same action is in the open folder's footer (where 4.0.3
  had the size button) and is a TalkBack action. The folder becomes a 2x2 Folder widget at its
  place, making room the way a dropped widget does. If no 2x2 area can be made, a toast says so and
  nothing changes.
- **Back to a folder:** the widget's menu has **Make normal folder**: it becomes a 1x1 folder in its
  top-left cell (the other cells are freed).

**Layout**
- The panel fills the widget's cells with the same insets Home uses for widgets, so it lines up with
  neighbouring widgets.
- Header (on by default): one row with the folder name, ellipsized.
- Below it: a grid of real app icons with names, centered horizontally. When everything fits, the
  rows are centered vertically in the space below the header; when apps overflow, the grid starts at
  the top and scrolls.
- With the header hidden, a small round "open" button sits in the panel's lower-right corner.
- Optional folder name below the panel (off by default), styled like a Home icon label.

**Using it**
- Tap an app to launch it.
- More apps than fit: swipe up/down inside the panel to scroll (fling, Android stretch overscroll,
  a thin scrollbar that fades). Horizontal swipes still change Home pages. When everything fits,
  Home's vertical gestures (app drawer, notification shade) work over the widget as usual.
- Tap the header, or the corner button, to open the full folder: rename, reorder, add (picker) and
  remove apps, plus a gear for widget settings.

**Managing it**
- Long-press an app: its shortcuts menu; drag to take it out of the widget.
- Long-press the header or empty space: shows the widget menu (Customize, Make normal folder,
  Remove) and picks up the widget. Moving starts the drag and closes the menu. Releasing without
  moving keeps the menu and shows the resize frame (cell by cell, pushing other items, never
  covering).
- Drop a Home icon on it: the icon moves inside. Drop an app from the drawer: it is added.
- Remove (drag to Remove, or the menu): a dialog offers **Put apps back on Home** (placed in free
  cells on that page, then later pages, then a new page) or **Remove all** (the apps stay in the app
  drawer, as when a normal folder is removed).

**Unchanged:** normal folders, the dock and the app drawer. Normal folders are 1x1 only again (no
resize frame, no size button in the open folder).

## 2. Architecture and storage

**A Folder widget is a flagged folder row.**
- Stored as a normal desktop folder row in the Home database, with a new option bit
  `FolderInfo.FLAG_FOLDER_WIDGET` (a high bit, unlikely to collide with AOSP) and its size in
  `spanX`/`spanY`. Its apps are ordinary folder items (`container` = the folder's id).
- Why not a real widget row (`LauncherAppWidgetInfo`)? Widget plumbing would come free, but the app
  list would need a second store, and moving apps in/out, uninstall cleanup, notification dots,
  Home backup and the full-folder screen would all have to be rebuilt. Folder rows already do all of
  that, and converting 4.0.3 large folders is just setting the flag.

**Widget picker entry.** A built-in custom widget (Launcher3 `CustomWidgetManager`, registered
in-process by calling `onPluginConnected` with an Expressive `CustomWidgetPlugin`) supplies the
"Folder" entry: label, preview, 2x2 default size, minimum two cells, resizable both ways. When that
entry is dropped on Home, Expressive creates a flagged folder at the drop cells instead of a widget
row, then opens the app picker. If the custom-widget info can't be created (it copies an installed
provider's info, and a device with none would return null), the entry is simply absent and
"Make widget" still works.

**New units**
- `FolderWidgetView` (`app.lawnchair.folder.widget`): subclass of `FolderIcon`, so existing
  folder code paths keep working (drop to add, open folder, dots, accessibility). Holds the header,
  a `RecyclerView` grid of real `BubbleTextView`s, the corner button and the optional name below.
  Bound when a flagged folder is bound to Home; normal folders keep the stock `FolderIcon`.
- `FolderWidgetGridMath` (pure Kotlin, unit-tested): from the panel size, header/label settings,
  icon-size scale and font scale it returns columns, column width, icon size, whether labels fit,
  row height, visible rows, and the centering offsets. Auto columns =
  `clamp(round(spanX × 1.5), 1, 6)`; a column override (2–6) wins. Icon size =
  `min(Home icon size, 0.75 × column width) × icon scale`. Labels are hidden automatically when a
  row with a label would not fit.
- `FolderWidgetResizeFrame`: replaces 4.0.3's snap-only `FolderResizeFrame`. Same behaviour as
  Launcher3's `AppWidgetResizeFrame`: handles on four sides, cell-by-cell steps past the 0.66 cell
  threshold, `CellLayout.createAreaForResize` to push other items, the same commit and dismiss
  rules. Minimum two cells, maximum the grid.
- `FolderWidgetStyleRepository`: per-widget settings in a new table of Expressive's Room database
  (`AppDatabase`, version 3 → 4 with a migration): folder id, columns override, show names, show
  header, show name below, background color, background opacity, corner radius, icon scale. A
  missing row means defaults. Rows are deleted with their folder.
- `FolderWidgetMigration` (pure placement logic + loader hook): see below.

**Workspace and CellLayout.** Flagged folders are treated like widgets everywhere placement is
decided: reorder, drag outline (full size), drop, resize, and "find a free area". They push and
are pushed, and never overlap.

**One-time migration (loader).**
- Any desktop folder with a span larger than 1x1 (4.0.2/4.0.3 large folders) gets
  `FLAG_FOLDER_WIDGET`, keeping its span, name, apps and cells.
- If its cells overlap another item or a widget (allowed in 4.0.3), it moves to the nearest free
  area of its size on the same page, then later pages, then a new page. Its row is rewritten.
- If it is outside the grid (grid or display-size change), it shrinks to the largest size of at
  least two cells that fits at its position, or moves as above.
- Never deleted. Flagged folders are exempt from the loader's "remove empty and single-item
  folders" clean-up.

**Removed (4.0.3 large-folder mode).** `LargeFolderOverlap`, `LargeFolderLayout`, the overlap and
ownership code in `CellLayout`, `Workspace`, `LoaderCursor` and `WorkspaceItemProcessor`,
`LargeFolderTile` (painted tile), the 1x1↔2x2 `FolderResizeFrame`, `LargeFolderAnimationGeometry`
and the slot-based branches in `FolderAnimationManager`, `FloatingIconView` and `DragView`, the
open-folder size button, and the large-folder TalkBack actions. About 65 call sites refer to
`LargeFolders`/`isLarge`; each is removed or redirected to the widget path.

## 3. Gestures, drag and drop, animations

**Touch**
- Tap an app: `ItemClickHandler` with the real icon as the launch origin.
- Vertical drag inside the grid while it can scroll: on touch-down the widget calls
  `DragLayer.requestDisallowInterceptTouchEvent(true)` (Launcher3's scrollable-widget pattern from
  `LauncherAppWidgetHostView`), so Home's state controllers (app drawer, Lawnchair
  `VerticalSwipeTouchController`) don't take it. When the grid can't scroll, nothing is disallowed.
  Horizontal drags still page Home.
- Long-press an app: `PopupContainerWithArrow` shortcuts menu; dragging starts a drag whose source is
  the widget (as the open `Folder` is for its icons). Dropping elsewhere moves the app out; dropping
  back on the widget cancels.
- Long-press the header or empty space: the widget menu plus a standard Workspace drag of the whole
  widget; release without movement keeps the menu and shows `FolderWidgetResizeFrame`.
- Long-press a normal 1x1 folder: the new folder menu (Make widget) plus the usual drag.

**Dropping onto the widget**
- Accepts app shortcuts only. While one hovers, the panel shows an outline and a small spring scale,
  and the grid scrolls to the end.
- Drop: the app is appended and its icon animates into its cell. A Home icon moves in; a drawer app is
  added. Folders and widgets aren't accepted and place/push as usual.

**Animations** (Launcher3 motion tokens; all respect the system animator scale, including
"Remove animations")
1. **Open the full folder** (header or corner): the `Folder` panel is positioned centered over the
   widget and clamped to the screen. Its reveal clip starts as the widget's rounded rectangle (same
   bounds and radius) and grows into the panel. Visible icons translate/scale from their grid cells
   to their panel cells; the others fade in. The widget's grid is hidden for exactly the animation.
2. **Close:** the exact reverse, landing back on the cells at the same scroll position.
3. **Launch an app:** the standard Launcher3 launch from the tapped icon view. Returning Home, the
   app shrinks into its icon if it is visible in the grid, otherwise into the widget's center.
4. **Resize:** the frame follows the finger; when the size steps, the panel re-lays out with a short
   transition (as widgets do), and pushed items use the standard reorder animation.
5. **Make widget / Make normal folder:** the 1x1 folder icon morphs into the panel (and back) with a
   short spring; neighbours reorder normally.

## 4. Settings and Pro

- **Sheet:** a `ComposeBottomSheet` opened from the widget's long-press menu ("Customize") and from
  the gear in the full folder view, with a live preview at the widget's current size.
- **Free rows:** Columns (Auto, 2–6), Show app names, Show header, Show name below.
- **Pro rows** (locked with the standard Pro badge and upgrade tap for free users): Background color
  (Material You default or custom), Background opacity (0–100 %), Corner radius (square to fully
  round), Icon size (70–110 %).
- **Defaults** follow the existing Folder settings so a new widget matches Home: color =
  `folderColor`, opacity = `folderBackgroundOpacity`, corner radius = the system widget corner
  radius (`android:dimen/system_app_widget_background_radius`), so it matches neighbouring widgets.
- Changes apply live; **Reset** returns to defaults.

## 5. Accessibility

- The widget announces "Folder widget, <name>, <N> apps"; each app is its own focusable item.
- Widget actions: Open folder, Customize, the standard widget resize actions (wider, narrower,
  taller, shorter), Make normal folder, Remove. Grid scrolling uses `RecyclerView` scroll actions.
- Large font or display size: `FolderWidgetGridMath` drops columns, then hides labels, before
  anything clips; labels ellipsize.

## 6. Edge cases

- Zero apps: the widget stays and shows an "Add apps" button (tap → picker). One app: a normal
  one-app grid.
- Uninstalled apps disappear from the grid; disabled, suspended and work-profile apps look as they do
  on Home.
- Grid or display-size change and backup restore: see the migration rules; never deleted. Styling
  falls back to defaults if the folder's id changed.
- Foldables, landscape and two-panel layouts: per page, like any widget.
- Search, notification dots (per icon, following Home's dot setting) and app labels behave as on
  Home.

## 7. Testing

**Unit (JVM)**
- `FolderWidgetGridMath`: columns (auto/override), icon size, label fit, visible rows and centering
  offsets for 1x2…4x5 at several densities and font scales.
- `FolderWidgetMigration`: overlap → nearest free area (same page, later pages, new page), out of
  grid → shrink or move, never delete.
- Style repository: defaults, persistence, deletion with the folder; Pro gating of the four styling
  setters.
- Drop and drag-out rules (accepted item types, move vs add).

**Robolectric**
- `FolderWidgetView` fills its cells and its grid is centered (regression test for "Folders are not
  centered").
- Scrolling is enabled only when apps overflow; header and corner button open the folder.

**Loader regressions**
- A 4.0.3 2x2 folder covering a widget converts and moves; an empty flagged folder survives a
  reload; normal 1x1 folders are unchanged.

**Device** (the user's development phone over adb, plus emulator-5590)
- Add from the picker; Make widget / Make normal folder; resize in every direction with pushing.
- Scroll vs Home gestures (up, down, left, right, flings); long-press shortcuts and drag-out; drops
  from Home and the drawer.
- Open, close, launch and return animations, compared frame by frame (`screenrecord`).
- TalkBack, "Remove animations", launcher restart, and an upgrade from 4.0.3 with a large folder
  covering a widget.

**Suites:** `./gradlew testLawnWithQuickstepExpressiveDebugUnitTest` and
`python3 -m unittest discover -s ci/tests`.

## 8. Delivery

- Worktree `~/ExpressiveWorktrees/folder-widget` on `claude/folder-widget`. Milestones, each a
  commit with its tests:
  1. Retire the 4.0.3 large-folder mode; flag and migrate existing large folders.
  2. `FolderWidgetView` and `FolderWidgetGridMath` (layout, header, names, scrolling, launch).
  3. Widget behaviour: picker entry and app picker, resize frame, reorder/push, drag in and out,
     long-press menus, remove/put back, Make widget / Make normal folder.
  4. Open/close and launch/return animations.
  5. Settings sheet and Pro styling.
- Merge into `codex/pixel-parity` after the unit and CI suites and the device checks pass; a daily QA
  run ships it.
- After release: update `docs/PIXEL_PARITY.md` and the XDA-014 backlog entry; draft a Telegram reply
  to Adriano and an XDA follow-up for the user's approval before anything is posted.

## Risks to check first

- Scroll vs Lawnchair's `VerticalSwipeTouchController`: confirm the disallow-intercept path covers it
  on real hardware before building the rest of the gestures.
- Drag source for apps inside a closed widget: the open `Folder` is the usual source; the widget
  needs the same remove-on-drop semantics without opening the folder.
- Binding a `FolderIcon` subclass from `Workspace`/`LauncherDelegate` without breaking code that
  inflates `FolderIcon` from XML.
- Menus for folders and Folder widgets: `PopupContainerWithArrow` is built around `BubbleTextView`;
  folders may need an `ArrowPopup` variant.
- Room migration 3 → 4 on existing installs.
