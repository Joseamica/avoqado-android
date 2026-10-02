package com.avoqado.pos.printing.data

/**
 * La parte PURA de «la impresora que se encuentra sola» (Testarudo, 2-oct-2026).
 *
 * El caso real: el router le dio a la TABLET la dirección que antes tenía la impresora de
 * cocina (192.168.1.64). La app seguía tocando esa puerta —la de la propia tablet— y cada
 * comanda tronaba con `ECONNREFUSED`. La impresora estaba sana en otra dirección; nada en la
 * app sabía buscarla, porque las ticketeras de cocina casi nunca se anuncian por mDNS.
 *
 * Sin reloj, sin sockets, sin Android: el barrido real vive en [BuscadorDeImpresora].
 */
object ImpresoraMovida {

    /**
     * Las direcciones vecinas que vale la pena tocar en el puerto 9100.
     *
     * Una red más grande que /24 sólo se barre en el /24 propio: 65 mil direcciones
     * tardarían minutos, y en un local la impresora vive en el mismo bloque que la tablet.
     * Excluye la propia, la de red y la de difusión. /31, /32 o algo inválido ⇒ nada.
     */
    fun hostsDeLaSubred(ipPropia: String, prefijo: Int): List<String> {
        val propia = aEntero(ipPropia) ?: return emptyList()
        if (prefijo < 1 || prefijo > 30) return emptyList()
        val efectivo = maxOf(prefijo, 24)
        val mascara = (0xFFFFFFFFL shl (32 - efectivo)) and 0xFFFFFFFFL
        val red = propia and mascara
        val difusion = red or (mascara.inv() and 0xFFFFFFFFL)
        return ((red + 1) until difusion)
            .filter { it != propia }
            .map { aTexto(it) }
    }

    /**
     * ¿Esta respuesta a `DLE EOT 1` (estado de la impresora) es de una ticketera ESC/POS?
     *
     * El byte de estado tiene cuatro bits FIJOS por especificación: el 1 y el 4 en 1, el 0
     * y el 7 en 0 (`xxx1 xx10` con el bit 7 en 0 ⇒ máscara 0x93 == 0x12). Un banner de texto,
     * un servidor HTTP o un silencio no cumplen esa forma por casualidad.
     *
     * 🔴 Es lo que impide mandarle la comanda a una impresora de OFICINA (HP, Kyocera, Ricoh):
     * también escuchan en el 9100, pero no contestan este estado. Medido en la red de
     * Testarudo (2-oct): aparecían cuatro de ésas en la búsqueda.
     */
    fun esEstadoDeTicketera(byte: Int): Boolean = byte in 0..0xFF && (byte and 0x93) == 0x12

    /**
     * Qué hacer con las ticketeras que contestaron.
     *
     * @param ticketeras las direcciones que contestaron como ticketera ESC/POS.
     * @param ocupadas las direcciones de las OTRAS impresoras ya conocidas: si contestan ahí,
     *   son ellas, no la que se movió.
     *
     * 🔴 Con dos o más candidatas NO se adivina: mandar la comanda de Cocina a la impresora
     * de Barra es peor que avisar. Se pregunta.
     */
    fun elegir(ticketeras: List<String>, ocupadas: Set<String>): Eleccion {
        val libres = ticketeras.filter { it !in ocupadas }.distinct().sortedBy { aEntero(it) ?: 0L }
        return when (libres.size) {
            0 -> Eleccion.Ninguna
            1 -> Eleccion.Una(libres.single())
            else -> Eleccion.Varias(libres)
        }
    }

    // MARK: - La dirección que vale hoy

    /**
     * La dirección con la que hay que intentar a una impresora, según las mudanzas recordadas.
     *
     * 🔴 La mudanza sólo aplica si la dirección configurada SIGUE siendo la vieja: si alguien la
     * corrigió en el dashboard (o a mano), gana lo nuevo y la mudanza recordada se ignora.
     */
    fun direccionVigente(mudanzas: Map<String, Mudanza>, idImpresora: String, direccionConfigurada: String): String {
        val mudanza = mudanzas[idImpresora] ?: return direccionConfigurada
        return if (mudanza.original == direccionConfigurada) mudanza.nueva else direccionConfigurada
    }

    // MARK: - Lo que se le dice al cajero

    fun avisoDireccionPropia(direccion: String): String =
        "La impresora tenía la dirección $direccion, que ahora es la de esta tablet: el módem les " +
            "cambió las direcciones. No la encontré en la red: revisa que esté encendida y búscala en " +
            "Más → Impresoras."

    fun avisoVarias(direccion: String, cuantas: Int): String =
        "La impresora ya no está en $direccion y hay $cuantas ticketeras en la red. Elige la correcta " +
            "en Más → Impresoras."

    fun avisoNinguna(direccion: String): String =
        "La impresora no contesta en $direccion y no encontré otra ticketera en la red. Revisa que " +
            "esté encendida y conectada al WiFi del local."

    /** ¿Este texto es uno de los avisos de arriba? (para no re-traducirlo encima). */
    fun esAvisoParaCajero(texto: String): Boolean =
        texto.contains("ticketera") || texto.contains("es la de esta tablet")

    private fun aEntero(ip: String): Long? {
        val partes = ip.trim().split('.')
        if (partes.size != 4) return null
        var valor = 0L
        for (parte in partes) {
            val n = parte.toIntOrNull() ?: return null
            if (n !in 0..255) return null
            valor = (valor shl 8) or n.toLong()
        }
        return valor
    }

    private fun aTexto(valor: Long): String =
        listOf(24, 16, 8, 0).joinToString(".") { ((valor shr it) and 0xFF).toString() }
}

/** Una impresora que se encontró en otra dirección. Se recuerda por id de impresora. */
@kotlinx.serialization.Serializable
data class Mudanza(val original: String, val nueva: String)

sealed interface Eleccion {
    data object Ninguna : Eleccion
    data class Una(val direccion: String) : Eleccion
    data class Varias(val direcciones: List<String>) : Eleccion
}
