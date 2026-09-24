package app.lawnchair.ui.preferences.pro

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.lawnchair.pro.proManager
import app.lawnchair.ui.preferences.components.layout.PreferenceTemplate
import com.android.launcher3.R
import com.android.launcher3.util.MSDLPlayerWrapper
import com.google.android.msdl.data.model.MSDLToken

import androidx.compose.foundation.layout.height
import androidx.compose.material3.ListItemDefaults
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup

/**
 * Returns true if the user is currently verified as an Expressive Pro user.
 */
@Composable
fun rememberIsPro(): Boolean {
    val proManager = proManager()
    val isPro by proManager.isPro.collectAsState()
    return isPro
}

@Composable
fun ProBadge(
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(20.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.expressive_pro_badge),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/**
 * Declarative feature gate that displays [content] when Pro is active,
 * or [lockedContent] / a standard Pro lock placeholder when inactive.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ProGate(
    modifier: Modifier = Modifier,
    featureMask: Long = 0xFFFFFFFFL,
    lockedTitle: String? = null,
    lockedDescription: String? = null,
    lockedContent: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val proManager = proManager()
    val isPro by proManager.isPro.collectAsState()
    val details by proManager.licenseDetails.collectAsState()

    val isUnlocked = isPro && (details?.let { (it.features and featureMask) != 0L } ?: true)

    if (isUnlocked) {
        content()
    } else if (lockedContent != null) {
        lockedContent()
    } else {
        var showRedeemDialog by remember { mutableStateOf(false) }
        val context = LocalContext.current
        val mMSDLPlayerWrapper = MSDLPlayerWrapper.INSTANCE.get(context)

        PreferenceGroup(
            modifier = modifier,
        ) {
            PreferenceTemplate(
                modifier = Modifier.clickable {
                    mMSDLPlayerWrapper.playToken(MSDLToken.TAP_LOW_EMPHASIS)
                    showRedeemDialog = true
                },
                colors = ListItemDefaults.segmentedColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                ),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = lockedTitle ?: stringResource(R.string.expressive_pro_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        ProBadge()
                    }
                },
                description = {
                    Text(
                        text = lockedDescription ?: stringResource(R.string.expressive_pro_locked_customization),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    )
                },
                startWidget = {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                },
            )
        }

        if (showRedeemDialog) {
            RedeemProDialog(onDismiss = { showRedeemDialog = false })
        }
    }
}
