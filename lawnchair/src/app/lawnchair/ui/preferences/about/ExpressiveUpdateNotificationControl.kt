package app.lawnchair.ui.preferences.about

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.launcher3.R

@Composable
internal fun ExpressiveUpdateNotificationControl(
    config: ExpressiveUpdateConfig,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var notificationsEnabled by remember {
        mutableStateOf(ExpressiveUpdateNotifications.canNotify(context))
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationsEnabled = granted && ExpressiveUpdateNotifications.canNotify(context)
        if (notificationsEnabled) {
            ExpressiveUpdateScheduler.ensureScheduled(context)
        }
    }
    val channelName = stringResource(
        if (config.channel == ExpressiveUpdateChannel.QA) {
            R.string.expressive_update_channel_qa
        } else {
            R.string.expressive_update_channel_release
        },
    )

    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (notificationsEnabled) {
            Text(
                text = stringResource(
                    R.string.expressive_update_notifications_enabled,
                    channelName,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Button(
                onClick = {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(
                    text = stringResource(
                        R.string.expressive_update_notifications_enable,
                        channelName,
                    ),
                )
            }
            Text(
                text = stringResource(R.string.expressive_update_notifications_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
