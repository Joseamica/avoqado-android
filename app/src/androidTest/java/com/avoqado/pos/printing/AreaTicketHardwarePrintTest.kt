package com.avoqado.pos.printing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.avoqado.pos.printing.data.ESCPOSPrinter
import com.avoqado.pos.printing.data.SunmiInnerPrinter
import com.avoqado.pos.printing.data.model.AreaTicketData
import com.avoqado.pos.printing.data.model.PaperWidth
import com.avoqado.pos.printing.data.model.ReceiptItem
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 🖨️ SALE PAPEL DE VERDAD. Corre contra la impresora integrada de una Sunmi D3.
 *
 * Los 554 tests JVM prueban que los BYTES son correctos. Ninguno prueba que la impresora
 * los entienda, que el código de barras salga legible, ni que quepa en el rollo. Eso sólo
 * se sabe con papel enfrente y la pistola en la mano.
 *
 * Lo que hay que revisar EN EL PAPEL cuando salga:
 *  1. ¿El código de barras se lee con la pistola del cliente?
 *  2. ¿Sale completo o se corta del lado derecho? (§11 del spec: CODE39 con 10 dígitos
 *     NO cabe en 58 mm ni al ancho mínimo; CODE128 sí. Este test imprime los dos para
 *     resolver empíricamente lo que el manual no dice.)
 *  3. ¿Los acentos salen bien? ("CREMERÍA", "Atendió") — la integrada de Sunmi arranca en
 *     multibyte y necesita `FS .` antes del code page.
 *  4. ¿El peso del granel se lee de un vistazo? Es lo que el cliente compara con la báscula.
 *
 * Correr con:
 *   ./gradlew connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.avoqado.pos.printing.AreaTicketHardwarePrintTest
 */
@RunWith(AndroidJUnit4::class)
class AreaTicketHardwarePrintTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Los dos renglones REALES del ticket del cliente (§4.3 del spec). */
    private fun itemsDelCliente() = listOf(
        ReceiptItem(
            name = "LOMO CANADIENSE",
            quantity = 1,
            unitPrice = 16400,
            totalPrice = 3674,
            weightSummary = "0.224 kg × \$164.00/kg",
        ),
        ReceiptItem(
            name = "QUESO MANCHEGO",
            quantity = 1,
            unitPrice = 23350,
            totalPrice = 7145,
            weightSummary = "0.306 kg × \$233.50/kg",
        ),
    )

    private fun vale(code: String) = AreaTicketData(
        areaTicketCode = code,
        areaName = "Cremería",
        items = itemsDelCliente(),
        totalCents = 10819,
        venueName = "Prueba Avoqado",
        staffName = "Rosa",
        holdsProduct = true,
    )

    /**
     * La integrada de la D3 es de 58 mm y arranca en multibyte: `switchToSingleByteFirst`
     * es obligatorio o se come el ticket entero (ver el KDoc de [ESCPOSPrinter]).
     */
    private fun escpos() = ESCPOSPrinter(
        paperWidth = PaperWidth.MM58,
        switchToSingleByteFirst = true,
    )

    @Test
    fun imprime_vale_con_CODE128() = runBlocking {
        val printer = SunmiInnerPrinter(context)
        check(printer.ensureBound()) {
            "No se pudo enlazar el servicio de impresión de Sunmi. " +
                "¿Es una Sunmi con cabezal? ¿La app tiene el permiso?"
        }
        val bytes = escpos().generateAreaTicket(
            vale("9470000015"),
            symbology = ESCPOSPrinter.BarcodeSymbology.CODE128_C,
        )
        printer.printRaw(bytes)
    }

    /**
     * El respaldo de §4.2. El spec (§11) calcula que con 10 dígitos NO cabe en 58 mm:
     * ~211 módulos × 2 = 422 puntos contra 384 disponibles. Pero ESC/POS no fija la razón
     * ancho:angosto de CODE39 y cada fabricante usa la suya — asumimos 3:1, la peor.
     *
     * **Este papel resuelve la duda.** Si sale completo, la razón real es más angosta y el
     * respaldo CODE39 sirve en 58 mm. Si sale cortado, el respaldo exige rollo de 80.
     */
    @Test
    fun imprime_vale_con_CODE39_para_medir_si_cabe_en_58mm() = runBlocking {
        val printer = SunmiInnerPrinter(context)
        check(printer.ensureBound()) { "No se pudo enlazar el servicio de impresión de Sunmi." }
        val bytes = escpos().generateAreaTicket(
            vale("9470000023"),
            symbology = ESCPOSPrinter.BarcodeSymbology.CODE39,
        )
        printer.printRaw(bytes)
    }

    /**
     * §5.3 — algunos negocios no quieren que el cliente vea el desglose antes de la caja.
     * El vale debe seguir siendo escaneable sin precios.
     */
    @Test
    fun imprime_vale_sin_precios() = runBlocking {
        val printer = SunmiInnerPrinter(context)
        check(printer.ensureBound()) { "No se pudo enlazar el servicio de impresión de Sunmi." }
        val bytes = escpos().generateAreaTicket(
            vale("9470000031").copy(showPrices = false),
            symbology = ESCPOSPrinter.BarcodeSymbology.CODE128_C,
        )
        printer.printRaw(bytes)
    }
}
