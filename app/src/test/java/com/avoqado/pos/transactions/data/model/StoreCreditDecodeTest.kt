package com.avoqado.pos.transactions.data.model

import com.avoqado.pos.customers.data.model.CustomersResponse
import com.avoqado.pos.tpvsettings.data.VenueSettingsData
import com.avoqado.pos.transactions.data.AssociatedRefundData
import com.avoqado.pos.transactions.data.ExchangeResult
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/** Saldo a favor, A1: todo campo nuevo es opcional; una respuesta de un servidor viejo decodifica igual que hoy. */
class StoreCreditDecodeTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val abono = """{"customerId":"c1","customerName":"Ana Pérez","amountCents":15050,"newBalanceCents":20000,"phoneLast4":"1234","phoneMismatch":true}"""

    private fun assertAbono(a: StoreCreditAbono?) {
        assertEquals(StoreCreditAbono("c1", "Ana Pérez", 15050L, 20000L, "1234", true), a)
    }

    @Test fun `refund response with storeCredit decodes cents and recipient`() {
        val data = json.decodeFromString<AssociatedRefundData>(
            """{"refundId":"r","amount":150.5,"remainingRefundable":0,"status":"COMPLETED","storeCredit":$abono}""")
        assertAbono(data.storeCredit)
    }

    @Test fun `old refund response has no storeCredit and null or garbage never breaks it`() {
        assertNull(json.decodeFromString<AssociatedRefundData>("""{"refundId":"r","amount":1,"remainingRefundable":0,"status":"COMPLETED"}""").storeCredit)
        assertNull(json.decodeFromString<AssociatedRefundData>("""{"refundId":"r","amount":1,"remainingRefundable":0,"status":"COMPLETED","storeCredit":null}""").storeCredit)
        val bad = json.decodeFromString<AssociatedRefundData>("""{"refundId":"r","amount":1,"remainingRefundable":0,"status":"COMPLETED","storeCredit":"x"}""")
        assertEquals("r", bad.refundId)
        assertNull(bad.storeCredit)
    }

    private fun exchange(settlementExtra: String) = """{"id":"e","status":"COMPLETED","originalOrderId":"o","originalOrderNumber":"1",
      "newOrderId":"n","newOrderNumber":"2","returnedCents":100,"takenCents":50,"differenceCents":-50,
      "settlement":{"kind":"PAYOUT","method":"STORE_CREDIT","amountCents":50,"sources":[]$settlementExtra},
      "lines":{"returned":[],"taken":[]}}"""

    @Test fun `exchange with STORE_CREDIT settlement carries storeCredit`() {
        val r = json.decodeFromString<ExchangeResult>(exchange(""","storeCredit":$abono"""))
        assertEquals("STORE_CREDIT", r.settlement.method)
        assertAbono(r.settlement.storeCredit)
    }

    @Test fun `exchange without storeCredit still decodes and garbage keeps the exchange result`() {
        assertNull(json.decodeFromString<ExchangeResult>(exchange("")).settlement.storeCredit)
        val bad = json.decodeFromString<ExchangeResult>(exchange(""","storeCredit":{"amountCents":"mucho"}"""))
        assertEquals("e", bad.id)
        assertNull(bad.settlement.storeCredit)
    }

    @Test fun `transaction detail decodes storeCreditOption available and unavailable`() {
        val ok = json.decodeFromString<Transaction>("""{"id":"t","storeCreditOption":{"available":true}}""")
        assertEquals(StoreCreditOption(available = true), ok.storeCreditOption)
        val no = json.decodeFromString<Transaction>("""{"id":"t","storeCreditOption":{"available":false,"reason":"Pide a Avoqado que active el saldo a favor."}}""")
        assertEquals(false, no.storeCreditOption?.available)
        assertEquals("Pide a Avoqado que active el saldo a favor.", no.storeCreditOption?.reason)
    }

    @Test fun `refund row for reprint carries recipient name and amount only`() {
        val t = json.decodeFromString<Transaction>("""{"id":"t","storeCredit":{"customerName":"Ana Pérez","amountCents":15050}}""")
        assertEquals(StoreCreditAbono(customerName = "Ana Pérez", amountCents = 15050L), t.storeCredit)
    }

    @Test fun `old transaction has no store credit fields and a malformed one does not lose the sale`() {
        val old = json.decodeFromString<Transaction>("""{"id":"t","amount":500}""")
        assertNull(old.storeCreditOption)
        assertNull(old.storeCredit)
        val bad = json.decodeFromString<Transaction>("""{"id":"t","amount":500,"storeCreditOption":7,"storeCredit":[1],"tenderSystemKind":"STORE_CREDIT"}""")
        assertEquals(500.0, bad.amount, 0.0)
        assertNull(bad.storeCreditOption)
        assertNull(bad.storeCredit)
        assertEquals("STORE_CREDIT", bad.tenderSystemKind)
    }

    @Test fun `payout options drop STORE_CREDIT until A2 gives it a screen, and any unknown method`() {
        val t = json.decodeFromString<Transaction>("""{"id":"t","payoutOptions":[
          {"method":"STORE_CREDIT","maxCents":1000000,"maxSameCents":0,"needsRefundToCash":false},
          {"method":"FUTURE_THING","maxCents":1,"maxSameCents":1,"needsRefundToCash":false}]}""")
        assertEquals(emptyList<String?>(), t.payoutOptions?.map { it.method })
    }

    @Test fun `customers list decodes storeCredit active blocked null and absent`() {
        val r = json.decodeFromString<CustomersResponse>("""{"data":[
          {"id":"a","storeCredit":{"balanceCents":15050,"phoneLast4":"1234","giftCardId":"g1"}},
          {"id":"b","storeCredit":{"balanceCents":0,"phoneLast4":"9999","giftCardId":"g2","blocked":true}},
          {"id":"c","storeCredit":null},
          {"id":"d"},
          {"id":"e","storeCredit":"roto"}]}""")
        assertEquals(CustomerStoreCredit(15050L, "1234", "g1", null), r.data[0].storeCredit)
        assertEquals(CustomerStoreCredit(0L, "9999", "g2", true), r.data[1].storeCredit)
        assertNull(r.data[2].storeCredit)
        assertNull(r.data[3].storeCredit)
        assertEquals("e", r.data[4].id)
        assertNull(r.data[4].storeCredit)
    }

    @Test fun `tpv settings decode storeCreditOnly and storeCreditAvailable and default to null`() {
        val on = json.decodeFromString<VenueSettingsData>("""{"storeCreditOnly":true,"storeCreditAvailable":true}""")
        assertEquals(true, on.storeCreditOnly)
        assertEquals(true, on.storeCreditAvailable)
        val old = json.decodeFromString<VenueSettingsData>("""{"managerPinOverrideEnabled":false}""")
        assertNull(old.storeCreditOnly)
        assertNull(old.storeCreditAvailable)
        val bad = json.decodeFromString<VenueSettingsData>("""{"storeCreditOnly":"si","storeCreditAvailable":3,"exchangesEnabled":true}""")
        assertNull(bad.storeCreditOnly)
        assertNull(bad.storeCreditAvailable)
        assertEquals(true, bad.exchangesEnabled)
    }
}
