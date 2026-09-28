package app.lawnchair.ui.popup

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import com.android.launcher3.R
import com.android.launcher3.views.ActivityContext
import com.android.launcher3.views.OptionsPopupView

class LawnchairPopupDialog {
    class LawnchairOptionsPopUp<T>(context: T, attributeSet: AttributeSet) : OptionsPopupView<T>(context, attributeSet)
        where T : Context, T : ActivityContext {

        override fun isShortcutOrWrapper(view: View): Boolean = view.id == R.id.wallpaper_container || super.isShortcutOrWrapper(view)

        override fun assignMarginsAndBackgrounds(viewGroup: ViewGroup) {
            if (useQpr2HomeMenu && viewGroup === this) {
                applyQpr2HomeMenuStyle(this)
            } else {
                super.assignMarginsAndBackgrounds(viewGroup)
            }
        }
    }
}
