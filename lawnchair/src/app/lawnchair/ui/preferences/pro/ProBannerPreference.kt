package app.lawnchair.ui.preferences.pro

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.lawnchair.pro.proManager
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ListItemDefaults
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.preferences.components.layout.PreferenceTemplate
import com.android.launcher3.R
import com.android.launcher3.util.MSDLPlayerWrapper
import com.google.android.msdl.data.model.MSDLToken

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ProBannerPreference(
    modifier: Modifier = Modifier,
    initialKeyToRedeem: String = "",
) {
    val context = LocalContext.current
    val proManager = proManager()
    val isPro by proManager.isPro.collectAsState()
    val details by proManager.licenseDetails.collectAsState()
    val isCakey = details?.isCakey == true

    var showRedeemDialog by remember { mutableStateOf(initialKeyToRedeem.isNotBlank()) }
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
                containerColor = when {
                    isCakey -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                    isPro -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    else -> MaterialTheme.colorScheme.surfaceContainer
                },
            ),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (isCakey) stringResource(R.string.cakey_edition_title)
                        else stringResource(R.string.expressive_pro_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(8.dp))
                    if (isCakey) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .height(20.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.tertiary)
                                .padding(horizontal = 8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.cakey_edition_badge),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onTertiary,
                            )
                        }
                    } else if (isPro) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .height(20.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .padding(horizontal = 8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.expressive_pro_status_active).uppercase(),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    } else {
                        ProBadge()
                    }
                }
            },
            description = {
                Text(
                    text = when {
                        isCakey -> stringResource(R.string.cakey_edition_banner_desc)
                        isPro && details != null -> stringResource(
                            R.string.expressive_pro_active_desc,
                            details!!.recipient,
                            details!!.type.displayName,
                        )
                        else -> stringResource(R.string.expressive_pro_free_desc)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            startWidget = {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = when {
                            isCakey -> Icons.Rounded.Favorite
                            isPro -> Icons.Rounded.Verified
                            else -> Icons.Rounded.Star
                        },
                        tint = when {
                            isCakey -> MaterialTheme.colorScheme.tertiary
                            isPro -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                }
            },
            endWidget = {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    contentDescription = null,
                )
            },
        )
    }

    if (showRedeemDialog) {
        RedeemProDialog(
            initialKey = initialKeyToRedeem,
            onDismiss = { showRedeemDialog = false },
        )
    }
}
