package app.lawnchair.pro

import android.content.Context
import android.os.Handler
import android.os.Looper
import retrofit2.HttpException
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import app.lawnchair.util.MainThreadInitializedObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

import com.android.launcher3.R

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

class ProManager(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _isPro = MutableStateFlow(false)
    val isPro: StateFlow<Boolean> = _isPro.asStateFlow()

    private val _isCakey = MutableStateFlow(false)
    val isCakey: StateFlow<Boolean> = _isCakey.asStateFlow()

    private val _licenseDetails = MutableStateFlow<ProLicenseDetails?>(null)
    val licenseDetails: StateFlow<ProLicenseDetails?> = _licenseDetails.asStateFlow()

    /** True once a trial has ended until the user dismisses the notice. */
    private val _trialEndedNotice = MutableStateFlow(prefs.getBoolean(KEY_TRIAL_ENDED_NOTICE, false))
    val trialEndedNotice: StateFlow<Boolean> = _trialEndedNotice.asStateFlow()

    private val expiryHandler = Handler(Looper.getMainLooper())
    private val expiryCheck = Runnable { refreshState() }

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
            _isCakey.value = false
            _licenseDetails.value = null
            return
        }

        val nowSeconds = nowSecondsFor(savedKey)
        val result = ProLicenseVerifier.verify(savedKey, nowSeconds).mapCatching { details ->
            verifyDeviceBinding(details).getOrThrow()
            details
        }
        result.onSuccess { details ->
            lastError = null
            _isPro.value = true
            _licenseDetails.value = details
            _isCakey.value = details.isCakey
            scheduleExpiryCheck(details, nowSeconds)
        }.onFailure {
            lastError = it
            val wasTrial = _licenseDetails.value?.isTrial == true || isSavedTrialKey(savedKey)
            _isPro.value = false
            _isCakey.value = false
            _licenseDetails.value = null
            expiryHandler.removeCallbacks(expiryCheck)
            if (wasTrial) {
                // A finished trial returns to Free Core; settings stay saved, just inactive.
                prefs.edit().remove(KEY_LICENSE_CODE).putBoolean(KEY_TRIAL_ENDED_NOTICE, true).commit()
                _trialEndedNotice.value = true
            }
        }
    }

    /**
     * Seconds used to judge expiry: a trial uses [trustedNowSeconds] so setting the clock back
     * can't stretch it; other licenses keep the plain wall clock they have always used.
     */
    private fun nowSecondsFor(key: String): Long {
        val trusted = trustedNowSeconds()
        return if (isSavedTrialKey(key)) trusted else clock() / 1000L
    }

    /**
     * A clock that never runs backwards: the later of the wall clock and the last trusted time plus
     * the real time elapsed since then (elapsedRealtime survives clock changes, until a reboot).
     */
    private fun trustedNowSeconds(): Long {
        val wall = clock() / 1000L
        val elapsed = elapsedClock() / 1000L
        val lastTrusted = prefs.getLong(KEY_TRUSTED_SECONDS, 0L)
        val lastElapsed = prefs.getLong(KEY_TRUSTED_ELAPSED_SECONDS, -1L)
        val sinceLast = if (lastElapsed in 0..elapsed) elapsed - lastElapsed else 0L
        val trusted = maxOf(wall, lastTrusted + sinceLast)
        prefs.edit()
            .putLong(KEY_TRUSTED_SECONDS, trusted)
            .putLong(KEY_TRUSTED_ELAPSED_SECONDS, elapsed)
            .apply()
        return trusted
    }

    private fun scheduleExpiryCheck(details: ProLicenseDetails, nowSeconds: Long) {
        expiryHandler.removeCallbacks(expiryCheck)
        if (details.isLifetime) return
        val delayMs = ((details.expiresAt - nowSeconds + 1).coerceAtLeast(1)) * 1000L
        expiryHandler.postDelayed(expiryCheck, delayMs.coerceAtMost(MAX_EXPIRY_CHECK_DELAY_MS))
    }

    private fun isSavedTrialKey(key: String): Boolean =
        ProLicenseVerifier.verify(key, nowSeconds = 0L).getOrNull()?.isTrial == true

    /** Whether this install already started its trial (the server enforces one per device too). */
    val hasUsedTrial: Boolean get() = prefs.getBoolean(KEY_TRIAL_STARTED, false)

    fun dismissTrialEndedNotice() {
        prefs.edit().putBoolean(KEY_TRIAL_ENDED_NOTICE, false).apply()
        _trialEndedNotice.value = false
    }

    /** Requests the device's 7-day trial key from the activation service and activates it. */
    suspend fun startTrial(service: ProActivationService = ProActivationService.create()): Result<ProLicenseDetails> =
        runCatching {
            val response = try {
                service.startTrial(
                    TrialRequest(ProDeviceId.get(context), ProTrialFingerprint.compute(context)),
                )
            } catch (e: HttpException) {
                if (e.code() == 409) {
                    prefs.edit().putBoolean(KEY_TRIAL_STARTED, true).apply()
                    throw IllegalStateException(context.getString(R.string.expressive_pro_trial_used))
                }
                throw IllegalStateException(context.getString(R.string.expressive_pro_trial_unavailable), e)
            } catch (e: java.io.IOException) {
                throw IllegalStateException(context.getString(R.string.expressive_pro_trial_unavailable), e)
            }
            val key = response.key?.takeIf { response.success }
                ?: throw IllegalStateException(context.getString(R.string.expressive_pro_trial_unavailable))
            val details = activate(key).getOrThrow()
            check(details.isTrial) { "Activation service returned a non-trial key" }
            prefs.edit().putBoolean(KEY_TRIAL_STARTED, true).apply()
            details
        }

    /**
     * Validate and activate a new license key string.
     */
    fun activate(rawKey: String): Result<ProLicenseDetails> {
        val nowSeconds = nowSecondsFor(rawKey)
        val result = ProLicenseVerifier.verify(rawKey, nowSeconds).mapCatching { details ->
            verifyDeviceBinding(details).getOrThrow()
            prefs.edit().putString(KEY_LICENSE_CODE, details.rawKeyCode).commit()
            _isPro.value = true
            _licenseDetails.value = details
            _isCakey.value = details.isCakey
            scheduleExpiryCheck(details, nowSeconds)
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
        expiryHandler.removeCallbacks(expiryCheck)
        prefs.edit().remove(KEY_LICENSE_CODE).commit()
        _isPro.value = false
        _isCakey.value = false
        _licenseDetails.value = null
    }

    fun getSavedKey(): String? = prefs.getString(KEY_LICENSE_CODE, null)

    companion object {
        private const val PREFS_NAME = "expressive_pro_license"
        private const val KEY_LICENSE_CODE = "active_license_key"
        private const val KEY_TRUSTED_SECONDS = "trusted_time_seconds"
        private const val KEY_TRUSTED_ELAPSED_SECONDS = "trusted_elapsed_seconds"
        private const val KEY_TRIAL_STARTED = "trial_started"
        private const val KEY_TRIAL_ENDED_NOTICE = "trial_ended_notice"
        private const val MAX_EXPIRY_CHECK_DELAY_MS = 6L * 60 * 60 * 1000

        /** Wall clock in milliseconds; replaced by unit tests. */
        @androidx.annotation.VisibleForTesting
        @JvmField
        var clock: () -> Long = { System.currentTimeMillis() }

        /** Monotonic time since boot in milliseconds; replaced by unit tests. */
        @androidx.annotation.VisibleForTesting
        @JvmField
        var elapsedClock: () -> Long = { android.os.SystemClock.elapsedRealtime() }

        @JvmField
        var INSTANCE = MainThreadInitializedObject(::ProManager)

        fun resetForTests() {
            clock = { System.currentTimeMillis() }
            elapsedClock = { android.os.SystemClock.elapsedRealtime() }
            INSTANCE = MainThreadInitializedObject(::ProManager)
        }
    }
}

@Composable
fun proManager(): ProManager = ProManager.INSTANCE.get(LocalContext.current)

@Composable
fun rememberIsCakey(): Boolean {
    val proManager = proManager()
    val isCakey by proManager.isCakey.collectAsState()
    return isCakey
}
