package com.avoqado.pos.referrals.domain.model

/**
 * Outcome of `POST /dashboard/venues/{venueId}/referrals/validate`.
 *
 * Sealed so callers can `when`-exhaust the rejection reasons. The backend's
 * `ValidationReason` enum is mirrored here verbatim — UI maps each reason
 * to Spanish copy + CTA (e.g., EXISTING_CUSTOMER triggers the manager-
 * override flow).
 *
 * See: avoqado-server/src/services/referrals/referralCapture.service.ts
 */
sealed class ValidationResult {

    /**
     * Code is valid. UI shows referrer name + discount preview, and the
     * cart applies `discountPercent` against the gross subtotal.
     */
    data class Valid(
        val referrerName: String,
        val discountPercent: Int,
        val referrerCustomerId: String,
    ) : ValidationResult()

    /**
     * Code was rejected for [reason]. UI shows the Spanish copy mapped from
     * the enum and surfaces the override CTA when applicable.
     */
    data class Invalid(val reason: Reason) : ValidationResult()

    enum class Reason {
        /** Venue does not have an active referral program. */
        PROGRAM_INACTIVE,

        /** Code did not match any customer in the venue. */
        CODE_NOT_FOUND,

        /** The new customer is the same as the referrer (self-referral). */
        SELF_REFERRAL,

        /** The customer already has prior orders in this venue. */
        EXISTING_CUSTOMER,

        /** Unhandled backend error or unrecognized reason string. */
        UNKNOWN,
    }
}
