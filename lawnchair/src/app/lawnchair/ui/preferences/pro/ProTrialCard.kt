package app.lawnchair.ui.preferences.pro

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.lawnchair.pro.proManager
import com.android.launcher3.R
import kotlinx.coroutines.launch

/**
 * Free-user trial entry in the Redeem Pro sheet: a one-time "trial ended" notice, or the
 * "Try Pro free for 7 days" offer while this install hasn't used its trial.
 */
@Composable
fun ProTrialCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val proManager = proManager()
    val trialEnded by proManager.trialEndedNotice.collectAsState()
    var starting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    if (trialEnded) {
        Card(
            modifier = modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    stringResource(R.string.expressive_pro_trial_ended_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.expressive_pro_trial_ended_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { proManager.dismissTrialEndedNotice() }) {
                        Text(stringResource(R.string.expressive_pro_trial_ended_dismiss))
                    }
                }
            }
        }
        return
    }
    if (proManager.hasUsedTrial) return

    Card(
        modifier = modifier.fillMaxWidth().padding(bottom = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.expressive_pro_trial_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Button(
                onClick = {
                    starting = true
                    error = null
                    scope.launch {
                        proManager.startTrial()
                            .onSuccess {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.expressive_pro_trial_started),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                            .onFailure {
                                error = it.message
                                    ?: context.getString(R.string.expressive_pro_trial_unavailable)
                            }
                        starting = false
                    }
                },
                enabled = !starting,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                if (starting) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.expressive_pro_trial_starting))
                } else {
                    Text(stringResource(R.string.expressive_pro_trial_button))
                }
            }
        }
    }
}
