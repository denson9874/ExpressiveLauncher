package app.lawnchair.ui.preferences.components

import android.app.KeyguardManager
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Shows [content] only after the user confirms their fingerprint, face or screen lock. Phones without a
 * secure lock screen show it straight away. Cancelling or failing the prompt calls [onCancel].
 *
 * The unlock lasts while this screen is composed: leaving and coming back asks again.
 */
@Composable
fun DeviceCredentialGate(
    title: String,
    onCancel: () -> Unit,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val secure = remember { context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true }
    var unlocked by remember { mutableStateOf(false) }
    if (!secure || unlocked) {
        content()
        return
    }
    val currentOnCancel by rememberUpdatedState(onCancel)
    DisposableEffect(Unit) {
        val cancellation = CancellationSignal()
        BiometricPrompt.Builder(context)
            .setTitle(title)
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()
            .authenticate(
                cancellation,
                context.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        unlocked = true
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        // Disposing the effect after a success cancels the finished prompt too.
                        if (!unlocked) currentOnCancel()
                    }
                },
            )
        onDispose { cancellation.cancel() }
    }
}
