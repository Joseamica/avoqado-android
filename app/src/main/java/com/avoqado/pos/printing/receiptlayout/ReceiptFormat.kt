package com.avoqado.pos.printing.receiptlayout

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.abs

/** Espejo de `format.ts`: sólo FORMATEA. Nunca redondea ni divide dinero. */
object ReceiptFormat {

    fun money(cents: Long): String {
        val absolute = abs(cents)
        val entero = (absolute / 100).toString().reversed().chunked(3).joinToString(",").reversed()
        val decimales = (absolute % 100).toString().padStart(2, '0')
        return (if (cents < 0) "-" else "") + "\$" + entero + "." + decimales
    }

    private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ROOT)

    /**
     * El instante de la VENTA en la zona del venue. `timezone` tiene que ser válida: el mapeador la
     * resuelve con `VenueTimeZone.zoneId()`, que ya tiene su respaldo.
     */
    fun dateTime(iso: String, timezone: String): String =
        DATE_TIME.format(parseInstant(iso).atZone(ZoneId.of(timezone)))

    private fun parseInstant(iso: String): Instant =
        try { Instant.parse(iso) } catch (_: DateTimeParseException) { OffsetDateTime.parse(iso).toInstant() }

    private val UNIDADES = listOf(
        "", "UN", "DOS", "TRES", "CUATRO", "CINCO", "SEIS", "SIETE", "OCHO", "NUEVE", "DIEZ",
        "ONCE", "DOCE", "TRECE", "CATORCE", "QUINCE", "DIECISÉIS", "DIECISIETE", "DIECIOCHO", "DIECINUEVE", "VEINTE",
        "VEINTIÚN", "VEINTIDÓS", "VEINTITRÉS", "VEINTICUATRO", "VEINTICINCO", "VEINTISÉIS", "VEINTISIETE", "VEINTIOCHO", "VEINTINUEVE",
    )
    private val DECENAS = listOf("", "", "", "TREINTA", "CUARENTA", "CINCUENTA", "SESENTA", "SETENTA", "OCHENTA", "NOVENTA")
    private val CENTENAS = listOf(
        "", "CIENTO", "DOSCIENTOS", "TRESCIENTOS", "CUATROCIENTOS", "QUINIENTOS", "SEISCIENTOS", "SETECIENTOS", "OCHOCIENTOS", "NOVECIENTOS",
    )

    private fun menorQueMil(n: Int): String {
        if (n == 0) return ""
        if (n == 100) return "CIEN"
        val c = n / 100
        val r = n % 100
        val partes = mutableListOf<String>()
        if (c != 0) partes += CENTENAS[c]
        if (r in 1..29) {
            partes += UNIDADES[r]
        } else if (r >= 30) {
            val d = r / 10
            val u = r % 10
            partes += if (u != 0) "${DECENAS[d]} Y ${UNIDADES[u]}" else DECENAS[d]
        }
        return partes.joinToString(" ")
    }

    fun amountInWords(cents: Long): String {
        val absolute = abs(cents)
        val pesos = absolute / 100
        val centavos = absolute % 100
        var deMillones = false
        val palabras = if (pesos == 0L) {
            "CERO"
        } else {
            val millones = (pesos / 1_000_000).toInt()
            val miles = ((pesos % 1_000_000) / 1000).toInt()
            val resto = (pesos % 1000).toInt()
            val p = mutableListOf<String>()
            if (millones == 1) p += "UN MILLÓN" else if (millones > 1) p += "${menorQueMil(millones)} MILLONES"
            if (miles == 1) p += "MIL" else if (miles > 1) p += "${menorQueMil(miles)} MIL"
            if (resto != 0) p += menorQueMil(resto)
            deMillones = millones > 0 && miles == 0 && resto == 0
            p.joinToString(" ")
        }
        val moneda = if (pesos == 1L) "PESO" else if (deMillones) "DE PESOS" else "PESOS"
        return "$palabras $moneda ${centavos.toString().padStart(2, '0')}/100 M.N."
    }
}

data class ResolvedEmisor(val legalName: String?, val rfc: String?, val lugarExpedicion: String?)

/** Espejo de `resolveEmisor.ts`: el emisor del merchant que cobró → el principal → el legacy. */
object FiscalEmisorResolver {
    fun resolve(venue: ReceiptVenueInfo, merchantAccountId: String?): ResolvedEmisor? {
        val byMerchant = merchantAccountId?.takeIf { it.isNotEmpty() }
            ?.let { id -> venue.fiscalEmisors.firstOrNull { id in it.merchantAccountIds } }
        if (byMerchant != null) return ResolvedEmisor(byMerchant.legalName, byMerchant.rfc, byMerchant.lugarExpedicion)

        val principal = venue.principalEmisorId?.takeIf { it.isNotEmpty() }
            ?.let { pid -> venue.fiscalEmisors.firstOrNull { it.id == pid } }
            ?: venue.fiscalEmisors.firstOrNull()
        if (principal != null) return ResolvedEmisor(principal.legalName, principal.rfc, principal.lugarExpedicion)

        if (!venue.legacy.legalName.isNullOrEmpty() || !venue.legacy.rfc.isNullOrEmpty()) {
            return ResolvedEmisor(venue.legacy.legalName, venue.legacy.rfc, null)
        }
        return null
    }
}
