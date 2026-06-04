package com.avoqado.pos.referrals.domain.usecase

import com.avoqado.pos.referrals.domain.model.ValidationResult
import com.avoqado.pos.referrals.domain.repository.ReferralsRepository
import javax.inject.Inject

/**
 * Validates a referral code at the checkout step. No state is persisted —
 * the cashier can re-try without side effects until they're ready to charge.
 */
class ValidateReferralUseCase @Inject constructor(
    private val repository: ReferralsRepository,
) {
    suspend operator fun invoke(
        venueId: String,
        referralCode: String,
        newCustomerId: String,
    ): Result<ValidationResult> = repository.validate(
        venueId = venueId,
        referralCode = referralCode,
        newCustomerId = newCustomerId,
    )
}
