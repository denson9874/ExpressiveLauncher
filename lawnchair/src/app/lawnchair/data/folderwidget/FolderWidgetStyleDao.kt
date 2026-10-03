package app.lawnchair.data.folderwidget

import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderWidgetStyleDao {
    @Query("SELECT * FROM FolderWidgetStyles WHERE folderId = :folderId")
    fun observe(folderId: Int): Flow<FolderWidgetStyleEntity?>

    @Upsert
    suspend fun upsert(e: FolderWidgetStyleEntity)

    @Query("DELETE FROM FolderWidgetStyles WHERE folderId = :folderId")
    suspend fun delete(folderId: Int)

    @RawQuery
    suspend fun checkpoint(q: SupportSQLiteQuery): Int
}
