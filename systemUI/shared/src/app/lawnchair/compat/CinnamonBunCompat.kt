package app.lawnchair.compat

import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.util.Log
import android.view.IWindowManager
import android.view.InputChannel
import android.view.KeyEvent
import android.view.SurfaceControl
import android.window.RemoteTransition
import android.window.TaskSnapshot
import android.window.TaskSnapshotListener
import android.window.TransitionFilter
import com.android.systemui.shared.recents.ISystemUiProxy
import com.android.wm.shell.shared.IHomeTransitionListener
import com.android.wm.shell.shared.IShellTransitions
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger

/**
 * Android 17 (API 37) framework calls that the Android 16 prebuilt the launcher compiles against
 * does not have. Callers check [LawnchairQuickstepCompat.ATLEAST_CINNAMON_BUN] first and keep their
 * Android 16 path as it was.
 */
object CinnamonBunCompat {

    private const val TAG = "CinnamonBunCompat"

    // Look these up by name, never with `::class.java`. R8 merges this file's lazy lambdas with
    // unrelated ones and moves class constants ahead of the shared switch, so a class literal here
    // crashes Settings and Compose text on any device where the class is missing.
    private const val TASK_SNAPSHOT = "android.window.TaskSnapshot"
    private const val TASK_SNAPSHOT_LISTENER = "android.window.TaskSnapshotListener"

    /** `android.window.TaskSnapshotManager`, which took over task snapshots from ActivityTaskManager. */
    private val snapshotManagerClass: Class<*>? by lazy {
        runCatching { Class.forName("android.window.TaskSnapshotManager") }
            .onFailure { Log.e(TAG, "TaskSnapshotManager not found", it) }
            .getOrNull()
    }

    private val snapshotManager: Any? by lazy {
        snapshotManagerClass?.let { cls ->
            runCatching { cls.getMethod("getInstance").invoke(null) }
                .onFailure { Log.e(TAG, "TaskSnapshotManager.getInstance failed", it) }
                .getOrNull()
        }
    }

    private val getTaskSnapshotMethod: Method? by lazy {
        snapshotManagerClass?.let { cls ->
            runCatching {
                cls.getMethod("getTaskSnapshot", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            }.onFailure { Log.e(TAG, "TaskSnapshotManager.getTaskSnapshot not found", it) }.getOrNull()
        }
    }

    private val takeTaskSnapshotMethod: Method? by lazy {
        snapshotManagerClass?.let { cls ->
            runCatching {
                cls.getMethod("takeTaskSnapshot", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            }.onFailure { Log.e(TAG, "TaskSnapshotManager.takeTaskSnapshot not found", it) }.getOrNull()
        }
    }

    private val registerSnapshotListenerMethod: Method? by lazy {
        snapshotManagerClass?.let { cls ->
            runCatching { cls.getMethod("registerTaskSnapshotListener", Class.forName(TASK_SNAPSHOT_LISTENER)) }
                .onFailure { Log.e(TAG, "TaskSnapshotManager.registerTaskSnapshotListener not found", it) }
                .getOrNull()
        }
    }

    private val unregisterSnapshotListenerMethod: Method? by lazy {
        snapshotManagerClass?.let { cls ->
            runCatching { cls.getMethod("unregisterTaskSnapshotListener", Class.forName(TASK_SNAPSHOT_LISTENER)) }
                .onFailure { Log.e(TAG, "TaskSnapshotManager.unregisterTaskSnapshotListener not found", it) }
                .getOrNull()
        }
    }

    private val wrapToBitmapMethod: Method? by lazy {
        runCatching { Class.forName(TASK_SNAPSHOT).getMethod("wrapToBitmap") }
            .onFailure { Log.e(TAG, "TaskSnapshot.wrapToBitmap not found", it) }
            .getOrNull()
    }

    private val closeBufferMethod: Method? by lazy {
        runCatching { Class.forName(TASK_SNAPSHOT).getMethod("closeBuffer") }.getOrNull()
    }

    private val isBufferValidMethod: Method? by lazy {
        runCatching { Class.forName(TASK_SNAPSHOT).getMethod("isBufferValid") }.getOrNull()
    }

    private val setSnapshotTrackerMethod: Method? by lazy {
        runCatching {
            Class.forName(TASK_SNAPSHOT).declaredMethods
                .firstOrNull { it.name == "setSnapshotTracker" && it.parameterCount == 1 }
                ?.apply { isAccessible = true }
        }.getOrNull()
    }

    private val snapshotTrackerField: Field? by lazy {
        runCatching {
            Class.forName(TASK_SNAPSHOT).getDeclaredField("mSnapshotTracker").apply {
                isAccessible = true
            }
        }.getOrNull()
    }

    private val snapshotHardwareBufferField: Field? by lazy {
        runCatching {
            Class.forName(TASK_SNAPSHOT).getDeclaredField("mSnapshot").apply {
                isAccessible = true
            }
        }.getOrNull()
    }

    private val createInputConsumerMethod: Method? by lazy {
        runCatching {
            IWindowManager::class.java.getMethod(
                "createInputConsumer",
                IBinder::class.java,
                String::class.java,
                Int::class.javaPrimitiveType,
            )
        }.onFailure { Log.e(TAG, "IWindowManager.createInputConsumer(IBinder, String, int) not found", it) }
            .getOrNull()
    }

    private val setFilterMethod: Method? by lazy {
        runCatching { RemoteTransition::class.java.getMethod("setFilter", TransitionFilter::class.java) }
            .onFailure { Log.e(TAG, "RemoteTransition.setFilter not found", it) }
            .getOrNull()
    }

    private val captureLayersMethod: Method? by lazy {
        runCatching {
            Class.forName("android.window.ScreenCaptureInternal").getMethod(
                "captureLayers",
                SurfaceControl::class.java,
                Rect::class.java,
                Float::class.javaPrimitiveType,
            )
        }.onFailure { Log.e(TAG, "ScreenCaptureInternal.captureLayers not found", it) }
            .getOrNull()
    }

    // IShellTransitions transaction codes, IBinder.FIRST_CALL_TRANSACTION + the AIDL id. The
    // generated constants are package-private, and Android 17 kept the ids but changed the args.
    private const val TRANSACTION_REGISTER_REMOTE = IBinder.FIRST_CALL_TRANSACTION + 1
    private const val TRANSACTION_SET_HOME_TRANSITION_LISTENER = IBinder.FIRST_CALL_TRANSACTION + 4
    private const val TRANSACTION_REGISTER_REMOTE_FOR_TAKEOVER = IBinder.FIRST_CALL_TRANSACTION + 6

    // ISystemUiProxy Android 17 transaction codes (IBinder.FIRST_CALL_TRANSACTION + AIDL id).
    private const val TRANSACTION_SYSUI_ON_BACK_EVENT = IBinder.FIRST_CALL_TRANSACTION + 44
    private const val TRANSACTION_SYSUI_ON_OVERVIEW_SHOWN = IBinder.FIRST_CALL_TRANSACTION + 61
    private const val TRANSACTION_SYSUI_ON_OVERVIEW_HIDDEN = IBinder.FIRST_CALL_TRANSACTION + 62

    // TaskSnapshotManager.RESOLUTION_HIGH and RESOLUTION_LOW
    private const val RESOLUTION_HIGH = 1
    private const val RESOLUTION_LOW = 2

    /** `TaskSnapshotManager.getInstance().getTaskSnapshot(taskId, resolution)`. */
    @JvmStatic
    fun getTaskSnapshot(taskId: Int, isLowResolution: Boolean): TaskSnapshot? {
        val resolution = if (isLowResolution) RESOLUTION_LOW else RESOLUTION_HIGH
        return invoke(getTaskSnapshotMethod, snapshotManager, taskId, resolution) as TaskSnapshot?
    }

    /** `TaskSnapshotManager.getInstance().takeTaskSnapshot(taskId, updateCache)`. */
    @JvmStatic
    fun takeTaskSnapshot(taskId: Int, updateCache: Boolean): TaskSnapshot? =
        invoke(takeTaskSnapshotMethod, snapshotManager, taskId, updateCache) as TaskSnapshot?

    /**
     * `TaskSnapshotManager.getInstance().registerTaskSnapshotListener(listener)`. The Android 16
     * prebuilt already has a TaskSnapshotManager, just without the listener methods.
     */
    @JvmStatic
    fun registerTaskSnapshotListener(listener: TaskSnapshotListener): Boolean {
        val method = registerSnapshotListenerMethod ?: return false
        val manager = snapshotManager ?: return false
        return try {
            method.invoke(manager, listener)
            true
        } catch (e: InvocationTargetException) {
            Log.e(TAG, "registerTaskSnapshotListener failed", e.targetException)
            false
        } catch (e: ReflectiveOperationException) {
            Log.e(TAG, "registerTaskSnapshotListener not callable", e)
            false
        }
    }

    /** `TaskSnapshotManager.getInstance().unregisterTaskSnapshotListener(listener)`. */
    @JvmStatic
    fun unregisterTaskSnapshotListener(listener: TaskSnapshotListener) {
        invoke(unregisterSnapshotListenerMethod, snapshotManager, listener)
    }

    /**
     * Wraps the snapshot's buffer in a bitmap. Android 17 no longer hands out the hardware buffer,
     * so this goes through `wrapToBitmap()`, then releases the snapshot's own reference.
     *
     * The bitmap keeps the process density, as `Bitmap.wrapHardwareBuffer` gives it on Android 16.
     * Android 17's SystemUI applies the snapshot's own density instead, but its PreviewPositionHelper
     * scales for that and ours doesn't, so a snapshot taken at another density (after a Display
     * size or Screen resolution change) would be drawn too large or too small.
     */
    @JvmStatic
    fun wrapSnapshotToBitmap(snapshot: TaskSnapshot): Bitmap? =
        try {
            invoke(wrapToBitmapMethod, snapshot) as Bitmap?
        } finally {
            closeSnapshotBuffer(snapshot)
        }

    /** `TaskSnapshot.closeBuffer()`, replacing `getHardwareBuffer().close()` on Android 17+. */
    @JvmStatic
    fun closeSnapshotBuffer(snapshot: TaskSnapshot?) {
        closeSnapshotBufferCompat(snapshot)
    }

    /**
     * Closes a `TaskSnapshot`'s buffer while respecting shared `mReferenceCount` owners.
     *
     * Stock Android 17's `TaskSnapshotManager.SingleTaskTracker` can drop an equal-`mCaptureTime`
     * tracker from its `TreeSet`, causing `removeTracker()` to return early without decrementing
     * `mReferenceCount` or closing `mSnapshot`. When `closeBuffer()` legitimately decrements a
     * shared refcount (`2 -> 1`), the buffer MUST remain open for the remaining owner.
     */
    @JvmStatic
    fun closeSnapshotBufferCompat(snapshot: Any?) {
        if (snapshot == null) return
        val isFrameworkSnapshot = snapshot.javaClass.name == TASK_SNAPSHOT
        val closeMethod = if (isFrameworkSnapshot) {
            closeBufferMethod
        } else {
            runCatching {
                snapshot.javaClass.getMethod("closeBuffer").apply { isAccessible = true }
            }.getOrNull()
        }
        val validMethod = if (isFrameworkSnapshot) {
            isBufferValidMethod
        } else {
            runCatching {
                snapshot.javaClass.getMethod("isBufferValid").apply { isAccessible = true }
            }.getOrNull()
        }

        val trackerBefore = extractSnapshotTracker(snapshot, isFrameworkSnapshot)
        val refBefore = readTrackerRefCount(trackerBefore)

        invoke(closeMethod, snapshot)

        val stillValid = invoke(validMethod, snapshot) as? Boolean ?: false
        if (!stillValid) return

        val trackerAfter = extractSnapshotTracker(snapshot, isFrameworkSnapshot)
        val refAfter = readTrackerRefCount(trackerAfter)

        // TaskSnapshotManager legitimately decremented a shared reference (e.g. 2 -> 1); leave
        // the buffer open for the remaining owner(s).
        if (refBefore != null && refAfter != null && refAfter < refBefore && refAfter > 0) {
            return
        }

        // Stock Android 17 removeTracker() returned early without decrementing an orphaned tracker
        // that still has multiple logical owners: decrement refcount rather than closing early.
        if (trackerAfter != null && refBefore != null && refBefore > 1 && refAfter == refBefore) {
            writeTrackerRefCount(trackerAfter, refBefore - 1)
            return
        }

        // Otherwise the buffer is orphaned with no remaining owners (refcount <= 1 and unchanged,
        // or tracker already cleared): detach tracker and close the native HardwareBuffer.
        val detachMethod = if (isFrameworkSnapshot) {
            setSnapshotTrackerMethod
        } else {
            runCatching {
                snapshot.javaClass.declaredMethods
                    .firstOrNull { it.name == "setSnapshotTracker" && it.parameterCount == 1 }
                    ?.apply { isAccessible = true }
            }.getOrNull()
        }
        if (detachMethod != null) {
            invoke(detachMethod, snapshot, null)
            invoke(closeMethod, snapshot)
        }
        val stillValidAfterDetach = invoke(validMethod, snapshot) as? Boolean ?: false
        if (stillValidAfterDetach) {
            val hbField = if (isFrameworkSnapshot) {
                snapshotHardwareBufferField
            } else {
                findDeclaredField(snapshot.javaClass, "mSnapshot")
            }
            val hardwareBuffer = runCatching { hbField?.get(snapshot) }.getOrNull()
            closeIfCloseable(hardwareBuffer)
        }
    }

    private fun extractSnapshotTracker(snapshot: Any, isFrameworkSnapshot: Boolean): Any? {
        val field = if (isFrameworkSnapshot) {
            snapshotTrackerField
        } else {
            findDeclaredField(snapshot.javaClass, "mSnapshotTracker")
        } ?: return null
        val raw = runCatching { field.get(snapshot) }.getOrNull() ?: return null
        return if (raw is WeakReference<*>) raw.get() else raw
    }

    private fun readTrackerRefCount(tracker: Any?): Int? {
        if (tracker == null) return null
        val field = findDeclaredField(tracker.javaClass, "mReferenceCount") ?: return null
        return when (val value = runCatching { field.get(tracker) }.getOrNull()) {
            is Int -> value
            is AtomicInteger -> value.get()
            else -> null
        }
    }

    private fun writeTrackerRefCount(tracker: Any, newValue: Int): Boolean {
        val field = findDeclaredField(tracker.javaClass, "mReferenceCount") ?: return false
        return runCatching {
            when (val current = field.get(tracker)) {
                is AtomicInteger -> current.set(newValue)
                else -> field.setInt(tracker, newValue)
            }
            true
        }.getOrDefault(false)
    }

    private fun findDeclaredField(clazz: Class<*>, name: String): Field? {
        var current: Class<*>? = clazz
        while (current != null) {
            val field = runCatching {
                current.getDeclaredField(name).apply { isAccessible = true }
            }.getOrNull()
            if (field != null) return field
            current = current.superclass
        }
        return null
    }

    /**
     * `ScreenCaptureInternal.captureLayers(layer, sourceCrop, 1f).asBitmap()`. Android 17 moved
     * layer capture out of ScreenCapture, so the Android 16 builder no longer exists.
     */
    @JvmStatic
    fun captureLayers(layer: SurfaceControl, sourceCrop: Rect): Bitmap? {
        val buffer = invoke(captureLayersMethod, null, layer, sourceCrop, 1f) ?: return null
        return try {
            val asBitmap = runCatching {
                buffer.javaClass.getMethod("asBitmap").apply { isAccessible = true }
            }.getOrNull()
            invoke(asBitmap, buffer) as Bitmap?
        } finally {
            closeScreenshotBuffer(buffer)
        }
    }

    /**
     * Closes the native HardwareBuffer (and gainmap HardwareBuffer, if present) owned by a
     * ScreenshotHardwareBuffer because `asBitmap()` wraps without closing the source buffers.
     */
    @JvmStatic
    fun closeScreenshotBuffer(buffer: Any?) {
        if (buffer == null) return
        for (getterName in arrayOf("getHardwareBuffer", "getGainmap", "getGainmapHardwareBuffer")) {
            val getter = runCatching {
                buffer.javaClass.getMethod(getterName).apply { isAccessible = true }
            }.getOrNull() ?: continue
            val resource = runCatching { getter.invoke(buffer) }.getOrNull()
            closeIfCloseable(resource)
        }
    }

    private fun closeIfCloseable(resource: Any?) {
        when (resource) {
            is HardwareBuffer -> if (!resource.isClosed) {
                runCatching { resource.close() }
            }
            is AutoCloseable -> runCatching { resource.close() }
        }
    }

    /** `IWindowManager.createInputConsumer(token, name, displayId)`, which now returns the channel. */
    @JvmStatic
    fun createInputConsumer(
        windowManager: IWindowManager,
        token: IBinder,
        name: String,
        displayId: Int,
    ): InputChannel? = invoke(createInputConsumerMethod, windowManager, token, name, displayId) as InputChannel?

    /**
     * `IShellTransitions.registerRemote(remoteTransition)`. Android 17 carries the filter on the
     * [RemoteTransition] instead of as its own argument. Returns false, without registering, if
     * the filter can't be attached: the Shell must never get an unfiltered remote.
     */
    @JvmStatic
    @Throws(RemoteException::class)
    fun registerRemote(
        shellTransitions: IShellTransitions,
        filter: TransitionFilter,
        remoteTransition: RemoteTransition,
    ): Boolean {
        val method = setFilterMethod ?: return false
        try {
            method.invoke(remoteTransition, filter)
        } catch (e: ReflectiveOperationException) {
            Log.e(TAG, "RemoteTransition.setFilter failed", e)
            return false
        }
        transactOneway(
            shellTransitions.asBinder(),
            IShellTransitions.DESCRIPTOR,
            TRANSACTION_REGISTER_REMOTE,
        ) {
            writeTypedObject(remoteTransition, 0)
        }
        return true
    }

    /**
     * `IShellTransitions.registerRemoteForTakeover(remoteTransition)`. Android 17 carries the
     * filter on [RemoteTransition] instead of as a separate argument.
     */
    @JvmStatic
    @Throws(RemoteException::class)
    fun registerRemoteForTakeover(
        shellTransitions: IShellTransitions,
        filter: TransitionFilter,
        remoteTransition: RemoteTransition,
    ): Boolean {
        val method = setFilterMethod ?: return false
        try {
            method.invoke(remoteTransition, filter)
        } catch (e: ReflectiveOperationException) {
            Log.e(TAG, "RemoteTransition.setFilter failed", e)
            return false
        }
        transactOneway(
            shellTransitions.asBinder(),
            IShellTransitions.DESCRIPTOR,
            TRANSACTION_REGISTER_REMOTE_FOR_TAKEOVER,
        ) {
            writeTypedObject(remoteTransition, 0)
        }
        return true
    }

    /** `IShellTransitions.setHomeTransitionListener(listener, userId)`, which gained the user id. */
    @JvmStatic
    @Throws(RemoteException::class)
    fun setHomeTransitionListener(
        shellTransitions: IShellTransitions,
        listener: IHomeTransitionListener?,
        userId: Int,
    ) {
        transactOneway(
            shellTransitions.asBinder(),
            IShellTransitions.DESCRIPTOR,
            TRANSACTION_SET_HOME_TRANSITION_LISTENER,
        ) {
            writeStrongInterface(listener)
            writeInt(userId)
        }
    }

    /** `ISystemUiProxy.onBackEvent(keyEvent, displayId)`, which gained `displayId` on Android 17. */
    @JvmStatic
    @Throws(RemoteException::class)
    fun onBackEvent(
        systemUiProxy: ISystemUiProxy,
        keyEvent: KeyEvent?,
        displayId: Int,
    ) {
        transactOneway(
            systemUiProxy.asBinder(),
            ISystemUiProxy.DESCRIPTOR,
            TRANSACTION_SYSUI_ON_BACK_EVENT,
        ) {
            writeTypedObject(keyEvent, 0)
            writeInt(displayId)
        }
    }

    /** `ISystemUiProxy.onOverviewShown(displayId)` (display-aware replacement for Ord 7). */
    @JvmStatic
    @Throws(RemoteException::class)
    fun onOverviewShown(systemUiProxy: ISystemUiProxy, displayId: Int) {
        transactOneway(
            systemUiProxy.asBinder(),
            ISystemUiProxy.DESCRIPTOR,
            TRANSACTION_SYSUI_ON_OVERVIEW_SHOWN,
        ) {
            writeInt(displayId)
        }
    }

    /** `ISystemUiProxy.onOverviewHidden(displayId)` (Android 17 Ord 63). */
    @JvmStatic
    @Throws(RemoteException::class)
    fun onOverviewHidden(systemUiProxy: ISystemUiProxy, displayId: Int) {
        transactOneway(
            systemUiProxy.asBinder(),
            ISystemUiProxy.DESCRIPTOR,
            TRANSACTION_SYSUI_ON_OVERVIEW_HIDDEN,
        ) {
            writeInt(displayId)
        }
    }

    private inline fun transactOneway(
        binder: IBinder,
        descriptor: String,
        code: Int,
        write: Parcel.() -> Unit,
    ) {
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(descriptor)
            data.write()
            binder.transact(code, data, null, IBinder.FLAG_ONEWAY)
        } finally {
            data.recycle()
        }
    }

    private fun invoke(method: Method?, receiver: Any?, vararg args: Any?): Any? {
        if (method == null) return null
        return try {
            method.invoke(receiver, *args)
        } catch (e: InvocationTargetException) {
            Log.e(TAG, "${method.name} failed", e.targetException)
            null
        } catch (e: ReflectiveOperationException) {
            Log.e(TAG, "${method.name} not callable", e)
            null
        } catch (e: RuntimeException) {
            Log.e(TAG, "${method.name} not callable", e)
            null
        }
    }
}
