package com.android.quickstep.recents.ui.mapper

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.ShapeDrawable
import android.view.Surface
import app.lawnchair.overview.TaskOverlayFactoryImpl
import com.android.quickstep.TaskOverlayFactory
import com.android.quickstep.recents.ui.viewmodel.TaskData
import com.android.quickstep.task.thumbnail.TaskThumbnailUiState
import com.android.quickstep.task.thumbnail.TaskThumbnailUiState.Snapshot
import com.android.systemui.shared.recents.model.Task
import com.android.systemui.shared.recents.model.ThumbnailData
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * 5.0.8: what an Overview card draws, and which callbacks its action row gets.
 *
 * A snapshot that came back without a bitmap (no snapshot yet, App Lock, a snapshot removed for
 * sensitive content) used to map to Uninitialized, which draws a see-through card. It shows the
 * app's colour now, as upstream does.
 *
 * The Overview action row casts its callbacks to [TaskOverlayFactoryImpl.OverlayUICallbacks] for
 * Share and Lens, so Lawnchair's overlay must install its own callbacks in initOverlay: with the
 * base ones, a tap on Share or Lens threw ClassCastException.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class OverviewCardStateTest {

    @Test
    fun snapshotWithoutBitmap_drawsAppColour() {
        val state = TaskUiStateMapper.toTaskThumbnailUiState(taskData(ThumbnailData()))

        assertThat(state).isEqualTo(TaskThumbnailUiState.BackgroundOnly(BACKGROUND_COLOR))
    }

    @Test
    fun noSnapshot_drawsAppColour() {
        val state = TaskUiStateMapper.toTaskThumbnailUiState(taskData(null))

        assertThat(state).isEqualTo(TaskThumbnailUiState.BackgroundOnly(BACKGROUND_COLOR))
    }

    @Test
    fun lockedTask_drawsAppColour() {
        val state =
            TaskUiStateMapper.toTaskThumbnailUiState(taskData(snapshot()).copy(isLocked = true))

        assertThat(state).isEqualTo(TaskThumbnailUiState.BackgroundOnly(BACKGROUND_COLOR))
    }

    @Test
    fun snapshotWithBitmap_drawsSnapshot() {
        val thumbnail = snapshot()

        val state = TaskUiStateMapper.toTaskThumbnailUiState(taskData(thumbnail))

        assertThat(state)
            .isEqualTo(
                TaskThumbnailUiState.SnapshotSplash(
                    Snapshot(thumbnail.thumbnail!!, Surface.ROTATION_0, BACKGROUND_COLOR),
                    ICON,
                )
            )
    }

    @Test
    fun lawnchairOverlay_installsItsOwnCallbacks() {
        val overlay = TaskOverlayFactoryImpl.TaskOverlay::class.java
        val initOverlay =
            overlay.getDeclaredMethod(
                "initOverlay",
                Task::class.java,
                Bitmap::class.java,
                Matrix::class.java,
                Boolean::class.javaPrimitiveType,
            )
        assertThat(initOverlay.declaringClass).isEqualTo(overlay)

        val callbacks =
            overlay.declaredClasses.filter {
                TaskOverlayFactoryImpl.OverlayUICallbacks::class.java.isAssignableFrom(it)
            }
        assertThat(callbacks).hasSize(1)
        assertThat(TaskOverlayFactory.OverlayUICallbacks::class.java.isAssignableFrom(callbacks[0]))
            .isTrue()
    }

    private fun snapshot() =
        ThumbnailData(
            thumbnail = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888),
            rotation = Surface.ROTATION_0,
        )

    private fun taskData(thumbnailData: ThumbnailData?) =
        TaskData.Data(
            1,
            packageName = "com.test",
            title = "Task 1",
            titleDescription = "Title Description 1",
            icon = ICON,
            thumbnailData = thumbnailData,
            backgroundColor = BACKGROUND_COLOR,
            isLocked = false,
            isLiveTile = false,
            remainingAppTimerDuration = null,
        )

    private companion object {
        val ICON = ShapeDrawable()
        val BACKGROUND_COLOR = Color.rgb(1, 2, 3)
    }
}
