package app.lawnchair.compat

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.util.Log
import android.view.IWindowManager
import android.view.InputChannel
import android.view.SurfaceControl
import android.window.RemoteTransition
import android.window.TaskSnapshot
import android.window.TaskSnapshotListener
import android.window.TransitionFilter
import com.android.wm.shell.shared.IHomeTransitionListener
import com.android.wm.shell.shared.IShellTransitions
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Android 17 (API 37) framework calls that the Android 16 prebuilt the launcher compiles against
 * does not have. Callers check [LawnchairQuickstepCompat.ATLEAST_CINNAMON_BUN] first and keep their
 * Android 16 path as it was.
 */
object CinnamonBunCompat {

    private const val TAG = "CinnamonBunCompat"

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
            runCatching { cls.getMethod("registerTaskSnapshotListener", TaskSnapshotListener::class.java) }
                .onFailure { Log.e(TAG, "TaskSnapshotManager.registerTaskSnapshotListener not found", it) }
                .getOrNull()
        }
    }

    private val unregisterSnapshotListenerMethod: Method? by lazy {
        snapshotManagerClass?.let { cls ->
            runCatching { cls.getMethod("unregisterTaskSnapshotListener", TaskSnapshotListener::class.java) }
                .onFailure { Log.e(TAG, "TaskSnapshotManager.unregisterTaskSnapshotListener not found", it) }
                .getOrNull()
        }
    }

    private val wrapToBitmapMethod: Method? by lazy {
        runCatching { TaskSnapshot::class.java.getMethod("wrapToBitmap") }
            .onFailure { Log.e(TAG, "TaskSnapshot.wrapToBitmap not found", it) }
            .getOrNull()
    }

    private val closeBufferMethod: Method? by lazy {
        runCatching { TaskSnapshot::class.java.getMethod("closeBuffer") }.getOrNull()
    }

    private val getDensityDpiMethod: Method? by lazy {
        runCatching { TaskSnapshot::class.java.getMethod("getDensityDpi") }.getOrNull()
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
     * so this goes through `wrapToBitmap()`, then releases the snapshot's own reference and applies
     * the density the snapshot was taken at, as SystemUI's ThumbnailData does.
     */
    @JvmStatic
    fun wrapSnapshotToBitmap(snapshot: TaskSnapshot): Bitmap? {
        val bitmap = invoke(wrapToBitmapMethod, snapshot) as Bitmap?
        invoke(closeBufferMethod, snapshot)
        val densityDpi = invoke(getDensityDpiMethod, snapshot) as Int? ?: 0
        if (bitmap != null && densityDpi > 0 && bitmap.density != densityDpi) {
            bitmap.density = densityDpi
        }
        return bitmap
    }

    /**
     * `ScreenCaptureInternal.captureLayers(layer, sourceCrop, 1f).asBitmap()`. Android 17 moved
     * layer capture out of ScreenCapture, so the Android 16 builder no longer exists.
     */
    @JvmStatic
    fun captureLayers(layer: SurfaceControl, sourceCrop: Rect): Bitmap? {
        val buffer = invoke(captureLayersMethod, null, layer, sourceCrop, 1f) ?: return null
        val asBitmap = runCatching { buffer.javaClass.getMethod("asBitmap") }.getOrNull()
        return invoke(asBitmap, buffer) as Bitmap?
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
        transactOneway(shellTransitions.asBinder(), TRANSACTION_REGISTER_REMOTE) {
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
        transactOneway(shellTransitions.asBinder(), TRANSACTION_SET_HOME_TRANSITION_LISTENER) {
            writeStrongInterface(listener)
            writeInt(userId)
        }
    }

    private inline fun transactOneway(binder: IBinder, code: Int, write: Parcel.() -> Unit) {
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(IShellTransitions.DESCRIPTOR)
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
