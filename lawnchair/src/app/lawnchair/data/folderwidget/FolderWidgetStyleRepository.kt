package app.lawnchair.data.folderwidget

import android.content.Context
import app.lawnchair.data.AppDatabase
import app.lawnchair.folder.widget.FolderWidgetStyle
import app.lawnchair.util.MainThreadInitializedObject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class FolderWidgetStyleRepository(
    private val db: AppDatabase,
) {
    constructor(context: Context) : this(AppDatabase.INSTANCE.get(context))

    fun observe(folderId: Int): Flow<FolderWidgetStyle> =
        db.folderWidgetStyleDao().observe(folderId).map { entity ->
            entity?.toStyle() ?: FolderWidgetStyle()
        }

    suspend fun save(folderId: Int, style: FolderWidgetStyle) {
        db.folderWidgetStyleDao().upsert(FolderWidgetStyleEntity.from(folderId, style))
    }

    suspend fun delete(folderId: Int) {
        db.folderWidgetStyleDao().delete(folderId)
    }

    companion object {
        @JvmField
        val INSTANCE = MainThreadInitializedObject(::FolderWidgetStyleRepository)
    }
}
