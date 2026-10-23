package app.lawnchair.data

import androidx.room.TypeConverter
import app.lawnchair.data.folder.FolderItemEntity
import app.lawnchair.util.parseComponentKeyLenient
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.util.ComponentKey

class Converters {

    @TypeConverter
    fun fromComponentKey(value: ComponentKey?) = value?.toString()

    // Lenient, so an icon override saved for a shortcut whose ID contains '#' can be read back.
    @TypeConverter
    fun toComponentKey(value: String?) = value?.let { parseComponentKeyLenient(it) }
}

fun AppInfo.toEntity(folderId: Int): FolderItemEntity {
    return FolderItemEntity(
        folderId = folderId,
        componentKey = Converters().fromComponentKey(this.toComponentKey()),
    )
}
