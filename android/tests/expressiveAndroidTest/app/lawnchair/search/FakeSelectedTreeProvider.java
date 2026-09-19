package app.lawnchair.search;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Minimal document-tree provider used to exercise the real DocumentsContract traversal. */
public final class FakeSelectedTreeProvider extends ContentProvider {
    public static final String ROOT_ID = "root";
    public static final String LOADING_ROOT_ID = "loading-root";
    public static final String METHOD_RESET_LOADING_QUERY_COUNT = "resetLoadingQueryCount";
    public static final String METHOD_GET_LOADING_QUERY_COUNT = "getLoadingQueryCount";
    public static final String KEY_LOADING_QUERY_COUNT = "loadingQueryCount";

    private static final AtomicInteger LOADING_QUERY_COUNT = new AtomicInteger();

    private static final String[] DOCUMENT_COLUMNS = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    };

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder) {
        String[] columns = projection != null ? projection : DOCUMENT_COLUMNS;
        MatrixCursor cursor = new MatrixCursor(columns);
        List<Document> documents;
        List<String> pathSegments = uri.getPathSegments();
        int parentIndex = pathSegments.size() - 1;
        if (parentIndex > 0 && "children".equals(pathSegments.get(parentIndex))) {
            parentIndex--;
        }
        String parentId = parentIndex >= 0 ? pathSegments.get(parentIndex) : null;

        if (LOADING_ROOT_ID.equals(parentId)) {
            if (LOADING_QUERY_COUNT.incrementAndGet() == 1) {
                Bundle extras = new Bundle();
                extras.putBoolean(DocumentsContract.EXTRA_LOADING, true);
                cursor.setExtras(extras);
                new Handler(Looper.getMainLooper()).postDelayed(
                        () -> getContext().getContentResolver().notifyChange(uri, null),
                        50);
                return cursor;
            }
            documents = List.of(
                    new Document("file:cloud", "cloud-proof.txt", "text/plain", 24, 5_000));
        } else if (ROOT_ID.equals(parentId)) {
            documents = List.of(
                    new Document(
                            "folder:nested",
                            "Nested",
                            DocumentsContract.Document.MIME_TYPE_DIR,
                            0,
                            1_000),
                    new Document("file:root", "proof-root.txt", "text/plain", 12, 2_000),
                    new Document("file:ignored", "notes.txt", "text/plain", 18, 3_000));
        } else if ("folder:nested".equals(parentId)) {
            documents = List.of(
                    new Document(
                            "file:nested",
                            "deep-proof.pdf",
                            "application/pdf",
                            42,
                            4_000));
        } else {
            documents = Collections.emptyList();
        }

        for (Document document : documents) {
            MatrixCursor.RowBuilder row = cursor.newRow();
            for (String column : columns) {
                row.add(column, valueForColumn(document, column));
            }
        }
        return cursor;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        // The provider belongs to the test APK and Android may host it outside the
        // instrumentation process. Expose test state through provider IPC instead of a static
        // getter so the assertion observes the same counter that query() increments.
        if (METHOD_RESET_LOADING_QUERY_COUNT.equals(method)) {
            LOADING_QUERY_COUNT.set(0);
            return new Bundle();
        }
        if (METHOD_GET_LOADING_QUERY_COUNT.equals(method)) {
            Bundle result = new Bundle();
            result.putInt(KEY_LOADING_QUERY_COUNT, LOADING_QUERY_COUNT.get());
            return result;
        }
        return super.call(method, arg, extras);
    }

    private static Object valueForColumn(Document document, String column) {
        if (DocumentsContract.Document.COLUMN_DOCUMENT_ID.equals(column)) {
            return document.id;
        }
        if (DocumentsContract.Document.COLUMN_DISPLAY_NAME.equals(column)) {
            return document.name;
        }
        if (DocumentsContract.Document.COLUMN_MIME_TYPE.equals(column)) {
            return document.mimeType;
        }
        if (DocumentsContract.Document.COLUMN_SIZE.equals(column)) {
            return document.size;
        }
        if (DocumentsContract.Document.COLUMN_LAST_MODIFIED.equals(column)) {
            return document.lastModified;
        }
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    private static final class Document {
        final String id;
        final String name;
        final String mimeType;
        final long size;
        final long lastModified;

        Document(String id, String name, String mimeType, long size, long lastModified) {
            this.id = id;
            this.name = name;
            this.mimeType = mimeType;
            this.size = size;
            this.lastModified = lastModified;
        }
    }
}
