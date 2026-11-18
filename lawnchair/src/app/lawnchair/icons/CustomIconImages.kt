package app.lawnchair.icons

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.util.Log
import app.lawnchair.icons.picker.IconPickerItem
import app.lawnchair.icons.picker.IconType
import java.io.File
import java.util.UUID

/**
 * Icons the user cropped from their own images. Each is a square PNG in the app's files, so it keeps
 * working after the original photo is deleted; an [IconPickerItem] of type [IconType.Image] names it.
 */
object CustomIconImages {
    /** The icon pack name stored with image icons; no installed pack can have it. */
    const val PACK = "expressive:image"
    const val SIZE_PX = 512

    /** Path of the folder inside the app's files, also used as the backup entry prefix. */
    const val DIR_NAME = "custom_icons"
    private const val TAG = "CustomIconImages"

    fun dir(context: Context) = File(context.filesDir, DIR_NAME)

    /** Saves [bitmap] (already square) and returns the picker item that points at it. */
    fun save(context: Context, bitmap: Bitmap, label: String): IconPickerItem {
        val name = "${UUID.randomUUID()}.png"
        val dir = dir(context).apply { mkdirs() }
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return IconPickerItem(PACK, name, label, IconType.Image)
    }

    /** Deletes the file behind [item] if it is an image icon. */
    fun delete(context: Context, item: IconPickerItem?) {
        if (item?.type != IconType.Image) return
        file(context, item.drawableName)?.delete()
    }

    /**
     * The image as an adaptive icon, so the launcher applies the user's icon shape to it. The image
     * fills the shape: adaptive layers are drawn 1.5x the icon's size, and the 1/6 inset on each
     * side brings the image back to exactly the visible area.
     */
    fun load(context: Context, name: String): Drawable? {
        val file = file(context, name) ?: return null
        val bitmap = BitmapFactory.decodeFile(file.path) ?: run {
            Log.w(TAG, "Could not read custom icon $name")
            return null
        }
        val image = InsetDrawable(BitmapDrawable(context.resources, bitmap), 1f / 6)
        return AdaptiveIconDrawable(image, ColorDrawable(Color.TRANSPARENT))
    }

    /** Only plain file names inside [dir]; a stored name can never reach another folder. */
    private fun file(context: Context, name: String): File? {
        if (name.isEmpty() || name.contains('/') || name.startsWith(".")) return null
        return File(dir(context), name).takeIf { it.isFile }
    }
}
