package com.avoqado.pos.referrals.data.repository

import android.util.Log
import com.avoqado.pos.referrals.data.api.ReferralsApiService
import com.avoqado.pos.referrals.data.dto.ReferralCaptureRequest
import com.avoqado.pos.referrals.data.dto.ReferralForceOverrideRequest
import com.avoqado.pos.referrals.data.dto.ReferralValidateRequest
import com.avoqado.pos.referrals.data.dto.ReferralValidateResponse
import com.avoqado.pos.referrals.domain.model.ValidationResult
import com.avoqado.pos.referrals.domain.repository.ReferralValidationException
import com.avoqado.pos.referrals.domain.repository.ReferralsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [ReferralsRepository] backed by Retrofit. Maps HTTP responses to
 * the strongly-typed [ValidationResult] sealed class and surfaces structured
 * 400-validation errors as [ReferralValidationException].
 */
@Singleton
class ReferralsRepositoryImpl @Inject constructor(
    private val api: ReferralsApiService,
    private val json: Json,
) : ReferralsRepository {

    override suspend fun validate(
        venueId: String,
        referralCode: String,
        newCustomerId: String,
    ): Result<ValidationResult> = withContext(Dispatchers.IO) {
        runCatching {
            Log.d(TAG, "validate code=$referralCode customer=$newCustomerId venue=$venueId")
            val response = api.validate(
                venueId = venueId,
                request = ReferralValidateRequest(
                    referralCode = referralCode.trim().uppercase(),
                    newCustomerId = newCustomerId,
                ),
            )
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "HTTP ${response.code()} validating referral code",
                )
            }
            val body = response.body()
                ?: throw IllegalStateException("Empty validate response body")
            body.toDomain()
        }.onFailure { e ->
            Log.e(TAG, "validate failed", e)
        }
    }

    override suspend fun capture(
        venueId: String,
        referralCode: String,
        newCustomerId: String,
        capturedByStaffVenueId: String,
        intendedOrderId: String?,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            Log.d(TAG, "capture code=$referralCode customer=$newCustomerId order=$intendedOrderId")
            val response = api.capture(
                venueId = venueId,
                request = ReferralCaptureRequest(
                    referralCode = referralCode.trim().uppercase(),
                    newCustomerId = newCustomerId,
                    capturedByStaffVenueId = capturedByStaffVenueId,
                    intendedOrderId = intendedOrderId,
                ),
            )
            if (response.code() == 400) {
                throw parseValidationException(response.errorBody()?.string())
            }
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "HTTP ${response.code()} capturing referral",
                )
            }
            response.body()?.id
                ?: throw IllegalStateException("Empty capture response body")
        }.onFailure { e ->
            Log.e(TAG, "capture failed", e)
        }
    }

    override suspend fun forceOverride(
        venueId: String,
        referralCode: String,
        existingCustomerId: String,
        capturedByStaffVenueId: String,
        reason: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            Log.d(TAG, "forceOverride code=$referralCode customer=$existingCustomerId")
            val response = api.forceOverride(
                venueId = venueId,
                request = ReferralForceOverrideRequest(
                    referralCode = referralCode.trim().uppercase(),
                    existingCustomerId = existingCustomerId,
                    capturedByStaffVenueId = capturedByStaffVenueId,
                    reason = reason,
                ),
            )
            if (response.code() == 400) {
                throw parseValidationException(response.errorBody()?.string())
            }
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "HTTP ${response.code()} forcing referral override",
                )
            }
            response.body()?.id
                ?: throw IllegalStateException("Empty force-override response body")
        }.onFailure { e ->
            Log.e(TAG, "forceOverride failed", e)
        }
    }

    /**
     * Backend returns `{ valid:false, reason:<ValidationReason> }` on 400.
     * Parse it into a [ReferralValidationException] so callers can switch
     * on the enum.
     */
    private fun parseValidationException(errorBody: String?): ReferralValidationException {
        val reason = errorBody?.let { raw ->
            runCatching { json.decodeFromString(ReferralValidateResponse.serializer(), raw) }
                .getOrNull()
                ?.reason
                ?.toReason()
        } ?: ValidationResult.Reason.UNKNOWN
        return ReferralValidationException(reason)
    }

    companion object {
        private const val TAG = "🎁 Referrals"
    }
}

private fun ReferralValidateResponse.toDomain(): ValidationResult {
    if (valid) {
        val name = listOfNotNull(referrer?.firstName, referrer?.lastName)
            .joinToString(" ")
            .trim()
            .ifEmpty { "Cliente" }
        return ValidationResult.Valid(
            referrerName = name,
            discountPercent = discountPercent ?: 0,
            referrerCustomerId = referrer?.id.orEmpty(),
        )
    }
    return ValidationResult.Invalid(reason.toReason())
}

private fun String?.toReason(): ValidationResult.Reason = when (this) {
    "PROGRAM_INACTIVE" -> ValidationResult.Reason.PROGRAM_INACTIVE
    "CODE_NOT_FOUND" -> ValidationResult.Reason.CODE_NOT_FOUND
    "SELF_REFERRAL" -> ValidationResult.Reason.SELF_REFERRAL
    "EXISTING_CUSTOMER" -> ValidationResult.Reason.EXISTING_CUSTOMER
    else -> ValidationResult.Reason.UNKNOWN
}
