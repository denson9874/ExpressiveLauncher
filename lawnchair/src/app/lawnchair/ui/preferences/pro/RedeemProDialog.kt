package app.lawnchair.ui.preferences.pro

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.lawnchair.pro.ProLicenseDetails
import app.lawnchair.pro.proManager
import com.android.launcher3.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RedeemProDialog(
    initialKey: String = "",
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    onDismiss: () -> Unit,
) {
    val proManager = proManager()
    val isPro by proManager.isPro.collectAsState()
    val details by proManager.licenseDetails.collectAsState()

    var keyInput by remember { mutableStateOf(initialKey) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showDeactivateConfirm by remember { mutableStateOf(false) }

    val clipboardManager = LocalClipboardManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(initialKey) {
        if (initialKey.isNotBlank()) {
            keyInput = initialKey.trim()
            // Auto validate if format matches
            if (keyInput.startsWith("EXPR-PRO-") || keyInput.startsWith("EXPR-")) {
                val res = proManager.activate(keyInput)
                if (res.isFailure) {
                    errorMessage = res.exceptionOrNull()?.message ?: "Invalid license key"
                } else {
                    errorMessage = null
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 12.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (isPro) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.secondaryContainer,
                        ),
                ) {
                    Icon(
                        imageVector = if (isPro) Icons.Rounded.Verified else Icons.Rounded.Star,
                        contentDescription = null,
                        tint = if (isPro) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        text = stringResource(R.string.expressive_pro_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = if (isPro) stringResource(R.string.expressive_pro_status_active)
                        else stringResource(R.string.expressive_pro_redeem_title),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (isPro && details != null) {
                // Active Pro License View
                ProActiveContent(
                    details = details!!,
                    showDeactivateConfirm = showDeactivateConfirm,
                    onToggleDeactivateConfirm = { showDeactivateConfirm = it },
                    onDeactivate = {
                        proManager.deactivate()
                        showDeactivateConfirm = false
                        keyInput = ""
                        errorMessage = null
                    },
                    onDismiss = onDismiss,
                )
            } else {
                // Free Core / Redeem View
                Text(
                    text = stringResource(R.string.expressive_pro_redeem_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp),
                )

                OutlinedTextField(
                    value = keyInput,
                    onValueChange = {
                        keyInput = it
                        errorMessage = null
                    },
                    label = { Text(stringResource(R.string.expressive_pro_key_label)) },
                    placeholder = { Text(stringResource(R.string.expressive_pro_key_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    maxLines = 3,
                    isError = errorMessage != null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                    trailingIcon = {
                        if (keyInput.isNotEmpty()) {
                            IconButton(onClick = { keyInput = ""; errorMessage = null }) {
                                Icon(Icons.Rounded.Clear, contentDescription = "Clear")
                            }
                        } else {
                            IconButton(onClick = {
                                val pasteText = clipboardManager.getText()?.text
                                if (!pasteText.isNullOrBlank()) {
                                    keyInput = pasteText.trim()
                                    errorMessage = null
                                }
                            }) {
                                Icon(
                                    Icons.Rounded.ContentPaste,
                                    contentDescription = stringResource(R.string.expressive_pro_paste_clipboard),
                                )
                            }
                        }
                    },
                )

                AnimatedVisibility(visible = errorMessage != null) {
                    errorMessage?.let { error ->
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Error,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = error,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))

                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(stringResource(R.string.expressive_pro_close))
                    }
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = {
                            keyboardController?.hide()
                            val res = proManager.activate(keyInput.trim())
                            if (res.isFailure) {
                                errorMessage = res.exceptionOrNull()?.message ?: "Verification failed"
                            } else {
                                errorMessage = null
                            }
                        },
                        enabled = keyInput.isNotBlank(),
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(stringResource(R.string.expressive_pro_activate_button))
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProActiveContent(
    details: ProLicenseDetails,
    showDeactivateConfirm: Boolean,
    onToggleDeactivateConfirm: (Boolean) -> Unit,
    onDeactivate: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.expressive_pro_license_details),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.expressive_pro_recipient, details.recipient),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = stringResource(R.string.expressive_pro_tier, details.type.displayName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            val expiryText = if (details.isLifetime) {
                stringResource(R.string.expressive_pro_lifetime)
            } else {
                formatTimestamp(details.expiresAt)
            }
            Text(
                text = stringResource(R.string.expressive_pro_expires, expiryText),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }

    AnimatedVisibility(visible = showDeactivateConfirm) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = stringResource(R.string.expressive_pro_deactivate_confirm),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { onToggleDeactivateConfirm(false) },
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(stringResource(android.R.string.cancel))
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = onDeactivate,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(stringResource(R.string.expressive_pro_deactivate_button))
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(16.dp))

    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
    ) {
        if (!showDeactivateConfirm) {
            OutlinedButton(
                onClick = { onToggleDeactivateConfirm(true) },
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(
                    text = stringResource(R.string.expressive_pro_deactivate_button),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        } else {
            Spacer(Modifier.width(1.dp))
        }

        Button(
            onClick = onDismiss,
            shapes = ButtonDefaults.shapes(),
        ) {
            Text(stringResource(R.string.expressive_pro_close))
        }
    }
}

private fun formatTimestamp(timestampSeconds: Long): String {
    val dt = Date(timestampSeconds * 1000L)
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    sdf.timeZone = TimeZone.getDefault()
    return sdf.format(dt)
}
