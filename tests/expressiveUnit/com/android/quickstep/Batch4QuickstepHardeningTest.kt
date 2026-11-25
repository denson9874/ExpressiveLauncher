package com.android.quickstep

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.Region
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Message
import android.os.Parcel
import android.view.Display
import android.view.IWindowManager
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.widget.ImageView
import app.lawnchair.compat.CinnamonBunCompat
import com.android.launcher3.R
import com.android.launcher3.statehandlers.DesktopVisibilityController
import com.android.launcher3.util.coroutines.DispatcherProvider
import com.android.quickstep.recents.data.HighResLoadingStateNotifier
import com.android.quickstep.recents.data.RecentTasksDataSource
import com.android.quickstep.recents.data.TaskVisualsChangeNotifier
import com.android.quickstep.recents.data.TaskVisualsChangedDelegateImpl
import com.android.quickstep.recents.data.TasksRepository
import com.android.quickstep.task.thumbnail.TaskThumbnailUiState
import com.android.quickstep.task.thumbnail.TaskThumbnailView
import com.android.quickstep.task.thumbnail.data.TaskIconDataSource
import com.android.quickstep.task.thumbnail.data.TaskThumbnailDataSource
import com.android.quickstep.util.GestureExclusionManager
import com.android.quickstep.util.GroupTask
import com.android.quickstep.util.SingleTask
import com.android.quickstep.util.TaskKeyByLastActiveTimeCache
import com.android.quickstep.util.TaskKeyLruCache
import com.android.quickstep.util.TaskVisualsChangeListener
import com.android.systemui.shared.recents.ISystemUiProxy
import com.android.systemui.shared.recents.model.Task
import com.android.systemui.shared.recents.model.ThumbnailData
import com.android.systemui.shared.system.TaskStackChangeListener
import com.android.systemui.shared.system.TaskStackChangeListeners
import com.android.wm.shell.shared.IFocusTransitionListener
import com.google.common.truth.Truth.assertThat
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class Batch4QuickstepHardeningTest {

    private class RecordingBinder : Binder() {
        var lastCode: Int = -1
        var lastFlags: Int = -1
        var lastDataBytes: ByteArray = ByteArray(0)

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            lastCode = code
            lastFlags = flags
            lastDataBytes = data.marshall()
            return true
        }
    }

    private fun proxyWrapping(binder: IBinder): ISystemUiProxy =
        Proxy.newProxyInstance(
            ISystemUiProxy::class.java.classLoader,
            arrayOf(ISystemUiProxy::class.java),
        ) { _, method, _ ->
            if (method.name == "asBinder") binder else null
        } as ISystemUiProxy

    @Test
    fun cinnamonBunCompat_onBackEvent_writesKeyEventAndDisplayId() {
        val binder = RecordingBinder()
        val keyEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)

        CinnamonBunCompat.onBackEvent(proxyWrapping(binder), keyEvent, 7)

        assertThat(binder.lastCode).isEqualTo(IBinder.FIRST_CALL_TRANSACTION + 44)
        assertThat(binder.lastFlags).isEqualTo(IBinder.FLAG_ONEWAY)
        val parcel = Parcel.obtain()
        try {
            parcel.unmarshall(binder.lastDataBytes, 0, binder.lastDataBytes.size)
            parcel.setDataPosition(0)
            parcel.enforceInterface(ISystemUiProxy.DESCRIPTOR)
            val readEvent = parcel.readTypedObject(KeyEvent.CREATOR)
            val readDisplayId = parcel.readInt()
            assertThat(readEvent?.keyCode).isEqualTo(KeyEvent.KEYCODE_BACK)
            assertThat(readDisplayId).isEqualTo(7)
            assertThat(parcel.dataAvail()).isEqualTo(0)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun cinnamonBunCompat_onOverviewShownAndHidden_writeDisplayIdWithAndroid17Codes() {
        val binder = RecordingBinder()

        CinnamonBunCompat.onOverviewShown(proxyWrapping(binder), 3)
        assertThat(binder.lastCode).isEqualTo(IBinder.FIRST_CALL_TRANSACTION + 61)
        Parcel.obtain().also { parcel ->
            try {
                parcel.unmarshall(binder.lastDataBytes, 0, binder.lastDataBytes.size)
                parcel.setDataPosition(0)
                parcel.enforceInterface(ISystemUiProxy.DESCRIPTOR)
                assertThat(parcel.readInt()).isEqualTo(3)
                assertThat(parcel.dataAvail()).isEqualTo(0)
            } finally {
                parcel.recycle()
            }
        }

        CinnamonBunCompat.onOverviewHidden(proxyWrapping(binder), 5)
        assertThat(binder.lastCode).isEqualTo(IBinder.FIRST_CALL_TRANSACTION + 62)
        Parcel.obtain().also { parcel ->
            try {
                parcel.unmarshall(binder.lastDataBytes, 0, binder.lastDataBytes.size)
                parcel.setDataPosition(0)
                parcel.enforceInterface(ISystemUiProxy.DESCRIPTOR)
                assertThat(parcel.readInt()).isEqualTo(5)
                assertThat(parcel.dataAvail()).isEqualTo(0)
            } finally {
                parcel.recycle()
            }
        }
    }

    @Test
    fun desktopTaskListener_android17TransactionCodesMatchAidlOrdinals() {
        assertThat(DesktopVisibilityController.TRANSACTION_ON_TASKBAR_CORNER_ROUNDING_UPDATE)
            .isEqualTo(IBinder.FIRST_CALL_TRANSACTION + 3)
        assertThat(
            DesktopVisibilityController.TRANSACTION_ON_TASK_APPEARING_IN_DESK_WITH_OVERVIEW_SHOWING
        ).isEqualTo(IBinder.FIRST_CALL_TRANSACTION + 10)
    }

    @Test
    fun thumbnailData_recycleBitmapIsIdempotentAndCloseSnapshotNullSafe() {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val data = ThumbnailData(thumbnail = bitmap)

        data.recycleBitmap()
        assertThat(bitmap.isRecycled).isTrue()

        // Calling a second time must not throw.
        data.recycleBitmap()
        CinnamonBunCompat.closeSnapshotBuffer(null)
    }

    @Test
    fun taskKeyCaches_trackContainsAcrossPutUpdateRemoveAndEvict() {
        val key = Task.TaskKey(42, 0, Intent(), null, 0, 1_000L)
        val lru = TaskKeyLruCache<String>(4)
        val byActive = TaskKeyByLastActiveTimeCache<String>(4)

        for (cache in listOf(lru, byActive)) {
            assertThat(cache.contains(42)).isFalse()
            cache.put(key, "first")
            assertThat(cache.contains(42)).isTrue()
            cache.updateIfAlreadyInCache(42, "second")
            assertThat(cache.getAndInvalidateIfModified(key)).isEqualTo("second")
            cache.remove(key)
            assertThat(cache.contains(42)).isFalse()
            cache.put(key, "third")
            cache.evictAll()
            assertThat(cache.contains(42)).isFalse()
        }
    }

    @Test
    fun gestureExclusionManager_latchesSecurityExceptionAndStopsRetrying() {
        val registerCalls = AtomicInteger(0)
        val unregisterCalls = AtomicInteger(0)
        val firstAttempt = CountDownLatch(1)
        val wm =
            Proxy.newProxyInstance(
                IWindowManager::class.java.classLoader,
                arrayOf(IWindowManager::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "registerSystemGestureExclusionListener" -> {
                        registerCalls.incrementAndGet()
                        firstAttempt.countDown()
                        throw SecurityException("denied")
                    }
                    "unregisterSystemGestureExclusionListener" -> {
                        unregisterCalls.incrementAndGet()
                        null
                    }
                    else -> null
                }
            } as IWindowManager

        val manager = GestureExclusionManager(wm)
        val listener =
            object : GestureExclusionManager.ExclusionListener {
                override fun onGestureExclusionChanged(
                    exclusionRegion: Region?,
                    unrestrictedOrNull: Region?,
                ) = Unit
            }

        manager.addListener(listener)
        assertThat(firstAttempt.await(5, TimeUnit.SECONDS)).isTrue()
        // Wait briefly for the SecurityException catch block to set registrationDenied = true.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            manager.removeListener(listener)
            manager.addListener(listener)
            if (registerCalls.get() == 1) {
                Thread.sleep(20)
                break
            }
        }

        manager.removeListener(listener)
        manager.addListener(listener)
        Thread.sleep(100)

        assertThat(registerCalls.get()).isEqualTo(1)
        assertThat(unregisterCalls.get()).isEqualTo(0)
    }

    @Test
    fun focusState_onTransact_handlesAndroid17OnFocusedTaskChanged() {
        val stub = FocusState().createListenerStub()
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(IFocusTransitionListener.DESCRIPTOR)
            data.writeTypedObject<ActivityManager.RunningTaskInfo>(null, 0)
            data.writeBoolean(true)
            data.writeBoolean(false)
            data.setDataPosition(0)

            val handled = stub.onTransact(
                FocusState.TRANSACTION_ON_FOCUSED_TASK_CHANGED,
                data,
                null,
                IBinder.FLAG_ONEWAY,
            )

            assertThat(handled).isTrue()
            assertThat(data.dataAvail()).isEqualTo(0)
        } finally {
            data.recycle()
        }
    }

    @Test
    fun cinnamonBunCompat_closeScreenshotBuffer_closesHardwareBufferAndGainmap() {
        val hardwareCloses = AtomicInteger(0)
        val gainmapCloses = AtomicInteger(0)
        val fakeScreenshot = object {
            @Suppress("unused")
            fun getHardwareBuffer(): AutoCloseable = AutoCloseable { hardwareCloses.incrementAndGet() }

            @Suppress("unused")
            fun getGainmap(): AutoCloseable = AutoCloseable { gainmapCloses.incrementAndGet() }
        }

        CinnamonBunCompat.closeScreenshotBuffer(fakeScreenshot)

        assertThat(hardwareCloses.get()).isEqualTo(1)
        assertThat(gainmapCloses.get()).isEqualTo(1)
        // Null input must be a safe no-op.
        CinnamonBunCompat.closeScreenshotBuffer(null)
    }

    private class FakeTracker(var mReferenceCount: Int)

    private class FakeAndroid17Snapshot(
        tracker: FakeTracker?,
        private val simulateOrphanedTracker: Boolean,
    ) {
        @Suppress("unused")
        private var mSnapshotTracker: WeakReference<FakeTracker>? = tracker?.let(::WeakReference)

        var bufferClosed = false
            private set

        @Suppress("unused")
        private val mSnapshot = AutoCloseable { bufferClosed = true }

        @Suppress("unused")
        fun setSnapshotTracker(tracker: Any?) {
            mSnapshotTracker = (tracker as? FakeTracker)?.let(::WeakReference)
        }

        @Suppress("unused")
        fun isBufferValid(): Boolean = !bufferClosed

        @Suppress("unused")
        fun closeBuffer() {
            val tracker = mSnapshotTracker?.get()
            if (tracker == null) {
                bufferClosed = true
                return
            }
            if (simulateOrphanedTracker) {
                // Simulate stock Android 17 removeTracker() returning early on taskTracker == null.
                return
            }
            tracker.mReferenceCount -= 1
            if (tracker.mReferenceCount <= 0) {
                mSnapshotTracker = null
                bufferClosed = true
            }
        }
    }

    @Test
    fun cinnamonBunCompat_closeSnapshotBuffer_respectsSharedRefcountAndClosesOrphanedBuffers() {
        // Case 1: Legitimate shared refcount (2 -> 1) MUST stay open for the second owner, then
        // close cleanly when the second owner releases (1 -> 0).
        val sharedTracker = FakeTracker(mReferenceCount = 2)
        val sharedSnapshot = FakeAndroid17Snapshot(sharedTracker, simulateOrphanedTracker = false)
        CinnamonBunCompat.closeSnapshotBufferCompat(sharedSnapshot)
        assertThat(sharedTracker.mReferenceCount).isEqualTo(1)
        assertThat(sharedSnapshot.bufferClosed).isFalse()

        CinnamonBunCompat.closeSnapshotBufferCompat(sharedSnapshot)
        assertThat(sharedTracker.mReferenceCount).isEqualTo(0)
        assertThat(sharedSnapshot.bufferClosed).isTrue()

        // Case 2: Stock Android 17 orphaned tracker with refcount 1 -> 1 MUST be detached & closed.
        val orphanedSingle = FakeTracker(mReferenceCount = 1)
        val orphanedSingleSnapshot = FakeAndroid17Snapshot(orphanedSingle, simulateOrphanedTracker = true)
        CinnamonBunCompat.closeSnapshotBufferCompat(orphanedSingleSnapshot)
        assertThat(orphanedSingleSnapshot.bufferClosed).isTrue()

        // Case 3: Orphaned tracker with refcount 2 -> 2 decrements to 1 on first close (stays open),
        // then closes on the final owner's close.
        val orphanedShared = FakeTracker(mReferenceCount = 2)
        val orphanedSharedSnapshot = FakeAndroid17Snapshot(orphanedShared, simulateOrphanedTracker = true)
        CinnamonBunCompat.closeSnapshotBufferCompat(orphanedSharedSnapshot)
        assertThat(orphanedShared.mReferenceCount).isEqualTo(1)
        assertThat(orphanedSharedSnapshot.bufferClosed).isFalse()

        CinnamonBunCompat.closeSnapshotBufferCompat(orphanedSharedSnapshot)
        assertThat(orphanedSharedSnapshot.bufferClosed).isTrue()
    }

    @Test
    fun remoteAnimationTargets_releaseCheckDefersAndRunsSafely() {
        val releaseRuns = AtomicInteger(0)
        val check = object : RemoteAnimationTargets.ReleaseCheck() {
            fun allowRelease() = setCanRelease(true)
        }

        check.addOnSafeToReleaseCallback { releaseRuns.incrementAndGet() }
        assertThat(releaseRuns.get()).isEqualTo(0)

        check.allowRelease()
        assertThat(releaseRuns.get()).isEqualTo(1)

        // Once already releasable, callbacks execute immediately.
        check.addOnSafeToReleaseCallback { releaseRuns.incrementAndGet() }
        assertThat(releaseRuns.get()).isEqualTo(2)
    }

    @Test
    fun taskStackChangeListeners_preWrappedThumbnailDataRecycledOnlyWhenUnconsumed() {
        val listeners = TaskStackChangeListeners.getTestInstance()
        val callback = listeners.listenerImpl as Handler.Callback

        val unconsumedBitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val unconsumedData = ThumbnailData(thumbnail = unconsumedBitmap)
        val unconsumedMsg = Message.obtain(null, /* ON_TASK_SNAPSHOT_CHANGED */ 2, 101, 0, unconsumedData)
        assertThat(callback.handleMessage(unconsumedMsg)).isTrue()
        assertThat(unconsumedBitmap.isRecycled).isTrue()

        val consumedBitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val consumedData = ThumbnailData(thumbnail = consumedBitmap)
        var receivedTaskId = -1
        val consumer = object : TaskStackChangeListener {
            override fun onTaskSnapshotChanged(taskId: Int, snapshot: ThumbnailData?): Boolean {
                receivedTaskId = taskId
                return snapshot === consumedData
            }
        }
        listeners.registerTaskStackListener(consumer)
        try {
            val consumedMsg = Message.obtain(null, /* ON_TASK_SNAPSHOT_CHANGED */ 2, 202, 0, consumedData)
            assertThat(callback.handleMessage(consumedMsg)).isTrue()
            assertThat(receivedTaskId).isEqualTo(202)
            assertThat(consumedBitmap.isRecycled).isFalse()
        } finally {
            listeners.unregisterTaskStackListener(consumer)
            consumedData.recycleBitmap()
        }
    }

    @Test
    fun taskSnapshotChanged_visibleTaskNotInThumbnailCache_isNotRecycled() {
        val context = RuntimeEnvironment.getApplication()
        val thumbnailCache = TaskThumbnailCache(context, Executor { it.run() }, TaskKeyLruCache(3))
        val visualListeners = CopyOnWriteArrayList<TaskVisualsChangeListener>()
        val visualNotifier = object : TaskVisualsChangeNotifier {
            override fun addThumbnailChangeListener(listener: TaskVisualsChangeListener) {
                visualListeners.add(listener)
            }

            override fun removeThumbnailChangeListener(listener: TaskVisualsChangeListener) {
                visualListeners.remove(listener)
            }
        }
        val highResNotifier = object : HighResLoadingStateNotifier {
            override fun addCallback(
                callback: HighResLoadingState.HighResLoadingStateChangedCallback,
            ) = Unit

            override fun removeCallback(
                callback: HighResLoadingState.HighResLoadingStateChangedCallback,
            ) = Unit
        }
        val delegate = TaskVisualsChangedDelegateImpl(visualNotifier, highResNotifier)
        val task77 = Task(
            Task.TaskKey(77, 1, Intent(), ComponentName("com.example", ".Main"), 0, 1_000L)
        )
        val initialBitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val recentTasksDataSource = object : RecentTasksDataSource {
            override fun getTasks(callback: Consumer<List<GroupTask>>?): Int {
                callback?.accept(listOf(SingleTask(task77)))
                return 1
            }
        }
        val thumbnailDataSource = object : TaskThumbnailDataSource {
            override suspend fun getThumbnail(task: Task): ThumbnailData =
                ThumbnailData(thumbnail = initialBitmap, snapshotId = 1L)
        }
        val iconDataSource = object : TaskIconDataSource {
            override suspend fun getIcon(task: Task): TaskIconCache.TaskCacheEntry =
                TaskIconCache.TaskCacheEntry(ColorDrawable(Color.BLUE), "desc", "App")
        }
        val unconfined = object : DispatcherProvider {
            override val default: CoroutineDispatcher = Dispatchers.Unconfined
            override val ioBackground: CoroutineDispatcher = Dispatchers.Unconfined
            override val lightweightBackground: CoroutineDispatcher = Dispatchers.Unconfined
            override val main: CoroutineDispatcher = Dispatchers.Unconfined
            override val unconfined: CoroutineDispatcher = Dispatchers.Unconfined
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repo = TasksRepository(
            recentTasksDataSource,
            thumbnailDataSource,
            iconDataSource,
            delegate,
            scope,
            unconfined,
        )
        val listeners = TaskStackChangeListeners.getTestInstance()
        val callback = listeners.listenerImpl as Handler.Callback
        val recentsListener = object : TaskStackChangeListener {
            override fun onTaskSnapshotChanged(taskId: Int, snapshot: ThumbnailData?): Boolean =
                RecentsModel.dispatchTaskSnapshotChanged(
                    thumbnailCache,
                    visualListeners,
                    taskId,
                    snapshot,
                )
        }
        listeners.registerTaskStackListener(recentsListener)
        try {
            repo.getAllTaskData(Display.DEFAULT_DISPLAY, forceRefresh = true)
            repo.setVisibleTasks(Display.DEFAULT_DISPLAY, setOf(77))
            assertThat(thumbnailCache.updateTaskSnapShot(77, null)).isFalse()

            val updatedBitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            val updatedSnapshot = ThumbnailData(thumbnail = updatedBitmap, snapshotId = 2L)
            val msg = Message.obtain(null, /* ON_TASK_SNAPSHOT_CHANGED */ 2, 77, 0, updatedSnapshot)
            assertThat(callback.handleMessage(msg)).isTrue()

            val retained = repo.getCurrentThumbnailById(77)
            assertThat(retained).isSameInstanceAs(updatedSnapshot)
            assertThat(updatedBitmap.isRecycled).isFalse()

            // Unrelated task not in cache and not visible must still be recycled.
            val unusedBitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            val unusedSnapshot = ThumbnailData(thumbnail = unusedBitmap, snapshotId = 3L)
            val unusedMsg =
                Message.obtain(null, /* ON_TASK_SNAPSHOT_CHANGED */ 2, 999, 0, unusedSnapshot)
            assertThat(callback.handleMessage(unusedMsg)).isTrue()
            assertThat(unusedBitmap.isRecycled).isTrue()
        } finally {
            listeners.unregisterTaskStackListener(recentsListener)
            scope.cancel()
        }
    }

    @Test
    fun taskThumbnailView_ignoresRecycledBitmapWhenDrawingSnapshot() {
        val context = RuntimeEnvironment.getApplication()
        val view =
            LayoutInflater.from(context).inflate(R.layout.task_thumbnail, null) as TaskThumbnailView
        val imageView = view.findViewById<ImageView>(R.id.task_thumbnail)
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        bitmap.recycle()

        view.setState(
            TaskThumbnailUiState.SnapshotSplash(
                TaskThumbnailUiState.Snapshot(bitmap, Surface.ROTATION_0, Color.DKGRAY),
                ColorDrawable(Color.WHITE),
            )
        )

        assertThat(imageView.drawable).isNull()
        assertThat(imageView.visibility).isEqualTo(View.INVISIBLE)

        val spec = View.MeasureSpec.makeMeasureSpec(16, View.MeasureSpec.EXACTLY)
        imageView.measure(spec, spec)
        imageView.layout(0, 0, 16, 16)
        val out = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        imageView.draw(Canvas(out))
        out.recycle()
    }
}
