package app.lawnchair.pro

import app.lawnchair.util.kotlinxJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.create
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

interface ProActivationService {

    @GET("api/license")
    suspend fun checkLicense(
        @Query("device_id") deviceId: String,
    ): LicenseResponse

    @POST("api/verify-donation")
    suspend fun verifyDonation(
        @Body request: VerifyDonationRequest,
    ): LicenseResponse

    companion object {
        const val DEFAULT_ACTIVATION_URL = "https://expressive-pro-activation.daryldenson0405.workers.dev/"

        fun create(baseUrl: String = DEFAULT_ACTIVATION_URL): ProActivationService {
            val okHttpClient = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

            val normalizedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

            return Retrofit.Builder()
                .client(okHttpClient)
                .baseUrl(normalizedUrl)
                .addConverterFactory(kotlinxJson.asConverterFactory("application/json".toMediaType()))
                .build()
                .create()
        }
    }
}

@Serializable
data class VerifyDonationRequest(
    @SerialName("transaction_id") val transactionId: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("email") val email: String? = null,
)

@Serializable
data class LicenseResponse(
    @SerialName("success") val success: Boolean,
    @SerialName("key") val key: String? = null,
    @SerialName("message") val message: String? = null,
    @SerialName("recipient") val recipient: String? = null,
)
