package com.android.launcher3.accessibility

import android.app.Activity
import android.app.Application
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import app.lawnchair.LawnchairLauncher
import app.lawnchair.allapps.views.ContactSearchAccessibilityTest
import com.android.launcher3.AppWidgetResizeFrame
import com.android.launcher3.ButtonDropTarget
import com.android.launcher3.DeviceProfile
import com.android.launcher3.DropTargetBar
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.R
import com.android.launcher3.dragndrop.DragLayer
import com.android.launcher3.model.data.LauncherAppWidgetInfo
import com.android.launcher3.widget.LauncherAppWidgetHostView
import com.android.launcher3.widget.LocalColorExtractor
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

// SDK 34: Android 17's framework jar bundles an older com.android.launcher3.Flags that hides ours.
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    application = Application::class,
    shadows = [
        ContactSearchAccessibilityTest.ShadowThemeProvider::class,
        WidgetResizeAccessibilityTest.ShadowLocalColorExtractor::class,
    ],
)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
class WidgetResizeAccessibilityTest {

    @Test
    fun parentlessWidget_offersAndPerformsNoResize() {
        val home = home()

        val actions = ReflectionHelpers.callInstanceMethod<List<*>>(
            home.delegate,
            "getSupportedResizeActions",
            ClassParameter.from(View::class.java, home.widget),
            ClassParameter.from(LauncherAppWidgetInfo::class.java, home.info),
        )
        val resized = ReflectionHelpers.callInstanceMethod<Boolean>(
            home.delegate,
            "performResizeAction",
            ClassParameter.from(Int::class.javaPrimitiveType!!, R.string.action_increase_width),
            ClassParameter.from(View::class.java, home.widget),
            ClassParameter.from(LauncherAppWidgetInfo::class.java, home.info),
        )

        assertThat(actions).isEmpty()
        assertThat(resized).isFalse()
        assertThat(home.info.spanX).isEqualTo(2)
    }

    @Test
    fun widgetLeavingHome_closesItsResizeFrame() {
        val home = home()
        val container = attach(home.widget)
        val frame = home.openFrame()

        // Outside a cell layout the widget has no room to grow, whatever its provider allows.
        assertThat(actionIds(frame)).doesNotContain(R.id.action_resize)

        container.removeView(home.widget)

        assertThat(frame.isOpen).isFalse()
        assertThat(frame.parent).isNull()
        // A late accessibility query still builds the closed frame's node; it must not throw.
        assertThat(actionIds(frame)).doesNotContain(R.id.action_resize)
    }

    @Test
    fun destroyedLauncher_leavesItsFrameToTheWindowTeardown() {
        val home = home()
        val container = attach(home.widget)
        val frame = home.openFrame()
        ReflectionHelpers.setField(home.launcher, "mDestroyed", true)

        container.removeView(home.widget)

        assertThat(frame.isOpen).isTrue()
        assertThat(frame.parent).isSameInstanceAs(home.dragLayer)
    }

    private fun home(): Home {
        val launcher = Robolectric.buildActivity(LawnchairLauncher::class.java).get()
        ReflectionHelpers.setField(launcher, "mDeviceProfile", DeviceProfile())
        val context = ContextThemeWrapper(launcher, R.style.LauncherTheme)
        val delegate = LauncherAccessibilityDelegate(launcher)
        ReflectionHelpers.setField(launcher, "mAccessibilityDelegate", delegate)
        // Accessibility also offers each drop target's action; this host has none.
        val dropTargetBar = DropTargetBar(context, null)
        ReflectionHelpers.setField(dropTargetBar, "mDropTargets", arrayOf<ButtonDropTarget>())
        ReflectionHelpers.setField(launcher, "mDropTargetBar", dropTargetBar)
        val dragLayer = DragLayer(context, null)
        ReflectionHelpers.setField(launcher, "mDragLayer", dragLayer)

        val info = LauncherAppWidgetInfo().apply {
            container = Favorites.CONTAINER_DESKTOP
            spanX = 2
            spanY = 2
        }
        val widget = LauncherAppWidgetHostView(context).apply {
            setAppWidget(
                WIDGET_ID,
                AppWidgetProviderInfo().apply {
                    provider = ComponentName("com.example.widgets", "ClockWidget")
                    resizeMode = AppWidgetProviderInfo.RESIZE_BOTH
                    autoAdvanceViewId = View.NO_ID
                },
            )
            tag = info
        }
        return Home(launcher, context, delegate, dragLayer, widget, info)
    }

    /** Puts the widget in a window, so that removing it really detaches it. */
    private fun attach(widget: View): FrameLayout {
        val container = FrameLayout(widget.context).apply { addView(widget) }
        Robolectric.buildActivity(Activity::class.java).setup().visible().get().setContentView(container)
        return container
    }

    private fun actionIds(frame: AppWidgetResizeFrame): List<Int> =
        frame.createAccessibilityNodeInfo().actionList.map { it.id }

    private class Home(
        val launcher: LawnchairLauncher,
        val context: Context,
        val delegate: LauncherAccessibilityDelegate,
        val dragLayer: DragLayer,
        val widget: LauncherAppWidgetHostView,
        val info: LauncherAppWidgetInfo,
    ) {
        /**
         * Opens the frame around the widget as showForWidget and setupForWidget do, without the
         * cell bookkeeping and snap animation, which need a full workspace.
         */
        fun openFrame(): AppWidgetResizeFrame {
            val frame = LayoutInflater.from(context)
                .inflate(R.layout.app_widget_resize_frame, dragLayer, false) as AppWidgetResizeFrame
            ReflectionHelpers.setField(frame, "mWidgetView", widget)
            ReflectionHelpers.setField(frame, "mDragLayer", dragLayer)
            widget.addOnAttachStateChangeListener(
                ReflectionHelpers.getField<View.OnAttachStateChangeListener>(
                    frame,
                    "mWidgetViewAttachListener",
                ),
            )
            frame.tag = info
            frame.setAccessibilityDelegate(delegate)
            dragLayer.addView(frame)
            ReflectionHelpers.setField(frame, "mIsOpen", true)
            return frame
        }
    }

    // Wallpaper-derived widget colors are unrelated to resizing and would subscribe to the
    // shadowed theme provider.
    @Implements(LocalColorExtractor::class)
    class ShadowLocalColorExtractor {
        companion object {
            @JvmStatic
            @Implementation
            fun newInstance(context: Context): LocalColorExtractor = LocalColorExtractor()
        }
    }

    companion object {
        private const val WIDGET_ID = 7
    }
}
