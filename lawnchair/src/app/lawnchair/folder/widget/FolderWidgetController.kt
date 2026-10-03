package app.lawnchair.folder.widget

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.widget.Toast
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import android.content.pm.LauncherApps
import app.lawnchair.data.folderwidget.FolderWidgetStyleRepository
import app.lawnchair.folder.widget.ui.FolderWidgetAppPicker
import app.lawnchair.folder.widget.ui.FolderWidgetSettingsSheet
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import app.lawnchair.views.ComposeBottomSheet
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.CellLayout
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.PendingAddItemInfo
import com.android.launcher3.R
import com.android.launcher3.Workspace
import com.android.launcher3.celllayout.CellLayoutLayoutParams
import com.android.launcher3.folder.FolderIcon
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.model.data.FolderInfo
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.util.ComponentKey
import com.android.launcher3.util.Executors
import kotlinx.coroutines.runBlocking

/** Creates, converts and removes Folder widgets on Home. */
object FolderWidgetController {

    /** The 2x2 area (a strip on a one-cell-wide or -tall grid) that contains a folder's cell. */
    @JvmStatic
    fun makeWidgetTarget(cellX: Int, cellY: Int, columns: Int, rows: Int): GridRect {
        val (spanX, spanY) = FolderWidgetPlacement.normalizeSpan(1, 1, columns, rows)
        return GridRect(
            cellX.coerceAtMost(columns - spanX).coerceAtLeast(0),
            cellY.coerceAtMost(rows - spanY).coerceAtLeast(0),
            spanX,
            spanY,
        )
    }

    /**
     * Single cells for [count] apps put back on Home: free cells on [screenId] row by row, then on
     * the later screens in [screens], then on new screens after the last one.
     */
    @JvmStatic
    fun putBackPlan(
        count: Int,
        screenId: Int,
        screens: List<Int>,
        columns: Int,
        rows: Int,
        isVacant: (Int, GridRect) -> Boolean,
    ): List<Placement> {
        val plan = ArrayList<Placement>(count)
        fun fill(screen: Int, vacant: (Int, GridRect) -> Boolean) {
            for (y in 0 until rows) {
                for (x in 0 until columns) {
                    if (plan.size == count) return
                    val cell = GridRect(x, y, 1, 1)
                    if (vacant(screen, cell) && plan.none { it.screenId == screen && it.rect == cell }) {
                        plan += Placement(screen, cell)
                    }
                }
            }
        }
        fill(screenId, isVacant)
        screens.filter { it > screenId }.forEach { if (plan.size < count) fill(it, isVacant) }
        var next = maxOf(screenId, screens.maxOrNull() ?: screenId) + 1
        while (plan.size < count) {
            fill(next++) { _, _ -> true }
        }
        return plan
    }

    /**
     * The Remove dialog's choices. Deck layout has no app drawer, so its apps can only go back on
     * Home (Lawnchair's Remove target refuses folders there for the same reason).
     */
    @JvmStatic
    fun removeChoices(deckLayout: Boolean): List<Int> =
        if (deckLayout) {
            listOf(R.string.folder_widget_put_back)
        } else {
            listOf(R.string.folder_widget_put_back, R.string.folder_widget_remove_all)
        }

    /** "Lock Home screen" refuses every layout change: making, converting and removing widgets. */
    @JvmStatic
    fun isHomeLocked(launcher: Launcher): Boolean =
        PreferenceManager2.getInstance(launcher).lockHomeScreen.firstCached()

    /**
     * Makes a Home folder a 2x2 Folder widget at its place, pushing other items as a widget resize
     * from the folder's corner does. Shows a toast and changes nothing when no 2x2 area can be made.
     */
    @JvmStatic
    fun makeWidget(launcher: Launcher, icon: FolderIcon): Boolean {
        val info = icon.mInfo
        if (isHomeLocked(launcher) || info.container != Favorites.CONTAINER_DESKTOP ||
            FolderWidgets.isFolderWidget(info)
        ) {
            return false
        }
        val layout = icon.parent?.parent as? CellLayout ?: return false
        AbstractFloatingView.closeAllOpenViews(launcher, false)
        val lp = icon.layoutParams as CellLayoutLayoutParams
        val target = makeWidgetTarget(lp.cellX, lp.cellY, layout.countX, layout.countY)
        // As when the folder's bottom-right corner is dragged out (its top-left one in the last
        // column or row, where the area grows the other way).
        val direction = intArrayOf(if (target.x < lp.cellX) -1 else 1, if (target.y < lp.cellY) -1 else 1)
        layout.markCellsAsUnoccupiedForView(icon)
        if (!layout.createAreaForResize(
                target.x, target.y, target.spanX, target.spanY, icon, direction.copyOf(), false,
            )
        ) {
            // A failed search changes no temp state, so only the folder's own cells need restoring.
            layout.markCellsAsOccupiedForView(icon)
            Toast.makeText(launcher, R.string.folder_widget_no_room, Toast.LENGTH_SHORT).show()
            return false
        }
        lp.tmpCellX = target.x
        lp.tmpCellY = target.y
        lp.cellHSpan = target.spanX
        lp.cellVSpan = target.spanY
        // Commits the folder's new cells and span, and every pushed item, as a resize does.
        layout.createAreaForResize(target.x, target.y, target.spanX, target.spanY, icon, direction.copyOf(), true)
        info.cellX = target.x
        info.cellY = target.y
        info.spanX = target.spanX
        info.spanY = target.spanY
        info.setOption(FolderInfo.FLAG_FOLDER_WIDGET, true, null)
        launcher.modelWriter.updateItemInDatabase(info)
        val oldWidth = icon.width
        val oldHeight = icon.height
        launcher.workspace.removeWorkspaceItem(icon)
        val newView = launcher.itemInflater.inflateItem(info) as? FolderWidgetView
        if (newView != null) {
            launcher.workspace.addInScreen(newView, info)
            (newView.parent?.parent as? CellLayout)?.shortcutsAndWidgets?.measureChild(newView)
            morphMakeWidget(newView, oldWidth, oldHeight, target.x < lp.cellX, target.y < lp.cellY)
        } else {
            addView(launcher, info)
        }
        return true
    }

    /** Makes a Folder widget a 1x1 Home folder in its top-left cell (its other cells are freed). */
    @JvmStatic
    fun makeNormalFolder(launcher: Launcher, widget: FolderWidgetView): Boolean {
        val info = widget.mInfo
        if (isHomeLocked(launcher) || !FolderWidgets.isFolderWidget(info)) return false
        // Closing the resize frame commits its size while the widget is still on Home (M8).
        AbstractFloatingView.closeAllOpenViews(launcher, false)

        val completeSwap = {
            launcher.workspace.removeWorkspaceItem(widget)
            info.spanX = 1
            info.spanY = 1
            info.setOption(FolderInfo.FLAG_FOLDER_WIDGET, false, null)
            // One write for the span and the flag: the loader turns any Home folder larger than 1x1
            // back into a widget.
            launcher.modelWriter.updateItemInDatabase(info)
            deleteStyle(launcher, info.id)
            addView(launcher, info)
        }

        val panel = widget.panel
        if (panel.width <= 0 || panel.height <= 0) {
            completeSwap()
            return true
        }

        val dp = launcher.deviceProfile
        val targetScaleX = (dp.cellWidthPx.toFloat() / panel.width).coerceIn(0.1f, 1f)
        val targetScaleY = (dp.cellHeightPx.toFloat() / panel.height).coerceIn(0.1f, 1f)
        panel.pivotX = 0f
        panel.pivotY = 0f

        var finished = false
        val onFinish = {
            if (!finished) {
                finished = true
                completeSwap()
            }
        }

        val animX = SpringAnimation(panel, DynamicAnimation.SCALE_X)
            .setSpring(
                SpringForce(targetScaleX)
                    .setStiffness(SpringForce.STIFFNESS_MEDIUM)
                    .setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY),
            )
            .addEndListener { _, _, _, _ -> onFinish() }
        val animY = SpringAnimation(panel, DynamicAnimation.SCALE_Y)
            .setSpring(
                SpringForce(targetScaleY)
                    .setStiffness(SpringForce.STIFFNESS_MEDIUM)
                    .setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY),
            )

        animX.start()
        animY.start()
        return true
    }

    private fun morphMakeWidget(
        widget: FolderWidgetView,
        oldWidth: Int,
        oldHeight: Int,
        fromRight: Boolean,
        fromBottom: Boolean,
    ) {
        val panel = widget.panel
        panel.post {
            if (panel.width <= 0 || panel.height <= 0) return@post
            val scaleX = if (oldWidth > 0) (oldWidth.toFloat() / panel.width).coerceIn(0.1f, 1f) else 0.5f
            val scaleY = if (oldHeight > 0) (oldHeight.toFloat() / panel.height).coerceIn(0.1f, 1f) else 0.5f
            panel.pivotX = if (fromRight) panel.width.toFloat() else 0f
            panel.pivotY = if (fromBottom) panel.height.toFloat() else 0f
            panel.scaleX = scaleX
            val animX = SpringAnimation(panel, DynamicAnimation.SCALE_X)
                .setSpring(
                    SpringForce(1f)
                        .setStiffness(SpringForce.STIFFNESS_MEDIUM)
                        .setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY),
                )
                .addEndListener { _, _, _, _ ->
                    panel.pivotX = panel.width / 2f
                    panel.pivotY = panel.height / 2f
                }
            val animY = SpringAnimation(panel, DynamicAnimation.SCALE_Y)
                .setSpring(
                    SpringForce(1f)
                        .setStiffness(SpringForce.STIFFNESS_MEDIUM)
                        .setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY),
                )
            animX.start()
            animY.start()
        }
    }

    /** The menu's Remove: asks whether to put the widget's apps back on Home, then removes it. */
    @JvmStatic
    fun confirmRemove(launcher: Launcher, widget: FolderWidgetView) {
        val info = widget.mInfo
        if (isHomeLocked(launcher)) return
        if (info.getContents().isEmpty()) {
            remove(launcher, widget, putBack = false)
            return
        }
        removeDialog(launcher, info) { putBack -> remove(launcher, widget, putBack) }.show()
    }

    /**
     * Removes a Folder widget. With [putBack] its apps become Home icons in free cells and only the
     * widget is deleted; otherwise it goes with its apps as a folder dropped on Remove does (Undo
     * included; the apps stay in the app drawer).
     */
    @JvmStatic
    fun remove(launcher: Launcher, widget: FolderWidgetView, putBack: Boolean) {
        val info = widget.mInfo
        // Also dismisses an earlier Undo bar, committing its delete before this one starts, and
        // commits an open resize frame while the widget is still on Home (M8).
        AbstractFloatingView.closeAllOpenViews(launcher, false)
        deleteStyle(launcher, info.id)
        if (putBack) {
            launcher.workspace.removeWorkspaceItem(widget)
            putBackApps(launcher, info)
        } else {
            launcher.modelWriter.prepareToUndoDelete()
            launcher.dropTargetHandler.onDeleteComplete(info, widget)
        }
    }

    /**
     * A Folder widget with apps dropped on Remove (its view has already left Home and the drop
     * target has deferred deletes for Undo): asks whether to put its apps back. Remove all runs
     * [removeAll], the usual delete with Undo; cancelling restores the widget. Returns false, with
     * nothing shown, when the widget has no apps.
     */
    @JvmStatic
    fun confirmRemoveAfterDrop(launcher: Launcher, info: FolderInfo, removeAll: Runnable): Boolean {
        if (info.getContents().isEmpty()) return false
        var chosen = false
        removeDialog(launcher, info) { putBack ->
            chosen = true
            if (putBack) {
                putBackApps(launcher, info)
                launcher.modelWriter.commitDelete()
                deleteStyle(launcher, info.id)
            } else {
                removeAll.run()
                deleteStyle(launcher, info.id)
            }
        }.setOnDismissListener {
            if (chosen) return@setOnDismissListener
            // Cancelled: nothing was deleted; the widget goes back where it was, unless Home was
            // rebound meanwhile and shows it already.
            launcher.modelWriter.abortDelete()
            if (launcher.workspace.getViewByItemId(info.id) == null) {
                ensureScreen(launcher.workspace, info.screenId)
                addView(launcher, info)
            }
        }.show()
        return true
    }

    private fun deleteStyle(context: Context, folderId: Int) {
        Executors.MODEL_EXECUTOR.execute {
            runBlocking {
                FolderWidgetStyleRepository.INSTANCE.get(context).delete(folderId)
            }
        }
    }

    private fun removeDialog(
        launcher: Launcher,
        info: FolderInfo,
        onChoice: (putBack: Boolean) -> Unit,
    ): AlertDialog.Builder {
        val deckLayout = PreferenceManager2.getInstance(launcher).deckLayout.firstCached()
        val name = info.title?.takeIf { it.isNotBlank() } ?: launcher.getString(R.string.unnamed_folder)
        val builder = AlertDialog.Builder(launcher)
            .setTitle(launcher.getString(R.string.folder_widget_remove_title, name))
            .setNeutralButton(android.R.string.cancel, null)
        removeChoices(deckLayout).forEach { choice ->
            val putBack = choice == R.string.folder_widget_put_back
            val listener = DialogInterface.OnClickListener { _, _ -> onChoice(putBack) }
            if (putBack) builder.setPositiveButton(choice, listener) else builder.setNegativeButton(choice, listener)
        }
        return builder
    }

    /**
     * Moves the folder's apps to free Home cells (same page, later pages, new pages), then deletes
     * the folder row. The apps leave the folder before the row goes, so none is ever orphaned.
     */
    private fun putBackApps(launcher: Launcher, info: FolderInfo) {
        val workspace = launcher.workspace
        // Home may have been rebound while the Remove dialog showed.
        workspace.getViewByItemId(info.id)?.let { workspace.removeWorkspaceItem(it) }
        val idp = launcher.deviceProfile.inv
        val apps = info.getContents().toList()
        val screens = workspace.screenOrder.toArray().filter { it >= 0 }
        val plan = putBackPlan(apps.size, info.screenId, screens, idp.numColumns, idp.numRows) { screen, cell ->
            val layout = workspace.getScreenWithId(screen)
            layout == null || layout.isRegionVacant(cell.x, cell.y, cell.spanX, cell.spanY)
        }
        apps.zip(plan).forEach { (app, placement) ->
            ensureScreen(workspace, placement.screenId)
            launcher.modelWriter.addOrMoveItemInDatabase(
                app,
                Favorites.CONTAINER_DESKTOP,
                placement.screenId,
                placement.rect.x,
                placement.rect.y,
            )
            addView(launcher, app)
        }
        info.getContents().clear()
        launcher.modelWriter.deleteItemFromDatabase(info, "folder widget removed; apps put back on Home")
    }

    /** Re-creates a missing page (a dropped widget's page is stripped when it empties) in id order. */
    private fun ensureScreen(workspace: Workspace<*>, screenId: Int) {
        if (workspace.getScreenWithId(screenId) != null) return
        val index = workspace.screenOrder.toArray().count { it in 0 until screenId }
        workspace.insertNewWorkspaceScreen(screenId, index)
    }

    private fun addView(launcher: Launcher, info: ItemInfo) {
        val view = launcher.itemInflater.inflateItem(info) ?: return
        launcher.workspace.addInScreen(view, info)
    }

    /**
     * Creates a Folder widget dropped from the widget picker, saves it, adds it to Home,
     * and shows the app picker to select its apps and title.
     */
    @JvmStatic
    fun createFromPicker(launcher: Launcher, info: PendingAddItemInfo) {
        val idp = launcher.deviceProfile.inv
        val (spanX, spanY) = FolderWidgetPlacement.normalizeSpan(info.spanX, info.spanY, idp.numColumns, idp.numRows)
        val folderInfo = FolderInfo().apply {
            title = launcher.getString(R.string.folder_widget_label)
            container = Favorites.CONTAINER_DESKTOP
            screenId = info.screenId
            cellX = info.cellX
            cellY = info.cellY
            this.spanX = spanX
            this.spanY = spanY
            setOption(FolderInfo.FLAG_FOLDER_WIDGET, true, null)
        }
        launcher.modelWriter.addItemToDatabase(
            folderInfo,
            folderInfo.container,
            folderInfo.screenId,
            folderInfo.cellX,
            folderInfo.cellY,
        )
        ensureScreen(launcher.workspace, folderInfo.screenId)
        val view = launcher.itemInflater.inflateItem(folderInfo) as? FolderWidgetView
        if (view != null) {
            launcher.workspace.addInScreen(view, folderInfo)
            (view.parent?.parent as? CellLayout)?.shortcutsAndWidgets?.measureChild(view)
            launcher.workspace.removeExtraEmptyScreenDelayed(0, false, null)
            showAppPicker(launcher, view)
        }
    }

    /**
     * Opens the app picker sheet for [widget] to pick its apps and name.
     */
    @JvmStatic
    fun showAppPicker(launcher: Launcher, widget: FolderWidgetView) {
        if (isHomeLocked(launcher)) return
        AbstractFloatingView.closeAllOpenViews(launcher, false)
        val currentKeys = widget.mInfo.getContents().mapNotNull { item ->
            val cn = item.targetComponent ?: item.intent?.component ?: return@mapNotNull null
            ComponentKey(cn, item.user)
        }
        ComposeBottomSheet.show(launcher) {
            FolderWidgetAppPicker(
                initialTitle = widget.mInfo.title?.toString() ?: "",
                initialSelectedKeys = currentKeys,
                onSave = { newTitle, selectedKeys ->
                    close(true)
                    applyAppPickerResult(launcher, widget, newTitle, selectedKeys)
                },
                onCancel = {
                    close(true)
                },
            )
        }
    }

    /**
     * Opens the settings sheet for [widget] to configure its layout and styling.
     */
    @JvmStatic
    fun showSettingsSheet(launcher: Launcher, widget: FolderWidgetView) {
        if (isHomeLocked(launcher)) return
        AbstractFloatingView.closeAllOpenViews(launcher, false)
        ComposeBottomSheet.show(launcher) {
            FolderWidgetSettingsSheet(
                launcher = launcher,
                widget = widget,
                onDismiss = { close(true) },
            )
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun applyAppPickerResult(
        launcher: Launcher,
        widget: FolderWidgetView,
        title: String,
        selectedKeys: List<ComponentKey>,
    ) {
        val currentItems = widget.mInfo.getContents().toList()
        val currentKeys = currentItems.mapNotNull { item ->
            val cn = item.targetComponent ?: item.intent?.component ?: return@mapNotNull null
            ComponentKey(cn, item.user)
        }
        val (toAdd, toRemove) = FolderWidgetAppSelection.diff(currentKeys, selectedKeys)

        toRemove.forEach { key ->
            val item = widget.mInfo.getContents().firstOrNull { item ->
                val cn = item.targetComponent ?: item.intent?.component
                cn != null && ComponentKey(cn, item.user) == key
            }
            if (item != null) {
                widget.folder.removeFolderContent(false, item)
                launcher.modelWriter.deleteItemFromDatabase(item, "removed via folder widget app picker")
            }
        }

        val launcherApps = launcher.getSystemService(LauncherApps::class.java)
        toAdd.forEach { key ->
            val appInfo = launcher.appsView?.appsStore?.getApp(key)
                ?: launcherApps?.getActivityList(key.componentName.packageName, key.user)
                    ?.firstOrNull { it.componentName == key.componentName }
                    ?.let { AppInfo(launcher, it, key.user) }
            val workspaceItem = appInfo?.makeWorkspaceItem(launcher)
            if (workspaceItem != null) {
                widget.folder.addFolderContent(workspaceItem)
            }
        }

        updateWidgetTitle(widget.mInfo, launcher.modelWriter, widget::onTitleChanged, title)
    }

    @androidx.annotation.VisibleForTesting
    internal fun updateWidgetTitle(
        info: FolderInfo,
        modelWriter: com.android.launcher3.model.ModelWriter?,
        onTitleChanged: (CharSequence) -> Unit,
        newTitle: String,
    ): Boolean {
        val cleanTitle = newTitle.trim()
        if ((info.title?.toString() ?: "") != cleanTitle) {
            info.setTitle(cleanTitle, modelWriter)
            onTitleChanged(cleanTitle)
            return true
        }
        return false
    }
}
