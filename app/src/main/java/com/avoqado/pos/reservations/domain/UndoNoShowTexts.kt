package com.avoqado.pos.reservations.domain

import com.avoqado.pos.core.util.VenueDateTimeFormatter
import com.avoqado.pos.core.util.VenueTimeZone
import com.avoqado.pos.core.util.formatMoney
import com.avoqado.pos.reservations.data.model.CustomerLite
import com.avoqado.pos.reservations.data.model.NoShowCorrection
import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.data.model.UndoNoShowPlan
import java.time.format.DateTimeFormatter

/**
 * D4 · los textos de «Sí llegó», IGUALES en ANDROID e iOS (Global Constraints del plan). iOS copia estos.
 * La etiqueta del estado en los POS es «No se presentó» (el SERVIDOR y el kiosco dicen «No asistió»).
 */
object UndoNoShowTexts {
    const val BUTTON = "Sí llegó"
    const val CONFIRM = "Sí, llegó"
    const val CANCEL = "Cancelar"
    const val OFFLINE = "Necesita conexión: deshacer un “No se presentó” cambia el paquete y el depósito"
    const val OFFLINE_RESOLVE = "Necesita conexión para marcarlo resuelto"
    const val OFFLINE_TITLE = "Sin conexión"
    const val ERROR_TITLE = "No se pudo completar la acción"
    const val CUT = "Se cortó la conexión. Vuelve a tocar «Sí llegó»: si ya se había corregido, no se repite."
    const val INTRO = "Esto deshace lo que hizo el «No se presentó»:"
    const val LOG = "Queda en la bitácora quién lo deshizo y a qué hora."
    const val PENDING_INTRO = "Esto no se puede arreglar solo y quedará como pendiente:"
    const val SUCCESS = "Llegada corregida"
    const val ALREADY = "Ya estaba corregida"
    const val PENDING_LEFT = "Pendiente de esta llegada:"
    const val OK = "Entendido"
    const val PASS_TITLE = "Se corrige desde la app del pase"
    const val PASS_BOOKING = "Esta reserva vino de un pase (TotalPass/Wellhub); corrígela desde su app"
    const val PASS = "Pase"
    const val NO_SHOW = "No se presentó"
    const val BANNER_TITLE = "Pendiente de esta llegada"
    const val RESOLVE = "Marcar resuelto"
    const val RESOLVED = "Pendiente resuelto"
    /** Aviso de la franja que el SERVIDOR ya no tiene (404 en SU sucursal): se quita sin decir que lo resolvimos. */
    const val NO_LONGER_PENDING = "Ya no estaba pendiente"
    const val TITLE_FALLBACK = "¿Sí llegó?"

    /** Debajo de las faltas de la lista de la clase cuando el SERVIDOR no las mandó todas. */
    fun more(n: Int) = "y $n más"

    /** «¿Karla R. sí llegó a Yoga de las 08:51?» ([name] ya en corto: así se distinguen dos «Karla» en la clase). */
    fun title(name: String, className: String?, time: String) = "¿$name sí llegó a ${className ?: "su reserva"} de las $time?"

    /** «Karla R.»: la regla del kiosco y del SERVIDOR (sin apellido, sólo el nombre). */
    fun shortName(first: String?, last: String?): String {
        val f = first?.trim().orEmpty()
        val l = last?.trim().orEmpty()
        if (f.isEmpty() && l.isEmpty()) return "Invitado"
        return if (l.isNotEmpty()) "$f ${l.first().uppercaseChar()}.".trim() else f
    }

    /** «Ana Gómez» → «Ana G.» */
    fun shortNameFromFull(full: String?): String {
        val parts = full?.trim()?.split(" ")?.filter { it.isNotBlank() }.orEmpty()
        if (parts.isEmpty()) return "Invitado"
        return shortName(parts.first(), parts.drop(1).joinToString(" "))
    }

    fun shortName(customer: CustomerLite?, guestName: String?): String =
        customer?.let { shortName(it.firstName, it.lastName) } ?: shortNameFromFull(guestName)

    /** HH:mm en la zona del negocio. */
    fun hhmm(iso: String?, timezone: String?): String? =
        VenueDateTimeFormatter.parseIso(iso)?.let { VenueTimeZone.at(it, timezone).format(DateTimeFormatter.ofPattern("HH:mm")) }

    fun title(r: Reservation, timezone: String?): String =
        title(name = shortName(r.customer, r.guestName), className = r.displayServiceName, time = hhmm(r.startsAt, timezone) ?: "--:--")

    /** Sólo los renglones que aplican, más la bitácora. Con montos y paquete cuando el SERVIDOR los manda. */
    fun rows(plan: UndoNoShowPlan): List<String> = buildList {
        val n = plan.creditToReconsume
        if (n > 0) {
            val verbo = if (n == 1) "se le vuelve a descontar" else "se le vuelven a descontar"
            add(
                plan.packName?.let { "Clase: $verbo $n de «$it»." }
                    ?: "Clase: $verbo $n ${if (n == 1) "clase" else "clases"} de su paquete.",
            )
        }
        if (plan.clearFee) {
            add(plan.feeAmount?.let { "Multa: se anula la de ${formatMoney(it)} (no se había cobrado)." }
                ?: "Multa: se anula la multa por no presentarse.")
        }
        if (plan.restoreDeposit) {
            add(plan.depositAmount?.let { "Depósito: sus ${formatMoney(it)} vuelven a ser parte de su visita." }
                ?: "Depósito: vuelve a ser parte de su visita.")
        }
        add(LOG)
    }

    fun pending(code: String): String = when (code) {
        "CLASS_UNPAID" -> "Esta clase no estaba pagada: cóbrala en caja"
        "CREDIT_NOT_RECONSUMED" -> "No había clase que descontar: cóbrala o regálala"
        "FEE_CAPTURED" -> "La multa ya se cobró a la tarjeta: si corresponde, reembólsala desde el pago"
        "KIOSK_TOO_OLD" -> "Llegada del kiosco de hace más de 3 días: revisa si sí llegó"
        "KIOSK_UNVERIFIED" -> "El kiosco mandó una llegada que no se pudo comprobar: revisa si sí llegó"
        else -> "Hay algo de esta llegada que revisar"
    }

    /** «Llegó · corregido 09:40 por Ana» · «… por el kiosco» · sin autor si no se sabe. Sin hora, no hay nota (como iOS). */
    fun correctedNote(c: NoShowCorrection, time: String?): String? {
        if (time.isNullOrBlank()) return null
        val quien = c.actorName ?: if (c.source == "KIOSK") "el kiosco" else null
        return "Llegó · corregido $time" + (quien?.let { " por $it" } ?: "")
    }

    /**
     * La franja: la llegada guardada en este aparato encontró su «No se presentó» ya corregido. Neutro a propósito: la
     * `correction` del SERVIDOR no dice si la hizo el kiosco o una persona antes de que saliera la fila.
     */
    fun kioskNotice(name: String?, time: String?): String = when {
        name != null && time != null -> "$name llegó a las $time sin conexión; su «No se presentó» ya quedó como «Llegó»."
        time != null -> "Una llegada del kiosco de las $time: su «No se presentó» ya quedó como «Llegó»."
        else -> "Una llegada del kiosco: su «No se presentó» ya quedó como «Llegó»."
    }
}
