package app.lawnchair.util

import androidx.annotation.StringRes
import com.android.launcher3.BuildConfig

/** Selects product copy without replacing Lawnchair's translated resources in upstream flavors. */
@StringRes
fun productStringId(
    @StringRes lawnchairResourceId: Int,
    @StringRes expressiveResourceId: Int,
): Int = if (BuildConfig.STANDARD_HOME_ONLY) expressiveResourceId else lawnchairResourceId
