package com.avoqado.pos.printing.data

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.printing.data.model.PaperWidth
import com.avoqado.pos.printing.data.model.ReceiptData
import com.avoqado.pos.printing.data.model.ReceiptItem
import com.avoqado.pos.printing.receiptlayout.CanonicalLayout
import com.avoqado.pos.printing.receiptlayout.LogicalLine
import com.avoqado.pos.printing.receiptlayout.ReceiptLayoutInterpreter
import com.avoqado.pos.tpvsettings.data.ReceiptInfo
import com.avoqado.pos.tpvsettings.data.ReceiptLayoutPayload
import com.avoqado.pos.tpvsettings.data.ReceiptTicketCache
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/**
 * 🔴 Que el ticket salga con la RECETA y el ENCABEZADO del negocio — el corazón de la fase 3 — no
 * lo probaba NADA: neutralizar `ticket?.layout` (o `ticket?.info`) dentro de `ReceiptBranding.plan`
 * dejaba las 2 332 pruebas de la app en verde (medido en la revisión de la Task 9, sabotajes
 * S1/S1b). La causa es estructural: las únicas menciones de `ReceiptBranding` en las pruebas eran
 * `mockk(relaxed = true)`, así que el `plan` REAL no se ejecutaba nunca. Lo que las 12 pruebas de
 * D18 vigilan es el repositorio (que el disco se GUARDE bien); esto vigila el CONSUMO (que al
 * imprimir se LEA ese disco y se use).
 *
 * 🔑 La llave para que discrimine: la receta del negocio NO puede ser la canónica, o «usó la del
 * negocio» y «cayó a la canónica» serían indistinguibles. Se toma `CanonicalLayout.JSON` y se le
 * cambia la despedida: queda una receta íntegra pero DISTINTA. Y se afirma sobre el TEXTO
 * INTERPRETADO —lo que ve el cajero en el papel—, nunca sobre un objeto intermedio.
 */
class ReceiptBrandingPlanTest {

    private val recetaPropia = CanonicalLayout.JSON.replace(
        """"lines":["Gracias por su compra"]""",
        """"lines":["VUELVE PRONTO"]""",
    )

    private fun receipt() = ReceiptData(
        orderNumber = "42",
        orderType = "En tienda",
        items = listOf(ReceiptItem(name = "Galleta", quantity = 2, unitPrice = 2250, totalPrice = 4500)),
        subtotal = 4500,
        taxAmount = 621,
        total = 4500,
        paymentMethod = "Efectivo",
        venueName = "Testarudo Cafe",
        date = Date(1_788_372_300_000L),
    )

    private fun branding(ticket: ReceiptTicketCache, venueId: String = "venue-a"): ReceiptBranding {
        val repo = mockk<TpvSettingsRepository>()
        // Sólo responde a la sucursal ACTIVA: si `plan` pidiera otra, mockk lanza.
        coEvery { repo.receiptTicketFor(venueId) } returns ticket
        val storage = mockk<SecureStorage>()
        every { storage.venueId } returns venueId
        return ReceiptBranding(
            context = mockk(relaxed = true),
            tpvSettingsRepository = repo,
            receiptLogoCache = mockk { every { cachedBitmap(any()) } returns null },
            secureStorage = storage,
        )
    }

    private fun texto(plan: ReceiptPlan): String =
        ReceiptLayoutInterpreter.interpret(plan.blocks, plan.input, PaperWidth.MM80.charsPerLine)
            .filterIsInstance<LogicalLine.Text>()
            .joinToString("\n") { it.text }

    @Test
    fun `P1 el ticket sale con la receta del NEGOCIO, no con la canonica`() = runTest {
        val plan = branding(
            ReceiptTicketCache(
                info = ReceiptInfo(rfc = "TCA2501231A6"),
                layout = ReceiptLayoutPayload(schemaVersion = 1, revision = 9, blocks = Json.parseToJsonElement(recetaPropia)),
            ),
        ).plan(receipt(), PaperWidth.MM80)

        assertFalse("no puede caer a la canonica", plan.usedFallback)
        val t = texto(plan)
        assertTrue("la despedida del negocio", t.contains("VUELVE PRONTO"))
        assertFalse("la de la canonica no", t.contains("Gracias por su compra"))
    }

    @Test
    fun `P1 el encabezado fiscal guardado llega al papel`() = runTest {
        val plan = branding(
            ReceiptTicketCache(
                info = ReceiptInfo(rfc = "TCA2501231A6", legalName = "TESTARUDO CAFE SAPI"),
                layout = ReceiptLayoutPayload(schemaVersion = 1, revision = 9, blocks = Json.parseToJsonElement(CanonicalLayout.JSON)),
            ),
        ).plan(receipt(), PaperWidth.MM80)

        val t = texto(plan)
        assertTrue("RFC del cache", t.contains("TCA2501231A6"))
        assertTrue("razon social del cache", t.contains("TESTARUDO CAFE SAPI"))
    }

    @Test
    fun `P2 sin receta guardada sale la canonica`() = runTest {
        val plan = branding(ReceiptTicketCache()).plan(receipt(), PaperWidth.MM80)
        assertTrue(plan.usedFallback)
        assertTrue(texto(plan).contains("Gracias por su compra"))
    }

    @Test
    fun `P2 un schemaVersion desconocido cae a la canonica`() = runTest {
        val plan = branding(
            ReceiptTicketCache(layout = ReceiptLayoutPayload(schemaVersion = 2, revision = 9, blocks = Json.parseToJsonElement(recetaPropia))),
        ).plan(receipt(), PaperWidth.MM80)
        assertTrue(plan.usedFallback)
        assertFalse(texto(plan).contains("VUELVE PRONTO"))
    }
}
