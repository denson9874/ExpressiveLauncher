package app.lawnchair.pro

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import app.lawnchair.util.MainThreadInitializedObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

import com.android.launcher3.R

class ProManager(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _isPro = MutableStateFlow(false)
    val isPro: StateFlow<Boolean> = _isPro.asStateFlow()

    private val _licenseDetails = MutableStateFlow<ProLicenseDetails?>(null)
    val licenseDetails: StateFlow<ProLicenseDetails?> = _licenseDetails.asStateFlow()

    init {
        refreshState()
    }

    var lastError: Throwable? = null
        private set

    /**
     * Re-verify saved license key against public key, expiration clock, and device identity.
     */
    fun refreshState() {
        val savedKey = prefs.getString(KEY_LICENSE_CODE, null)
        if (savedKey.isNullOrBlank()) {
            _isPro.value = false
            _licenseDetails.value = null
            return
        }

        val result = ProLicenseVerifier.verify(savedKey).mapCatching { details ->
            verifyDeviceBinding(details).getOrThrow()
            details
        }
        result.onSuccess { details ->
            lastError = null
            _isPro.value = true
            _licenseDetails.value = details
        }.onFailure {
            lastError = it
            _isPro.value = false
            _licenseDetails.value = null
        }
    }

    /**
     * Validate and activate a new license key string.
     */
    fun activate(rawKey: String): Result<ProLicenseDetails> {
        val result = ProLicenseVerifier.verify(rawKey).mapCatching { details ->
            verifyDeviceBinding(details).getOrThrow()
            prefs.edit().putString(KEY_LICENSE_CODE, details.rawKeyCode).commit()
            _isPro.value = true
            _licenseDetails.value = details
            details
        }.onFailure {
            lastError = it
        }
        return result
    }

    private fun verifyDeviceBinding(details: ProLicenseDetails): Result<Unit> {
        if (details.isDeviceBound) {
            val currentDeviceId = ProDeviceId.get(context)
            val bound = details.boundDeviceId
            if (!bound.equals(currentDeviceId, ignoreCase = true)) {
                return Result.failure(
                    IllegalStateException(
                        context.getString(
                            R.string.expressive_pro_device_mismatch,
                            bound ?: "unknown",
                            currentDeviceId,
                        ),
                    ),
                )
            }
        }
        return Result.success(Unit)
    }

    /**
     * Deactivate Pro license and revert to Free Core.
     */
    fun deactivate() {
        prefs.edit().remove(KEY_LICENSE_CODE).commit()
        _isPro.value = false
        _licenseDetails.value = null
    }

    fun getSavedKey(): String? = prefs.getString(KEY_LICENSE_CODE, null)

    companion object {
        private const val PREFS_NAME = "expressive_pro_license"
        private const val KEY_LICENSE_CODE = "active_license_key"

        @JvmField
        var INSTANCE = MainThreadInitializedObject(::ProManager)

        fun resetForTests() {
            INSTANCE = MainThreadInitializedObject(::ProManager)
        }
    }
}

@Composable
fun proManager(): ProManager = ProManager.INSTANCE.get(LocalContext.current)
