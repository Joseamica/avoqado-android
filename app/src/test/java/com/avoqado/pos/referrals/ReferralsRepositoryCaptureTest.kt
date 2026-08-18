package com.avoqado.pos.referrals

import com.avoqado.pos.referrals.data.api.ReferralsApiService
import com.avoqado.pos.referrals.data.dto.ReferralCaptureRequest
import com.avoqado.pos.referrals.data.dto.ReferralCaptureResponse
import com.avoqado.pos.referrals.data.repository.ReferralsRepositoryImpl
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response

/**
 * The `intendedOrderId` is the only thing that ties a captured referral to
 * the sale that must qualify it: without it the backend row is born PENDING
 * and can NEVER reach QUALIFIED (`onOrderPaid` claims referrals by order), so
 * the referrer never gets their reward. It is also what activates the
 * server's partial unique index, which is what stops a by-product split from
 * capturing the SAME sale once per part.
 *
 * `CartViewModelReferralTest` already proves the ViewModel hands the id to
 * the use case; this closes the last leg — that the repository actually puts
 * it on the wire.
 */
class ReferralsRepositoryCaptureTest {

    private val api = mockk<ReferralsApiService>()
    private val repository = ReferralsRepositoryImpl(api, Json { ignoreUnknownKeys = true })

    @Test
    fun `capture puts the real orderId of the paid sale on the request body`() = runTest {
        val body = slot<ReferralCaptureRequest>()
        coEvery { api.capture(venueId = any(), request = capture(body)) } returns
            Response.success(ReferralCaptureResponse(id = "ref_1"))

        val result = repository.capture(
            venueId = "venue_1",
            referralCode = "ana-2026",
            newCustomerId = "cust_1",
            capturedByStaffVenueId = "sv_1",
            intendedOrderId = "cmrb9c0000abcdefghijklmno",
        )

        assertEquals("ref_1", result.getOrNull())
        assertEquals("cmrb9c0000abcdefghijklmno", body.captured.intendedOrderId)
        // Canonical form is the repository's job (the backend matches codes
        // exactly), so assert it here rather than trusting each caller.
        assertEquals("ANA-2026", body.captured.referralCode)
    }

    @Test
    fun `capture without an order sends null instead of inventing one`() = runTest {
        val body = slot<ReferralCaptureRequest>()
        coEvery { api.capture(venueId = any(), request = capture(body)) } returns
            Response.success(ReferralCaptureResponse(id = "ref_2"))

        repository.capture(
            venueId = "venue_1",
            referralCode = "ANA-2026",
            newCustomerId = "cust_1",
            capturedByStaffVenueId = "sv_1",
            intendedOrderId = null,
        )

        assertNull(body.captured.intendedOrderId)
    }
}
