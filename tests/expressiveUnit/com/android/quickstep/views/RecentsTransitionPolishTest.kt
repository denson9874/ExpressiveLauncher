package com.android.quickstep.views

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import com.android.launcher3.R
import com.android.launcher3.views.ScrimColors
import com.android.launcher3.views.ScrimView
import com.android.quickstep.task.thumbnail.TaskThumbnailView
import com.android.quickstep.util.DesktopTask
import com.android.quickstep.util.SingleTask
import com.android.quickstep.util.SplitTask
import com.android.quickstep.util.SurfaceTransaction
import com.android.systemui.shared.recents.model.Task
import com.android.wm.shell.shared.split.SplitBounds
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RecentsTransitionPolishTest {

    private fun appContext(): Context = RuntimeEnvironment.getApplication()

    private fun makeTask(
        id: Int,
        userId: Int = 0,
        windowingMode: Int = 1,
        lastActiveTime: Long = 1_000L,
        isLocked: Boolean = false,
        isMinimized: Boolean = false,
        isVisible: Boolean = true,
        isDockable: Boolean = true,
        appBounds: Rect? = null,
        positionInParent: Point? = null,
        colorBackground: Int = Color.BLACK,
        label: String? = "App",
        topActivity: ComponentName? = null,
    ): Task =
        Task(Task.TaskKey(id, windowingMode, Intent(), null, userId, lastActiveTime)).apply {
            this.isLocked = isLocked
            this.isMinimized = isMinimized
            this.isVisible = isVisible
            this.isDockable = isDockable
            this.appBounds = appBounds
            this.positionInParent = positionInParent
            this.colorBackground = colorBackground
            this.topActivity = topActivity
            this.taskDescription = ActivityManager.TaskDescription.Builder().setLabel(label).build()
        }

    @Test
    fun taskThumbnailView_overlayChildrenDisableOverlappingRenderingAndResetDimAlphaOnRecycle() {
        val context = appContext()
        val thumbnailView =
            LayoutInflater.from(context).inflate(R.layout.task_thumbnail, null)
                as TaskThumbnailView

        val scrimView = thumbnailView.findViewById<View>(R.id.task_thumbnail_scrim)
        val splashBackground = thumbnailView.findViewById<View>(R.id.splash_background)
        val splashIcon = thumbnailView.findViewById<View>(R.id.splash_icon)

        assertThat(scrimView.visibility).isEqualTo(View.INVISIBLE)
        assertThat(splashBackground.visibility).isEqualTo(View.INVISIBLE)
        assertThat(splashIcon.visibility).isEqualTo(View.INVISIBLE)

        thumbnailView.updateSplashAlpha(0.5f)
        assertThat(splashBackground.getHasOverlappingRendering()).isFalse()
        assertThat(splashIcon.getHasOverlappingRendering()).isFalse()
        assertThat(splashBackground.visibility).isEqualTo(View.VISIBLE)
        assertThat(splashIcon.visibility).isEqualTo(View.VISIBLE)

        thumbnailView.updateSplashAlpha(0f)
        assertThat(splashBackground.visibility).isEqualTo(View.INVISIBLE)
        assertThat(splashIcon.visibility).isEqualTo(View.INVISIBLE)

        thumbnailView.updateTintAmount(0.4f)
        assertThat(scrimView.getHasOverlappingRendering()).isFalse()
        assertThat(scrimView.visibility).isEqualTo(View.VISIBLE)

        thumbnailView.updateTintAmount(0f)
        assertThat(scrimView.visibility).isEqualTo(View.INVISIBLE)

        // Open task menu (MenuProgress = 0.4f), recycle, then updateTintAmount(0f) on reused view:
        // stale MenuProgress must NOT resurrect the 40% dark scrim.
        thumbnailView.updateMenuOpenProgress(1f)
        assertThat(scrimView.visibility).isEqualTo(View.VISIBLE)

        thumbnailView.onRecycle()
        assertThat(scrimView.visibility).isEqualTo(View.INVISIBLE)
        assertThat(splashBackground.visibility).isEqualTo(View.INVISIBLE)
        assertThat(splashIcon.visibility).isEqualTo(View.INVISIBLE)

        thumbnailView.updateTintAmount(0f)
        assertThat(scrimView.alpha).isEqualTo(0f)
        assertThat(scrimView.visibility).isEqualTo(View.INVISIBLE)
    }

    @Test
    fun iconAppChipView_preservesOverlappingRenderingForCleanArrowBlending() {
        val chip = IconAppChipView(appContext())
        assertThat(chip.hasOverlappingRendering()).isTrue()
    }

    @Test
    fun scrimView_updatesBackgroundBeforeSysUiLuminanceAndReusesForegroundDrawable() {
        val observedDarkStates = mutableListOf<Boolean>()
        val scrimView =
            object : ScrimView(appContext(), null) {
                override fun updateSysUiColors() {
                    observedDarkStates.add(isScrimDark())
                }
            }

        // Starts with null background; setting white must update background BEFORE isScrimDark().
        scrimView.setScrimColors(ScrimColors(Color.WHITE, Color.TRANSPARENT))
        assertThat(observedDarkStates).containsExactly(false)
        assertThat(scrimView.foreground).isNull()

        // Setting dark color immediately computes dark luminance (true) with no stale read.
        scrimView.setScrimColors(ScrimColors(0x80000000.toInt(), Color.TRANSPARENT))
        assertThat(observedDarkStates).containsExactly(false, true).inOrder()
        assertThat(scrimView.scrimColors)
            .isEqualTo(ScrimColors(0x80000000.toInt(), Color.TRANSPARENT))

        // Foreground ColorDrawable is reused across non-transparent updates and cleared on 0.
        scrimView.setScrimColors(ScrimColors(0x80000000.toInt(), 0x33112233))
        val firstForeground = scrimView.foreground
        assertThat(firstForeground).isInstanceOf(ColorDrawable::class.java)
        assertThat((firstForeground as ColorDrawable).color).isEqualTo(0x33112233)

        scrimView.setScrimColors(ScrimColors(0x80000000.toInt(), 0x44445566))
        assertThat(scrimView.foreground).isSameInstanceAs(firstForeground)
        assertThat(firstForeground.color).isEqualTo(0x44445566)

        scrimView.setScrimColors(ScrimColors(0x80000000.toInt(), Color.TRANSPARENT))
        assertThat(scrimView.foreground).isNull()

        // Non-transparent initial background set directly via setBackground is cleared when
        // setScrimColors passes Color.TRANSPARENT even if mBackgroundColor was 0.
        val presetScrim =
            object : ScrimView(appContext(), null) {
                override fun updateSysUiColors() {}
            }
        presetScrim.background = ColorDrawable(Color.RED)
        presetScrim.setScrimColors(ScrimColors(Color.TRANSPARENT, Color.TRANSPARENT))
        assertThat((presetScrim.background as ColorDrawable).color).isEqualTo(Color.TRANSPARENT)
    }

    @Test
    fun surfaceTransaction_forNullSurfaceReturnsSafeMockProperties() {
        val tx = SurfaceTransaction()
        val props =
            tx.forSurface(null)
                .setBackgroundBlurRadius(48)
                .setOpaque(false)
                .reparent(null)
                .setRelativeLayer(null, -1)
                .setShow()
                .setRemove()
        assertThat(props).isInstanceOf(SurfaceTransaction.MockProperties::class.java)
        assertThat((props as SurfaceTransaction.MockProperties).backgroundBlurRadius).isEqualTo(48)
    }

    @Test
    fun recentsView_areTaskGroupListsEquivalentDetectsAllMutableTaskChanges() {
        val t1 = makeTask(10)
        val t2 = makeTask(20)
        val planA = listOf(SingleTask(t1), SingleTask(t2))

        assertThat(
                RecentsView.areTaskGroupListsEquivalent(
                    planA,
                    listOf(SingleTask(makeTask(10)), SingleTask(makeTask(20))),
                )
            )
            .isTrue()
        assertThat(RecentsView.areTaskGroupListsEquivalent(planA, listOf(SingleTask(t2), SingleTask(t1))))
            .isFalse()
        assertThat(
                RecentsView.areTaskGroupListsEquivalent(
                    planA,
                    listOf(SingleTask(makeTask(10, windowingMode = 5)), SingleTask(t2)),
                )
            )
            .isFalse()
        assertThat(
                RecentsView.areTaskGroupListsEquivalent(
                    planA,
                    listOf(SingleTask(makeTask(10, userId = 10)), SingleTask(t2)),
                )
            )
            .isFalse()
        assertThat(
                RecentsView.areTaskGroupListsEquivalent(
                    planA,
                    listOf(SingleTask(makeTask(10, isLocked = true)), SingleTask(t2)),
                )
            )
            .isFalse()
        assertThat(
                RecentsView.areTaskGroupListsEquivalent(
                    planA,
                    listOf(SingleTask(makeTask(10, lastActiveTime = 2_000L)), SingleTask(t2)),
                )
            )
            .isFalse()
        assertThat(
                RecentsView.areTaskGroupListsEquivalent(
                    planA,
                    listOf(SingleTask(makeTask(10, isMinimized = true)), SingleTask(t2)),
                )
            )
            .isFalse()
        assertThat(
                RecentsView.areTaskGroupListsEquivalent(
                    planA,
                    listOf(SingleTask(makeTask(10, isVisible = false)), SingleTask(t2)),
                )
            )
            .isFalse()
        assertThat(
                RecentsView.areTaskGroupListsEquivalent(
                    planA,
                    listOf(SingleTask(makeTask(10, isDockable = false)), SingleTask(t2)),
                )
            )
            .isFalse()
        assertThat(
                RecentsView.areTaskGroupListsEquivalent(
                    planA,
                    listOf(
                        SingleTask(makeTask(10, appBounds = Rect(0, 0, 500, 500))),
                        SingleTask(t2),
                    ),
                )
            )
            .isFalse()
        assertThat(
                RecentsView.areTaskGroupListsEquivalent(
                    planA,
                    listOf(SingleTask(makeTask(10, positionInParent = Point(10, 20))), SingleTask(t2)),
                )
            )
            .isFalse()
        assertThat(
                RecentsView.areTaskGroupListsEquivalent(
                    planA,
                    listOf(SingleTask(makeTask(10, label = "Renamed")), SingleTask(t2)),
                )
            )
            .isFalse()
        assertThat(
                RecentsView.areTaskGroupListsEquivalent(
                    planA,
                    listOf(
                        SingleTask(makeTask(10, topActivity = ComponentName("a.b", "a.b.C"))),
                        SingleTask(t2),
                    ),
                )
            )
            .isFalse()
        assertThat(RecentsView.areTaskGroupListsEquivalent(null, planA)).isFalse()

        val splitBounds1 = SplitBounds(Rect(0, 0, 500, 1000), Rect(500, 0, 1000, 1000), 10, 20, 0)
        val splitBounds2 = SplitBounds(Rect(0, 0, 400, 1000), Rect(400, 0, 1000, 1000), 10, 20, 1)
        assertThat(
                RecentsView.areTaskGroupListsEquivalent(
                    listOf(SplitTask(t1, t2, splitBounds1)),
                    listOf(SplitTask(makeTask(10), makeTask(20), splitBounds1)),
                )
            )
            .isTrue()
        assertThat(
                RecentsView.areTaskGroupListsEquivalent(
                    listOf(SplitTask(t1, t2, splitBounds1)),
                    listOf(SplitTask(t1, t2, splitBounds2)),
                )
            )
            .isFalse()

        assertThat(
                RecentsView.areTaskGroupListsEquivalent(
                    listOf(DesktopTask(deskId = 1, desktopDisplayId = 0, tasks = listOf(t1))),
                    listOf(DesktopTask(deskId = 1, desktopDisplayId = 0, tasks = listOf(makeTask(10)))),
                )
            )
            .isTrue()
        assertThat(
                RecentsView.areTaskGroupListsEquivalent(
                    listOf(DesktopTask(deskId = 1, desktopDisplayId = 0, tasks = listOf(t1))),
                    listOf(DesktopTask(deskId = 2, desktopDisplayId = 0, tasks = listOf(t1))),
                )
            )
            .isFalse()
    }
}
