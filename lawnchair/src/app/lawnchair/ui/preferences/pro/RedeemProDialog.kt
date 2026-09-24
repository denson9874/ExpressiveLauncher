package app.lawnchair.ui.preferences.pro

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.lawnchair.pro.ProActivationService
import app.lawnchair.pro.ProDeviceId
import app.lawnchair.pro.ProLicenseDetails
import app.lawnchair.pro.VerifyDonationRequest
import app.lawnchair.pro.proManager
import app.lawnchair.ui.preferences.about.AboutDestinations
import com.android.launcher3.R
import kotlinx.coroutines.launch
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
    val context = LocalContext.current
    val proManager = proManager()
    val isPro by proManager.isPro.collectAsState()
    val details by proManager.licenseDetails.collectAsState()

    var keyInput by remember { mutableStateOf(initialKey) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var showDeactivateConfirm by remember { mutableStateOf(false) }

    var selectedTab by remember { mutableIntStateOf(if (initialKey.isNotBlank()) 1 else 0) }
    var isCheckingStatus by remember { mutableStateOf(false) }

    var showTransactionInput by remember { mutableStateOf(false) }
    var transactionIdInput by remember { mutableStateOf("") }
    var isVerifyingTransaction by remember { mutableStateOf(false) }
    var showCakeyCelebration by remember { mutableStateOf(false) }

    val clipboardManager = LocalClipboardManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val deviceId = remember { ProDeviceId.get(context) }

    LaunchedEffect(initialKey) {
        if (initialKey.isNotBlank()) {
            keyInput = initialKey.trim()
            if (keyInput.startsWith("EXPR-PRO-") || keyInput.startsWith("EXPR-")) {
                val res = proManager.activate(keyInput)
                if (res.isFailure) {
                    errorMessage = res.exceptionOrNull()?.message ?: "Invalid license key"
                } else {
                    errorMessage = null
                    if (res.getOrNull()?.isCakey == true) {
                        showCakeyCelebration = true
                    }
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
                        statusMessage = null
                    },
                    onDismiss = onDismiss,
                )
            } else {
                // Tab Selection: Donate vs Offline Key
                PrimaryTabRow(
                    selectedTabIndex = selectedTab,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = {
                            selectedTab = 0
                            errorMessage = null
                            statusMessage = null
                        },
                        text = { Text(stringResource(R.string.expressive_pro_tab_donate)) },
                        icon = { Icon(Icons.Rounded.Favorite, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = {
                            selectedTab = 1
                            errorMessage = null
                            statusMessage = null
                        },
                        text = { Text(stringResource(R.string.expressive_pro_tab_offline)) },
                        icon = { Icon(Icons.Rounded.Key, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                }

                if (selectedTab == 0) {
                    // TAB 0: Donate & Activate ($4.99 via PayPal)
                    Text(
                        text = stringResource(R.string.expressive_pro_donation_instructions),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )

                    // Device ID Card
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 14.dp)
                            .clickable {
                                clipboardManager.setText(AnnotatedString(deviceId))
                                Toast
                                    .makeText(
                                        context,
                                        context.getString(R.string.expressive_pro_device_id_copied),
                                        Toast.LENGTH_SHORT,
                                    )
                                    .show()
                            },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.expressive_pro_device_id_label),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = deviceId,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = {
                                clipboardManager.setText(AnnotatedString(deviceId))
                                Toast
                                    .makeText(
                                        context,
                                        context.getString(R.string.expressive_pro_device_id_copied),
                                        Toast.LENGTH_SHORT,
                                    )
                                    .show()
                            }) {
                                Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy")
                            }
                        }
                    }

                    // Primary Button: Pay $4.99 with PayPal
                    Button(
                        onClick = {
                            val paypalUrl = Uri.parse(AboutDestinations.PAYPAL_PAYMENT_URL)
                                .buildUpon()
                                .appendQueryParameter("custom", deviceId)
                                .appendQueryParameter("invoice_id", deviceId)
                                .build()
                            context.startActivity(Intent(Intent.ACTION_VIEW, paypalUrl))
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp),
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Icon(Icons.Rounded.Favorite, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.expressive_pro_pay_button))
                    }

                    // Check Payment Status Button
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                isCheckingStatus = true
                                statusMessage = null
                                errorMessage = null
                                try {
                                    val service = ProActivationService.create()
                                    val response = service.checkLicense(deviceId)
                                    if (response.success && !response.key.isNullOrBlank()) {
                                        val actRes = proManager.activate(response.key)
                                        if (actRes.isFailure) {
                                            errorMessage = actRes.exceptionOrNull()?.message ?: "Activation failed"
                                        } else if (actRes.getOrNull()?.isCakey == true) {
                                            showCakeyCelebration = true
                                        }
                                    } else {
                                        statusMessage = context.getString(
                                            R.string.expressive_pro_payment_not_found,
                                            deviceId,
                                        )
                                    }
                                } catch (e: Exception) {
                                    statusMessage = e.localizedMessage
                                        ?: "Unable to reach activation service. Please check your network connection."
                                } finally {
                                    isCheckingStatus = false
                                }
                            }
                        },
                        enabled = !isCheckingStatus,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp),
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        if (isCheckingStatus) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.expressive_pro_checking_status))
                        } else {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.expressive_pro_check_status))
                        }
                    }

                    // Expandable Manual Transaction ID Section
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showTransactionInput = !showTransactionInput }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.expressive_pro_verify_transaction),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    AnimatedVisibility(visible = showTransactionInput) {
                        Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            OutlinedTextField(
                                value = transactionIdInput,
                                onValueChange = {
                                    transactionIdInput = it
                                    errorMessage = null
                                    statusMessage = null
                                },
                                label = { Text(stringResource(R.string.expressive_pro_transaction_id_label)) },
                                placeholder = { Text(stringResource(R.string.expressive_pro_transaction_id_hint)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    keyboardController?.hide()
                                    scope.launch {
                                        isVerifyingTransaction = true
                                        errorMessage = null
                                        statusMessage = null
                                        try {
                                            val service = ProActivationService.create()
                                            val req = VerifyDonationRequest(
                                                transactionId = transactionIdInput.trim(),
                                                deviceId = deviceId,
                                            )
                                            val response = service.verifyDonation(req)
                                            if (response.success && !response.key.isNullOrBlank()) {
                                                val actRes = proManager.activate(response.key)
                                                if (actRes.isFailure) {
                                                    errorMessage = actRes.exceptionOrNull()?.message ?: "Activation failed"
                                                } else if (actRes.getOrNull()?.isCakey == true) {
                                                    showCakeyCelebration = true
                                                }
                                            } else {
                                                errorMessage = response.message ?: "Transaction ID not verified"
                                            }
                                        } catch (e: Exception) {
                                            errorMessage = e.localizedMessage ?: "Verification error"
                                        } finally {
                                            isVerifyingTransaction = false
                                        }
                                    }
                                },
                                enabled = transactionIdInput.isNotBlank() && !isVerifyingTransaction,
                                modifier = Modifier.align(Alignment.End),
                                shapes = ButtonDefaults.shapes(),
                            ) {
                                if (isVerifyingTransaction) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.expressive_pro_verifying))
                                } else {
                                    Text(stringResource(R.string.expressive_pro_verify_button))
                                }
                            }
                        }
                    }
                } else {
                    // TAB 1: Offline Key Entry
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

                    Spacer(Modifier.height(16.dp))

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
                                    if (res.getOrNull()?.isCakey == true) {
                                        showCakeyCelebration = true
                                    }
                                }
                            },
                            enabled = keyInput.isNotBlank(),
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Text(stringResource(R.string.expressive_pro_activate_button))
                        }
                    }
                }

                // Error message banner
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

                // Informational status message banner
                AnimatedVisibility(visible = statusMessage != null) {
                    statusMessage?.let { status ->
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = status,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    if (showCakeyCelebration) {
        CakeyCelebrationDialog(
            onDismiss = { showCakeyCelebration = false },
        )
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
    val isCakey = details.isCakey
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (isCakey) MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
        else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isCakey) Icons.Rounded.Favorite else Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = if (isCakey) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (isCakey) stringResource(R.string.cakey_edition_title)
                    else stringResource(R.string.expressive_pro_license_details),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isCakey) MaterialTheme.colorScheme.onTertiaryContainer
                    else MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.height(10.dp))

            val recipientDisplay = when {
                details.isDeviceBound -> "This Device (${details.boundDeviceId})"
                details.isAccountBound -> "Account (${details.boundAccountEmail})"
                else -> details.recipient
            }

            Text(
                text = stringResource(R.string.expressive_pro_recipient, recipientDisplay),
                style = MaterialTheme.typography.bodyMedium,
                color = if (isCakey) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = stringResource(
                    R.string.expressive_pro_tier,
                    if (isCakey) "Cakey Edition 🍰 (The Best of the Best)" else details.type.displayName,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = if (isCakey) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
            )
            val expiryText = if (details.isLifetime) {
                if (isCakey) "Lifetime (Forever & Always ❤️)" else stringResource(R.string.expressive_pro_lifetime)
            } else {
                formatTimestamp(details.expiresAt)
            }
            Text(
                text = stringResource(R.string.expressive_pro_expires, expiryText),
                style = MaterialTheme.typography.bodyMedium,
                color = if (isCakey) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
            )

            if (isCakey) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.cakey_edition_banner_desc),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
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
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(R.string.expressive_pro_deactivate_button))
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

private fun formatTimestamp(seconds: Long): String {
    if (seconds <= 0L) return "Lifetime"
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    sdf.timeZone = TimeZone.getDefault()
    return sdf.format(Date(seconds * 1000L))
}
