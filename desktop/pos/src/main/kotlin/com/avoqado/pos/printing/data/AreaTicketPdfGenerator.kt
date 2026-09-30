package com.avoqado.pos.printing.data

import android.util.Log
import com.avoqado.pos.printing.data.ESCPOSPrinter.BarcodeSymbology
import com.avoqado.pos.printing.data.model.AreaTicketData
import javax.inject.Inject

/**
 * Reemplazo de escritorio: el PDF del vale se dibuja con android.graphics.pdf, que no existe en la PC.
 * 🔴 Aquí sí lanza, a propósito: devolver bytes vacíos sería un «éxito» falso (un PDF roto listo para guardarse).
 * Su único consumidor (AreaTicketOperationsViewModel.preparar el PDF) lo envuelve en runCatching y muestra el
 * mensaje en pantalla; imprimir el vale en la impresora sigue funcionando.
 */
class AreaTicketPdfGenerator @Inject constructor() {
    fun generate(
        ticket: AreaTicketData,
        symbology: BarcodeSymbology = BarcodeSymbology.CODE128_C,
    ): ByteArray {
        Log.w("Escritorio", "No disponible en Windows todavía: PDF del vale de área")
        throw UnsupportedOperationException("El PDF del vale no está disponible en Windows todavía. Imprímelo en la impresora.")
    }
}
