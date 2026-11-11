package com.avoqado.pos.cashdrawer.data

import com.avoqado.pos.core.util.RoleDisplay
import com.avoqado.pos.core.util.formatMoneyFromCents
import com.avoqado.pos.printing.data.ESCPOSPrinter
import com.avoqado.pos.printing.data.model.PaperWidth
import com.avoqado.pos.printing.data.model.TablaDeAcentos

/**
 * Comprobante en papel de un retiro de efectivo (con la autorización del gerente), para que firmen quien entrega y quien
 * recibe. Todo viene ya resuelto en [ComprobanteDeRetiro]; aquí sólo se arma el papel.
 */
object RetiroTicketBuilder {

    fun build(
        c: ComprobanteDeRetiro,
        reimpresion: Boolean,
        paperWidth: PaperWidth,
        switchToSingleByteFirst: Boolean = false,
        tablaDeAcentos: TablaDeAcentos = TablaDeAcentos.WINDOWS_1252,
    ): ByteArray {
        val p = ESCPOSPrinter(paperWidth, switchToSingleByteFirst, tablaDeAcentos = tablaDeAcentos)
        val ancho = paperWidth.charsPerLine

        // «Etiqueta   valor» en una línea; si no cabe, la etiqueta sola y el valor partido en renglones del ancho del papel.
        fun fila(etiqueta: String, valor: String) {
            if (etiqueta.length + 1 + valor.length < ancho) {
                p.printTwoColumns(etiqueta, valor)
            } else {
                p.printLine(etiqueta)
                valor.chunked(ancho).forEach { p.printLine(it) }
            }
        }

        p.reset()
        p.setAlignment(ESCPOSPrinter.TextAlignment.CENTER)
        p.setBold(true)
        p.printLine(c.venueName)
        p.printDivider()
        p.printTitle("RETIRO DE EFECTIVO", bold = true)
        p.printDivider()
        if (reimpresion) p.printLine("REIMPRESIÓN")
        p.setBold(false)
        p.setAlignment(ESCPOSPrinter.TextAlignment.LEFT)
        p.printTwoColumns(c.fechaTexto, c.horaTexto)
        c.nombreDelAparato?.let { p.printLine("Caja: $it") }
        p.printLine()
        fila("Concepto:", c.concepto)
        p.setBold(true)
        p.printTwoColumns("Importe:", formatMoneyFromCents(c.amountCents))
        p.setBold(false)
        p.printLine()
        val rol = RoleDisplay.label(null, c.rolDeQuienHizo)
        fila("Lo hizo:", if (rol != null) "${c.hizo} ($rol)" else c.hizo)
        fila("Autorizado por:", c.autorizadoPor)
        p.printDivider()
        p.setAlignment(ESCPOSPrinter.TextAlignment.CENTER)
        p.feedLines(2)
        p.printLine("______________________")
        p.printLine("Firma de quien entrega")
        p.feedLines(2)
        p.printLine("______________________")
        p.printLine("Firma de quien recibe")
        p.feedLines(3)
        p.cut()
        return p.getData()
    }
}
