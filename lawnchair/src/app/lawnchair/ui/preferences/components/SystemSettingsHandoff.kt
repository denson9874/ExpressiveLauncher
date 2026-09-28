package app.lawnchair.ui.preferences.components

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Launch
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import com.android.launcher3.R

/**
 * Rows that leave Expressive for an Android system screen carry a trailing "opens elsewhere"
 * glyph, so people are not surprised to land in system settings instead of launcher settings.
 */
object SystemSettingsHandoff {

    /** Spoken by TalkBack and shown as the glyph's content description. */
    @StringRes
    val announcementRes: Int = R.string.opens_android_settings

    /**
     * A row with its own warning or status widget keeps that widget; the hand-off glyph only
     * fills an otherwise empty trailing slot.
     */
    fun showsIndicator(opensSystemSettings: Boolean, hasOtherEndWidget: Boolean): Boolean =
        opensSystemSettings && !hasOtherEndWidget
}

@Composable
fun SystemSettingsHandoffIcon(modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.AutoMirrored.Rounded.Launch,
        contentDescription = stringResource(id = SystemSettingsHandoff.announcementRes),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .padding(8.dp)
            .size(20.dp),
    )
}
