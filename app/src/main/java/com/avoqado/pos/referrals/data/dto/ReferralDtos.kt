package com.avoqado.pos.referrals.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTOs for the C2C referrals capture flow (Plan 5B — Android POS Referral Capture).
 *
 * Mirrors the TPV implementation (Plan 5A) so the backend contract is shared.
 * Endpoints (all venue-scoped, `/api/v1/` prefix added by Retrofit base URL):
 * - POST /dashboard/venues/{venueId}/referrals/validate
 * - POST /dashboard/venues/{venueId}/referrals/capture
 * - POST /dashboard/venues/{venueId}/referrals/force-override
 *
 * Auth is Bearer token (added by [com.avoqado.pos.core.data.network.AuthInterceptor]).
 *
 * Both validate + capture require `newCustomerId`: the backend evaluates the
 * EXISTING_CUSTOMER rule against this customer's prior orders in the same
 * venue, so the POS must have a customer selected on the cart before the
 * cashier can validate a referral code.
 *
 * See: avoqado-server/src/schemas/dashboard/referrals.schemas.ts
 */
@Serializable
data class ReferralValidateRequest(
    val referralCode: String,
    val newCustomerId: String,
)

/**
 * Mirrors `ValidationResult` in
 * avoqado-server/src/services/referrals/referralCapture.service.ts.
 *
 * Success:
 * ```json
 * {
 *   "valid": true,
 *   "referrer": { "id": "cust_xxx", "firstName": "Ana", "lastName": "López" },
 *   "discountPercent": 10
 * }
 * ```
 *
 * Rejection:
 * ```json
 * { "valid": false, "reason": "EXISTING_CUSTOMER" }
 * ```
 */
@Serializable
data class ReferralValidateResponse(
    val valid: Boolean,
    val reason: String? = null,
    val referrer: ReferrerDto? = null,
    val discountPercent: Int? = null,
)

@Serializable
data class ReferrerDto(
    val id: String,
    val firstName: String? = null,
    val lastName: String? = null,
)

@Serializable
data class ReferralCaptureRequest(
    val referralCode: String,
    val newCustomerId: String,
    val capturedByStaffVenueId: String,
    val intendedOrderId: String? = null,
)

/**
 * Backend returns a Prisma `Referral` row on 201. POS only needs the id
 * here, but the rest of the columns are forward-compatibly modeled.
 */
@Serializable
data class ReferralCaptureResponse(
    val id: String,
    val status: String? = null,
    val referrerCustomerId: String? = null,
    val referredCustomerId: String? = null,
)

@Serializable
data class ReferralForceOverrideRequest(
    val referralCode: String,
    val existingCustomerId: String,
    val capturedByStaffVenueId: String,
    val reason: String,
)
