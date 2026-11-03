package com.avoqado.pos.printing.receiptlayout

import com.avoqado.pos.transactions.data.jsonReembolsos
import com.avoqado.pos.transactions.data.ExchangeResult
import com.avoqado.pos.transactions.presentation.exchange.exchangeReceipt
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Date

class ReceiptExchangeGoldenTest {
    @Test fun `exchange paper receipt matches shared golden and never contains digital receipt`() {
        val fixture=Json.parseToJsonElement(javaClass.getResource("/exchange/receipt-exchange.json")!!.readText()).jsonObject
        val result=jsonReembolsos.decodeFromJsonElement(ExchangeResult.serializer(),fixture["exchange"]!!)
        val receipt=exchangeReceipt(result,"IQ","Ana",Date(0))
        assertEquals("EXCHANGE",receipt.kind); assertNull(receipt.receiptUrl)
        val input=ReceiptInputMapper.map(receipt,null,false,"America/Mexico_City",null)
        assertEquals("EXCHANGE",input.sale.kind)
        val logical=ReceiptLayoutInterpreter.interpret(listOf(ReceiptBlock.Qr()),input.copy(sale=input.sale.copy(receiptUrl="https://must-not-share.example")),32)
        assertFalse(logical.any { it is LogicalLine.Qr })
        assertEquals(LogicalLine.Text("CAMBIO",Align.CENTER,true,false),logical.first())
        val lines=logical.filterIsInstance<LogicalLine.Text>().map { it.text }
        assertEquals(fixture["expectedText32"]!!.jsonArray.map { it.jsonPrimitive.content },lines)
        assertEquals(-1000L,input.sale.exchange!!.returned.single().totalCents)
    }
    @Test fun `el ticket de un cambio a saldo a favor dice a quién se abonó y con qué teléfono se usa`() {
        val case=Json.parseToJsonElement(javaClass.getResource("/exchange/receipt-exchange.json")!!.readText()).jsonObject.getValue("storeCredit").jsonObject
        val result=jsonReembolsos.decodeFromJsonElement(ExchangeResult.serializer(),case.getValue("exchange"))
        val input=ReceiptInputMapper.map(exchangeReceipt(result,"IQ","Ana",Date(0)),null,false,"America/Mexico_City",null)
        val lines=ReceiptLayoutInterpreter.interpret(emptyList(),input,32).filterIsInstance<LogicalLine.Text>().map { it.text }
        assertEquals(case.getValue("expectedText32").jsonArray.map { it.jsonPrimitive.content },lines)
        // Con el teléfono de la ficha igual al del sobre, el ticket no habla del teléfono.
        val samePhone=result.copy(settlement=result.settlement.copy(storeCredit=result.settlement.storeCredit!!.copy(phoneMismatch=false)))
        val plain=ReceiptLayoutInterpreter.interpret(emptyList(),ReceiptInputMapper.map(exchangeReceipt(samePhone,"IQ","Ana",Date(0)),null,false,"America/Mexico_City",null),32)
            .filterIsInstance<LogicalLine.Text>().map { it.text }
        assertEquals(lines.filterNot { it=="Se usa con el teléfono que" || it=="termina en 5678" },plain)
    }
    @Test fun `review cancelled and unknown exchanges cannot produce a change receipt`() {
        val fixture=Json.parseToJsonElement(javaClass.getResource("/exchange/receipt-exchange.json")!!.readText()).jsonObject
        val result=jsonReembolsos.decodeFromJsonElement(ExchangeResult.serializer(),fixture.getValue("exchange"))
        for(status in listOf("NEEDS_REVIEW","CANCELLED","PENDING_PAYMENT","FUTURE")) {
            assertTrue(runCatching { exchangeReceipt(result.copy(status=status),"IQ","Ana",Date(0)) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

}
