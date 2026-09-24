package app.lawnchair.pro

import android.content.Context
import com.android.launcher3.R
import java.util.Calendar

object CakeyGreetings {

    fun getGreeting(context: Context): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11 -> context.getString(R.string.cakey_smartspace_morning)
            in 12..17 -> context.getString(R.string.cakey_smartspace_afternoon)
            in 18..21 -> context.getString(R.string.cakey_smartspace_evening)
            else -> context.getString(R.string.cakey_smartspace_night)
        }
    }
}
