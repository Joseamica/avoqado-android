package com.avoqado.pos.referrals.domain.repository

import com.avoqado.pos.referrals.domain.model.ValidationResult

/**
 * Domain-side gateway to the referrals capture endpoints (Plan 5B).
 *
 * Uses Kotlin stdlib [kotlin.Result] (same pattern as
 * [com.avoqado.pos.customers.data.CustomersRepository] and the coupon
 * validation path in [com.avoqado.pos.pos.data.DiscountsRepository]).
 */
interface ReferralsRepository {

    /**
     * No-side-effect validation of [referralCode] for [newCustomerId] in
     * [venueId]. Network/HTTP failures surface as [Result.failure]; backend
     * rejections surface as `Result.success(ValidationResult.Invalid)`.
     */
    suspend fun validate(
        venueId: String,
        referralCode: String,
        newCustomerId: String,
    ): Result<ValidationResult>

    /**
     * Persists a PENDING Referral row tying [newCustomerId] to the referrer
     * who owns [referralCode]. [intendedOrderId] is optional — pass the
     * order id if known so the qualifying-order webhook can flip the status
     * without a separate lookup.
     *
     * The backend re-runs validation. Validation failures arrive as
     * [Result.failure] wrapping [ReferralValidationException] so callers can
     * `when (reason)` on the strongly-typed enum.
     */
    suspend fun capture(
        venueId: String,
        referralCode: String,
        newCustomerId: String,
        capturedByStaffVenueId: String,
        intendedOrderId: String? = null,
    ): Result<String>

    /**
     * Manager-authorized override for the EXISTING_CUSTOMER reject case.
     * Requires `referral:override-existing-customer` server-side.
     *
     * The UI surfaces this as a "Forzar atribución" affordance on the
     * EXISTING_CUSTOMER InvalidBanner; v1 keeps it inactive (Plan 5B does
     * not expose the manager-PIN dialog yet).
     */
    suspend fun forceOverride(
        venueId: String,
        referralCode: String,
        existingCustomerId: String,
        capturedByStaffVenueId: String,
        reason: String,
    ): Result<String>
}

/**
 * Thrown by [ReferralsRepository.capture] / [ReferralsRepository.forceOverride]
 * when the backend rejects the request with a structured validation reason.
 * Lets callers do `when (reason)` on the strongly-typed enum instead of
 * parsing message strings.
 */
class ReferralValidationException(
    val reason: ValidationResult.Reason,
) : RuntimeException("Referral validation rejected: $reason")
