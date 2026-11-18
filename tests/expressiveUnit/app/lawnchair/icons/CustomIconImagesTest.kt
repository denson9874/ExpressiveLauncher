package app.lawnchair.icons

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.test.core.app.ApplicationProvider
import app.lawnchair.icons.picker.IconPickerItem
import app.lawnchair.icons.picker.IconType
import app.lawnchair.ui.preferences.components.CropLayout
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class CustomIconImagesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun savedImageLoadsAsAdaptiveIcon_andDeleteRemovesIt() {
        val item = CustomIconImages.save(context, Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888), "Camera")

        assertThat(item.packPackageName).isEqualTo(CustomIconImages.PACK)
        assertThat(item.type).isEqualTo(IconType.Image)
        assertThat(CustomIconImages.load(context, item.drawableName)).isInstanceOf(AdaptiveIconDrawable::class.java)

        CustomIconImages.delete(context, item)
        assertThat(CustomIconImages.load(context, item.drawableName)).isNull()
    }

    @Test
    fun namesOutsideTheFolderAreRefused() {
        File(context.filesDir, "secret.png").writeText("not an icon folder file")
        assertThat(CustomIconImages.load(context, "../secret.png")).isNull()

        CustomIconImages.delete(context, IconPickerItem(CustomIconImages.PACK, "../secret.png", "x", IconType.Image))
        assertThat(File(context.filesDir, "secret.png").exists()).isTrue()
    }

    @Test
    fun cropFrameStaysCoveredAndMapsToTheImage() {
        // A 400x200 image in a 100x100 view: the 80px frame covers the image's full height.
        val image = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        val centred = CropLayout(Size(100f, 100f), image, zoom = 1f, requestedPan = Offset.Zero)
        assertThat(centred.scale).isEqualTo(0.4f)

        // Panning far right stops where the image's left edge meets the frame.
        val panned = CropLayout(Size(100f, 100f), image, zoom = 1f, requestedPan = Offset(1000f, 1000f))
        assertThat(panned.pan).isEqualTo(Offset(40f, 0f))
        assertThat(panned.imageLeft).isEqualTo(panned.frameLeft)

        val crop = panned.crop(image, 512)
        assertThat(crop.width).isEqualTo(512)
        assertThat(crop.height).isEqualTo(512)
    }
}
