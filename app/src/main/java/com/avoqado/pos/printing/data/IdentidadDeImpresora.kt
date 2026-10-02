package com.avoqado.pos.printing.data

/**
 * La IDENTIDAD de una impresora de red: lo que no cambia aunque el módem le cambie la IP. Es la
 * «placa del coche»; la IP es sólo el lugar donde se estacionó hoy.
 *
 * Dos formas, las mismas que guarda el servidor en `Printer.stableKey`:
 * - `mac:02107B1A76FC` — su dirección de hardware. Las ticketeras genéricas con chip W5500 (la de
 *   Testarudo) la publican en su página web (`/w5500.js`), sin imprimir nada (medido el 2-oct).
 * - `mdns:EPSON TM-m30III` — el nombre con que se anuncia por Bonjour. Las de marca no publican la
 *   MAC, pero la red no deja repetir un nombre.
 *
 * 🔴 Sin identidad, dos ticketeras genéricas que se intercambian la IP no se distinguen: la comanda
 * de Cocina saldría en Barra. Con identidad, la app sabe cuál es cuál.
 *
 * PURA: sin red. Espejo de `IdentidadDeImpresora.swift`.
 */
object IdentidadDeImpresora {

    private const val PREFIJO_MAC = "mac:"
    private const val PREFIJO_MDNS = "mdns:"

    fun deMac(mac: String): String? = normalizarMac(mac)?.let { PREFIJO_MAC + it }

    fun deNombre(nombreAnunciado: String): String? =
        nombreAnunciado.trim().takeIf { it.isNotEmpty() && it.length <= 100 }?.let { PREFIJO_MDNS + it }

    fun esMac(identidad: String?): Boolean = identidad?.startsWith(PREFIJO_MAC) == true

    /** El nombre anunciado de una identidad `mdns:`, o null si es de otro tipo. */
    fun nombreAnunciado(identidad: String?): String? =
        identidad?.takeIf { it.startsWith(PREFIJO_MDNS) }?.removePrefix(PREFIJO_MDNS)

    /**
     * La MAC del JSONP de una ticketera W5500:
     * `setCallback({"m0":"02","m1":"10","m2":"7B","m3":"1A","m4":"76","m5":"FC",...})`.
     */
    fun macDeW5500(cuerpo: String): String? {
        val octetos = (0..5).map { i ->
            Regex("\"m$i\"\\s*:\\s*\"([0-9A-Fa-f]{1,2})\"").find(cuerpo)?.groupValues?.get(1) ?: return null
        }
        return deMac(octetos.joinToString("") { it.padStart(2, '0') })
    }

    /** La primera MAC escrita en una página web (`02:10:7B:1A:76:FC` o con guiones). */
    fun macDeHtml(cuerpo: String): String? =
        Regex("(?<![0-9A-Fa-f:-])([0-9A-Fa-f]{2}([:-])(?:[0-9A-Fa-f]{2}\\2){4}[0-9A-Fa-f]{2})(?![0-9A-Fa-f:-])")
            .findAll(cuerpo)
            .mapNotNull { deMac(it.groupValues[1]) }
            .firstOrNull()

    /** 12 dígitos hexadecimales en mayúsculas, o null. Las MAC vacías (todo 0 o todo F) no cuentan. */
    private fun normalizarMac(mac: String): String? {
        val limpia = mac.filter { it.isLetterOrDigit() }.uppercase()
        if (limpia.length != 12 || !limpia.all { it in '0'..'9' || it in 'A'..'F' }) return null
        if (limpia.all { it == '0' } || limpia.all { it == 'F' }) return null
        return limpia
    }
}
