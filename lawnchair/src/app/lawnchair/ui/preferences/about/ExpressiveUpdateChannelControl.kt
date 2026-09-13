package app.lawnchair.ui.preferences.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.launcher3.R

internal const val EXPRESSIVE_UPDATE_CHANNEL_CONTROL_TAG = "expressive_update_channel_control"

@Composable
internal fun ExpressiveUpdateChannelControl(
    selectedChannel: ExpressiveUpdateChannel,
    onSelect: (ExpressiveUpdateChannel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val channels = listOf(ExpressiveUpdateChannel.RELEASE, ExpressiveUpdateChannel.QA)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag(EXPRESSIVE_UPDATE_CHANNEL_CONTROL_TAG),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.expressive_update_channel_label), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.expressive_update_channel_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                channels.forEachIndexed { index, channel ->
                    SegmentedButton(
                        selected = selectedChannel == channel,
                        onClick = { onSelect(channel) },
                        shape = SegmentedButtonDefaults.itemShape(index, channels.size),
                        modifier = Modifier.testTag("expressive_update_channel_${channel.wireName}"),
                    ) {
                        Text(stringResource(channel.displayNameResId))
                    }
                }
            }
            Text(
                stringResource(
                    if (selectedChannel == ExpressiveUpdateChannel.QA) R.string.expressive_update_channel_qa_description
                    else R.string.expressive_update_channel_stable_description,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            installedExpressiveUpdateConfig()?.channel?.let { installed ->
                Text(
                    stringResource(R.string.expressive_update_installed_channel, stringResource(installed.displayNameResId)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}
