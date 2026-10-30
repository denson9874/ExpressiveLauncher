package app.lawnchair.overview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.annotation.Keep
import app.lawnchair.util.RecentHelper
import app.lawnchair.util.TaskUtilLockState
import com.android.quickstep.TaskOverlayFactory
import com.android.quickstep.views.TaskContainer
import com.android.systemui.shared.recents.model.Task

@Keep
class TaskOverlayFactoryImpl(@Suppress("UNUSED_PARAMETER") context: Context) : TaskOverlayFactory() {

    override fun createOverlay(thumbnailView: TaskContainer) = TaskOverlay(thumbnailView)

    class TaskOverlay(
        taskThumbnailView: TaskContainer,
    ) : TaskOverlayFactory.TaskOverlay<LawnchairOverviewActionsView>(taskThumbnailView) {

        override fun initOverlay(
            task: Task?,
            thumbnail: Bitmap?,
            matrix: Matrix,
            rotated: Boolean,
        ) {
            super.initOverlay(task, thumbnail, matrix, rotated)
            // The base overlay hands the actions view callbacks without Share, Lens or Locked, and
            // LawnchairOverviewActionsView reads mCallbacks as ours, so a tap on Share or Lens
            // would throw ClassCastException. Replace them with callbacks that implement both.
            if (thumbnail != null) {
                actionsView.setCallbacks(OverlayUICallbacksImpl(isRealSnapshot(), task))
            }
        }

        private inner class OverlayUICallbacksImpl(
            isAllowedByPolicy: Boolean,
            task: Task?,
        ) : TaskOverlayFactory.TaskOverlay<LawnchairOverviewActionsView>.OverlayUICallbacksImpl(
            isAllowedByPolicy,
            task,
        ),
            OverlayUICallbacks {

            override fun onShare() {
                if (mIsAllowedByPolicy) {
                    endLiveTileMode { mImageApi.startShareActivity(null) }
                } else {
                    showBlockedByPolicyMessage()
                }
            }

            override fun onLens() {
                if (mIsAllowedByPolicy) {
                    endLiveTileMode { mImageApi.startLensActivity() }
                } else {
                    showBlockedByPolicyMessage()
                }
            }

            override fun onLocked(context: Context, task: Task) {
                val isLocked = !RecentHelper.isAppLocked(task.key.packageName, context)
                TaskUtilLockState.setTaskLockState(
                    context,
                    task.key.component,
                    isLocked,
                    task.key,
                )
            }
        }
    }

    sealed interface OverlayUICallbacks : TaskOverlayFactory.OverlayUICallbacks {
        fun onShare()
        fun onLens()
        fun onLocked(context: Context, task: Task)
    }
}
