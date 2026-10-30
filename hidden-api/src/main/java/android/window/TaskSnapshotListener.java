package android.window;

/** Android 17 (API 37) task snapshot callbacks, which left ITaskStackListener. */
public abstract class TaskSnapshotListener {
    protected void onTaskSnapshotChanged(int taskId, TaskSnapshot snapshot) {
        throw new RuntimeException("Stub!");
    }

    protected void onTaskSnapshotReleased(int taskId) {
        throw new RuntimeException("Stub!");
    }
}
