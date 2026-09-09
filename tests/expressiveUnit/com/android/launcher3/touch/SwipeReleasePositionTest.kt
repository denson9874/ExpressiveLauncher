package com.android.launcher3.touch

import android.app.Application
import android.content.Context
import android.graphics.PointF
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SwipeReleasePositionTest {

    @Test
    fun activeVerticalDrag_reportsReleasePositionExactlyOnceBeforeDragEnd() {
        val gesture = Gesture()
        gesture.down()
        gesture.move(y = -4 * gesture.slop)
        gesture.up(y = -10 * gesture.slop)

        assertThat(gesture.listener.calls).containsExactly("start", "move", "up", "end").inOrder()
        assertThat(gesture.listener.drags.map { it.displacement })
            .containsExactly(-3 * gesture.slop, -9 * gesture.slop).inOrder()
        assertThat(gesture.listener.displacementAtEnd).isEqualTo(-9 * gesture.slop)
        assertThat(gesture.detector.isSettlingState).isTrue()
    }

    @Test
    fun unchangedReleasePosition_doesNotSendAnotherDragCallback() {
        val gesture = Gesture()
        gesture.down()
        gesture.move(y = -4 * gesture.slop)
        gesture.up(y = -4 * gesture.slop)

        assertThat(gesture.listener.calls).containsExactly("start", "move", "end").inOrder()
        assertThat(gesture.listener.drags).hasSize(1)
        assertThat(gesture.listener.displacementAtEnd).isEqualTo(-3 * gesture.slop)
        assertThat(gesture.detector.isSettlingState).isTrue()
    }

    @Test
    fun cancel_ignoresItsEndpointAndSettlesTheExistingDrag() {
        val gesture = Gesture()
        gesture.down()
        gesture.move(y = -4 * gesture.slop)
        gesture.send(MotionEvent.ACTION_CANCEL, Pointer(y = -10 * gesture.slop))

        assertThat(gesture.listener.calls).containsExactly("start", "move", "end").inOrder()
        assertThat(gesture.listener.displacementAtEnd).isEqualTo(-3 * gesture.slop)
        assertThat(gesture.detector.isSettlingState).isTrue()
    }

    @Test
    fun downThenUp_doesNotInitiateADragFromTheReleasePosition() {
        val gesture = Gesture()
        gesture.down()
        gesture.up(y = -10 * gesture.slop)

        assertThat(gesture.listener.calls).isEmpty()
        assertThat(gesture.detector.isIdleState).isTrue()
    }

    @Test
    fun movementBelowSlop_thenDistantRelease_doesNotInitiateADrag() {
        val gesture = Gesture()
        gesture.down()
        gesture.move(y = -gesture.slop / 2)
        gesture.up(y = -10 * gesture.slop)

        assertThat(gesture.listener.calls).isEmpty()
        assertThat(gesture.detector.isIdleState).isTrue()
    }

    @Test
    fun releaseWithoutTheActivePointer_ignoresTheEndpointAndSettlesSafely() {
        val gesture = Gesture()
        gesture.down()
        gesture.move(y = -4 * gesture.slop)
        gesture.send(MotionEvent.ACTION_UP, Pointer(id = 7, y = -10 * gesture.slop))

        assertThat(gesture.listener.calls).containsExactly("start", "move", "end").inOrder()
        assertThat(gesture.listener.displacementAtEnd).isEqualTo(-3 * gesture.slop)
        assertThat(gesture.detector.isSettlingState).isTrue()
    }

    @Test
    fun horizontalRelease_preservesLtrDisplacement() {
        val gesture = Gesture(direction = SingleAxisSwipeDetector.HORIZONTAL)
        gesture.down()
        gesture.move(x = 4 * gesture.slop)
        gesture.up(x = 10 * gesture.slop)

        assertThat(gesture.listener.calls).containsExactly("start", "move", "up", "end").inOrder()
        assertThat(gesture.listener.drags.map { it.displacement })
            .containsExactly(3 * gesture.slop, 9 * gesture.slop).inOrder()
        assertThat(gesture.listener.displacementAtEnd).isEqualTo(9 * gesture.slop)
    }

    @Test
    fun horizontalRelease_preservesRtlDisplacementMirroring() {
        val gesture = Gesture(direction = SingleAxisSwipeDetector.HORIZONTAL, isRtl = true)
        gesture.down()
        gesture.move(x = 4 * gesture.slop)
        gesture.up(x = 10 * gesture.slop)

        assertThat(gesture.listener.calls).containsExactly("start", "move", "up", "end").inOrder()
        assertThat(gesture.listener.drags.map { it.displacement })
            .containsExactly(-3 * gesture.slop, -9 * gesture.slop).inOrder()
        assertThat(gesture.listener.displacementAtEnd).isEqualTo(-9 * gesture.slop)
    }

    @Test
    fun verticalRelease_doesNotMirrorItsPrimaryDisplacementInRtl() {
        val gesture = Gesture(isRtl = true)
        gesture.down()
        gesture.move(y = -4 * gesture.slop)
        gesture.up(y = -10 * gesture.slop)

        assertThat(gesture.listener.calls).containsExactly("start", "move", "up", "end").inOrder()
        assertThat(gesture.listener.drags.map { it.displacement })
            .containsExactly(-3 * gesture.slop, -9 * gesture.slop).inOrder()
        assertThat(gesture.listener.displacementAtEnd).isEqualTo(-9 * gesture.slop)
    }

    @Test
    fun releaseAfterActivePointerSwitch_preservesContinuousDisplacement() {
        val gesture = Gesture()
        gesture.send(MotionEvent.ACTION_DOWN, Pointer(id = 3))
        gesture.send(MotionEvent.ACTION_MOVE, Pointer(id = 3, y = -4 * gesture.slop))
        val first = Pointer(id = 3, y = -4 * gesture.slop)
        val second = Pointer(id = 8, y = 20 * gesture.slop)
        gesture.send(
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            first,
            second,
        )
        gesture.send(MotionEvent.ACTION_POINTER_UP, first, second)
        // The second pointer adds another six slops of movement to the existing four;
        // switching fingers must not replace the gesture origin with either raw coordinate.
        gesture.send(MotionEvent.ACTION_UP, second.copy(y = 14 * gesture.slop))

        assertThat(gesture.listener.calls).containsExactly("start", "move", "up", "end").inOrder()
        assertThat(gesture.listener.drags.map { it.displacement })
            .containsExactly(-3 * gesture.slop, -9 * gesture.slop).inOrder()
        assertThat(gesture.listener.displacementAtEnd).isEqualTo(-9 * gesture.slop)
        assertThat(gesture.detector.isSettlingState).isTrue()
    }

    @Test
    fun releaseWhileAlreadySettling_doesNotReportAnotherDragOrEnd() {
        val gesture = Gesture()
        gesture.down()
        gesture.move(y = -4 * gesture.slop)
        gesture.up(y = -4 * gesture.slop)
        gesture.listener.calls.clear()

        gesture.up(y = -10 * gesture.slop)

        assertThat(gesture.listener.calls).isEmpty()
        assertThat(gesture.detector.isSettlingState).isTrue()
    }

    @Test
    fun recaughtDrag_reportsReleasePositionWithoutSubtractingTouchSlopAgain() {
        val gesture = Gesture()
        gesture.down()
        gesture.move(y = -4 * gesture.slop)
        gesture.up(y = -4 * gesture.slop)
        gesture.listener.calls.clear()
        gesture.listener.drags.clear()
        gesture.detector.setDetectableScrollConditions(SingleAxisSwipeDetector.DIRECTION_BOTH, true)

        gesture.down()
        gesture.up(y = -4 * gesture.slop)

        assertThat(gesture.listener.calls).containsExactly("start", "up", "end").inOrder()
        assertThat(gesture.listener.dragStarts).containsExactly(true, false).inOrder()
        assertThat(gesture.listener.drags.map { it.displacement }).containsExactly(-4 * gesture.slop)
        assertThat(gesture.listener.displacementAtEnd).isEqualTo(-4 * gesture.slop)
        assertThat(gesture.detector.isSettlingState).isTrue()
    }

    @Test
    @Config(qualifiers = "ldltr")
    fun bothAxesDrag_reportsBothReleaseCoordinatesBeforeEnd() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        val calls = mutableListOf<String>()
        val displacements = mutableListOf<PointF>()
        var displacementAtEnd: PointF? = null
        val detector = BothAxesSwipeDetector(context, object : BothAxesSwipeDetector.Listener {
            override fun onDragStart(start: Boolean) {
                calls += "start"
            }

            override fun onDrag(displacement: PointF, event: MotionEvent): Boolean {
                calls += if (event.actionMasked == MotionEvent.ACTION_UP) "up" else "move"
                // BaseSwipeDetector reuses its scratch point; retain values, not that reference.
                displacements += PointF(displacement.x, displacement.y)
                return true
            }

            override fun onDragEnd(velocity: PointF) {
                calls += "end"
                displacementAtEnd = displacements.lastOrNull()
            }
        }).apply {
            setDetectableScrollConditions(
                BothAxesSwipeDetector.DIRECTION_UP or BothAxesSwipeDetector.DIRECTION_RIGHT,
                false,
            )
        }
        listOf(
            Triple(MotionEvent.ACTION_DOWN, 0f, 0f),
            Triple(MotionEvent.ACTION_MOVE, 4 * slop, -4 * slop),
            Triple(MotionEvent.ACTION_UP, 10 * slop, -12 * slop),
        ).forEachIndexed { index, (action, x, y) ->
            val event = MotionEvent.obtain(1_000L, 1_000L + index * 16, action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                detector.onTouchEvent(event)
            } finally {
                event.recycle()
            }
        }

        assertThat(calls).containsExactly("start", "move", "up", "end").inOrder()
        assertThat(displacements)
            .containsExactly(PointF(3 * slop, -3 * slop), PointF(9 * slop, -11 * slop)).inOrder()
        assertThat(displacementAtEnd).isEqualTo(PointF(9 * slop, -11 * slop))
        assertThat(detector.isSettlingState).isTrue()
    }

    private data class Pointer(val id: Int = 0, val x: Float = 0f, val y: Float = 0f)

    private data class Drag(val displacement: Float)

    private class RecordingListener : SingleAxisSwipeDetector.Listener {
        val calls = mutableListOf<String>()
        val drags = mutableListOf<Drag>()
        val dragStarts = mutableListOf<Boolean>()
        var displacementAtEnd: Float? = null

        override fun onDragStart(start: Boolean, startDisplacement: Float) {
            calls += "start"
            dragStarts += start
        }

        override fun onDrag(displacement: Float): Boolean =
            error("The event-aware listener should receive the drag")

        override fun onDrag(
            displacement: Float,
            orthogonalDisplacement: Float,
            event: MotionEvent,
        ): Boolean {
            calls += when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> "move"
                MotionEvent.ACTION_UP -> "up"
                else -> "unexpected:${event.actionMasked}"
            }
            drags += Drag(displacement)
            return true
        }

        override fun onDragEnd(velocity: Float) {
            calls += "end"
            displacementAtEnd = drags.lastOrNull()?.displacement
        }
    }

    private class TestDetector(
        context: Context,
        config: ViewConfiguration,
        listener: RecordingListener,
        direction: SingleAxisSwipeDetector.Direction,
        isRtl: Boolean,
    ) : SingleAxisSwipeDetector(context, config, listener, direction, isRtl)

    private class Gesture(
        direction: SingleAxisSwipeDetector.Direction = SingleAxisSwipeDetector.VERTICAL,
        isRtl: Boolean = false,
    ) {
        private val context = ApplicationProvider.getApplicationContext<Application>()
        private val config = ViewConfiguration.get(context)
        val slop = config.scaledTouchSlop.toFloat()
        val listener = RecordingListener()
        val detector = TestDetector(context, config, listener, direction, isRtl).apply {
            setDetectableScrollConditions(SingleAxisSwipeDetector.DIRECTION_BOTH, false)
        }
        private var eventTime = 1_000L

        fun down() = send(MotionEvent.ACTION_DOWN, Pointer())

        fun move(x: Float = 0f, y: Float = 0f) = send(MotionEvent.ACTION_MOVE, Pointer(x = x, y = y))

        fun up(x: Float = 0f, y: Float = 0f) = send(MotionEvent.ACTION_UP, Pointer(x = x, y = y))

        fun send(action: Int, vararg pointers: Pointer) {
            val properties = pointers.map { pointer ->
                MotionEvent.PointerProperties().apply {
                    id = pointer.id
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            }.toTypedArray()
            val coordinates = pointers.map { pointer ->
                MotionEvent.PointerCoords().apply {
                    x = pointer.x
                    y = pointer.y
                    pressure = 1f
                    size = 1f
                }
            }.toTypedArray()
            val event = MotionEvent.obtain(
                1_000L, eventTime, action, pointers.size, properties, coordinates,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
            )
            eventTime += 16
            try {
                detector.onTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
    }
}
