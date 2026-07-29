// Vale de área — acuñado y resolución del código. Helpers PUROS: sin Compose, sin Android, sin
// estado. Se prueban en JVM (AreaTicketCodeTest) y se espejan por nombre EXACTO en el server y en
// iOS — si un lado calcula el verificador distinto, TODOS los vales se rechazan.
//
// Formato: `9 PP NNNNNN C` — 10 dígitos. Cada parte existe por una razón concreta (§5.1 del spec
// docs/superpowers/specs/2026-07-28-vales-por-area-y-bascula-design.md):
//
//  9        Espacio de nombres propio. 10 dígitos NO es EAN-8 (8) ni UPC-A (12) ni EAN-13 (13):
//           ningún GTIN real ocupa ese largo, así que ningún producto puede esconder un vale.
//           El escáner busca PRODUCTO primero (CheckoutScreen.kt), y sin espacio propio un
//           producto con el mismo código se tragaba el vale en silencio.
//  PP       Partición del dispositivo (10..99). La asigna el SERVER en el login y se cachea
//           local (ver [AreaTicketCodeStore]). Dos dispositivos con la misma partición acuñan
//           la misma secuencia: por eso no se inventa nunca del lado del cliente.
//  NNNNNN   Contador MONÓTONO. Jamás se reinicia — ni por día, ni por turno, ni por logout.
//           Una versión previa lo reiniciaba a diario: el primer vale de hoy repetía el código
//           del primer vale de ayer y resolvía la cuenta de OTRO cliente. No era caso borde,
//           pasaba TODOS los días. 1M de vales por partición.
//  C        Verificador mod-10. Detecta errores de dedo cuando el cajero re-teclea un vale
//           arrugado. NO ES SEGURIDAD: es público y se adivina en diez intentos; contra el canje
//           ajeno va rate limiting en el endpoint de resolución (§5.1).
package com.avoqado.pos.pos.data

/** Primer dígito: el espacio de nombres que separa vales de productos. */
const val AREA_TICKET_NAMESPACE = '9'

/** Largo total del código, verificador incluido. Elegido justamente por NO ser 8, 12 ni 13. */
const val AREA_TICKET_CODE_LENGTH = 10

/** Partición del dispositivo: 2 dígitos, sin `00`..`09` para que el código no parezca truncado. */
const val MIN_AREA_TICKET_PARTITION = 10
const val MAX_AREA_TICKET_PARTITION = 99

/** Contador de 6 dígitos. Empieza en 1 (ver [AreaTicketCodeStore]) y NUNCA da la vuelta. */
const val MIN_AREA_TICKET_COUNTER = 0L
const val MAX_AREA_TICKET_COUNTER = 999_999L

/**
 * Verificador mod-10 **GS1** — el mismo de EAN-13 / UPC-A / GTIN: pesos 3 y 1 alternados desde el
 * dígito más a la derecha del payload, y el verificador es lo que falta para el siguiente múltiplo
 * de 10.
 *
 * Se eligió GS1 (y no Luhn, que también se llama "mod-10") porque estos códigos viven impresos en
 * un CODE128 junto a productos EAN: cualquier librería de códigos de barras, y cualquiera que ya
 * conozca GTIN, calcula ESTE. Server e iOS deben mirrorearlo **tal cual** — Luhn daría otro dígito
 * para el mismo payload y ningún vale resolvería.
 *
 * Cobertura real: detecta el 100% de los errores de UN dígito, y las transposiciones adyacentes
 * salvo cuando los dos dígitos difieren en 5 (27↔72). Es un guard contra el dedo, no seguridad.
 *
 * @param payload los 9 dígitos `9PPNNNNNN` (funciona con cualquier cadena de dígitos).
 * @throws IllegalArgumentException si [payload] está vacío o trae algo que no sea dígito — es un
 *   error de programación, no una entrada de usuario: quien recibe códigos escaneados usa
 *   [isAreaTicketCode] o [resolveScannedCode], que jamás lanzan.
 */
fun checkDigit(payload: String): Int {
    require(payload.isNotEmpty() && payload.all { it.isDigit() }) {
        "checkDigit requiere una cadena de dígitos, recibió: '$payload'"
    }
    var sum = 0
    payload.reversed().forEachIndexed { index, char ->
        val digit = char - '0'
        // El dígito pegado al verificador pesa 3; luego alterna 1,3,1,3… hacia la izquierda.
        sum += if (index % 2 == 0) digit * 3 else digit
    }
    return (10 - sum % 10) % 10
}

/**
 * Acuña el código `9PPNNNNNNC`. **No** avanza ningún contador: eso es responsabilidad de
 * [AreaTicketCodeStore], que además lo persiste antes de entregarlo.
 *
 * @throws IllegalArgumentException si la partición no está en 10..99 o el contador no cabe en 6
 *   dígitos. Lanza en vez de devolver null a propósito: un rango inválido significa partición
 *   corrupta o contador agotado, y un código mal formado impreso en un vale es un cliente que se
 *   va con producto que nadie puede cobrar. El llamador (el store) valida ANTES y traduce el caso
 *   de agotamiento a [AreaTicketMint.PartitionExhausted], que sí es un estado de negocio.
 */
fun buildAreaTicketCode(partition: Int, counter: Long): String {
    require(partition in MIN_AREA_TICKET_PARTITION..MAX_AREA_TICKET_PARTITION) {
        "Partición fuera de rango ($MIN_AREA_TICKET_PARTITION..$MAX_AREA_TICKET_PARTITION): $partition"
    }
    require(counter in MIN_AREA_TICKET_COUNTER..MAX_AREA_TICKET_COUNTER) {
        "Contador fuera de rango ($MIN_AREA_TICKET_COUNTER..$MAX_AREA_TICKET_COUNTER): $counter"
    }
    val payload = "$AREA_TICKET_NAMESPACE" +
        partition.toString().padStart(2, '0') +
        counter.toString().padStart(6, '0')
    return payload + checkDigit(payload)
}

/**
 * ¿Este código escaneado es un vale? Regla FIJA de §5.1, en este orden: 10 dígitos, empieza en
 * `9`, y el verificador cuadra. Cualquier otra cosa es producto.
 *
 * Nunca lanza: recibe lo que sea que escupa la pistola (o el dedo del cajero).
 *
 * Ojo con lo que **no** valida: el rango de la partición. El server es la autoridad de qué
 * particiones existen; un código bien formado de una partición que este dispositivo no conoce
 * igual se manda a resolver, y el server contesta `NOT_FOUND` con mensaje. Validarlo aquí sólo
 * lograría que un vale legítimo de otra caja se tratara como producto inexistente.
 */
fun isAreaTicketCode(raw: String): Boolean {
    val code = raw.trim()
    if (code.length != AREA_TICKET_CODE_LENGTH) return false
    if (code[0] != AREA_TICKET_NAMESPACE) return false
    if (!code.all { it.isDigit() }) return false
    val payload = code.dropLast(1)
    return checkDigit(payload) == (code.last() - '0')
}

/** Resultado de la resolución del escáner. Exhaustivo a propósito: no hay caso "no sé". */
sealed interface ScannedCode {
    /**
     * Vale de área: se resuelve contra `GET /mobile/venues/:venueId/area-tickets/:code`.
     * [partition] y [counter] van sólo para logs/diagnóstico — la autoridad de a qué cuenta
     * pertenece el vale es el server.
     */
    data class AreaTicket(val code: String, val partition: Int, val counter: Long) : ScannedCode

    /** Todo lo demás: SKU, EAN-8, UPC-A, EAN-13, QR, o basura. Se busca en el catálogo. */
    data class Product(val code: String) : ScannedCode
}

/**
 * Punto ÚNICO de decisión del escáner: vale o producto. Función pura, sin catálogo y sin red, para
 * que la misma regla valga en checkout, en mesas y en la pantalla de entrega.
 *
 * Se aplica `trim()` porque muchas pistolas HID agregan CR/LF al final; nada más se toca: los SKU
 * pueden ser alfanuméricos y filtrar caracteres rompería la búsqueda de producto.
 *
 * Determinista y sin ambigüedad → arregla el defecto de que un producto pudiera esconder un vale.
 */
fun resolveScannedCode(raw: String): ScannedCode {
    val code = raw.trim()
    if (!isAreaTicketCode(code)) return ScannedCode.Product(code)
    return ScannedCode.AreaTicket(
        code = code,
        partition = code.substring(1, 3).toInt(),
        counter = code.substring(3, 9).toLong(),
    )
}
