package com.avoqado.pos.kiosk.domain

import com.avoqado.pos.designsystem.components.Countries
import com.avoqado.pos.core.util.VenueDateTimeFormatter
import com.avoqado.pos.reservations.data.ReservationRepository
import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.data.model.ReservationFilters
import com.avoqado.pos.reservations.data.model.ReservationStatus
import com.avoqado.pos.reservations.domain.ReservationAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Quien CONDUCE el kiosco. Su idea central: **la pantalla la manda el reloj, no
 * una persona.**
 *
 * A las 5:40 se abre sola la lista de la clase de las 6:00; a las 6:15 (inicio
 * + tolerancia) se cierra sola y vuelve a la marca del negocio; a las 6:40 se
 * abre la de las 7:00. Nadie del estudio toca nada, y **la agenda del día jamás
 * aparece** — sólo quien viene a la clase de ahorita.
 *
 * ```
 * inicio − 20 min   ≤   ahora   <   inicio + tolerancia
 * ```
 *
 * Esa ventana **no la inventé aquí**: es la que ya aplica el servidor
 * (`evaluateKioskWindow` en `checkIn.service.ts`), y la vuelve a checar al
 * confirmar. Si no coincide, responde 422 `CHECK_IN_OUTSIDE_WINDOW`. Esta
 * pantalla es comodidad; el permiso lo da el servidor.
 *
 * ⚠️ **Límite conocido y a propósito:** para decidir QUÉ pintar se usa el reloj
 * del aparato, porque la app no tiene hora del servidor. Un D3 con la hora
 * corrida abriría la lista a destiempo — pero **no puede producir un check-in
 * inválido**, porque el 422 sigue del otro lado. Cuando exista hora de servidor
 * en la app, este es el sitio a cambiar.
 *
 * Singleton y no ViewModel: la segunda pantalla sigue viva sin importar en qué
 * pantalla ande el cajero.
 */
@Singleton
class KioskDriver @Inject constructor(
    private val kiosk: KioskState,
    private val reservations: ReservationRepository,
    private val dates: VenueDateTimeFormatter,
    private val kioskCheckIn: com.avoqado.pos.kiosk.data.KioskCheckInApi,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ticker: Job? = null
    private var work: Job? = null

    /** Tolerancia que configuró el admin. Se refresca con los ajustes. */
    private var graceMin: Int = KioskWindow.DEFAULT_GRACE_MIN

    /** Cuándo tocó alguien por última vez, para no cerrarle la pantalla encima. */
    private var lastTouchAt: Long = 0L

    /** Se enchufa una vez al arrancar la app. Idempotente. */
    fun attach() {
        kiosk.onCheckIn = { touched(); checkIn(it) }
        kiosk.onRestart = { touched(); refreshNow() }

        // Respaldo "no aparezco en la lista": teclear el teléfono. Se identifica por
        // CONTACTO, como el resto del mercado (WellnessLiving pide "Client ID, Email, or
        // Phone number"), y no por el código de confirmación — que nadie se sabe de
        // memoria. Lo que hace seguro aceptar un teléfono no es esta pantalla: es que el
        // servidor conteste lo mismo cuando no hay reserva que cuando el número no
        // existe, y que su límite de intentos sea durable.
        kiosk.onStart = { touched(); kiosk.show(KioskContent.Identify(country = Countries.pinned.first(), national = "")) }
        kiosk.onDigit = { d -> touched(); editDigits { it + d } }
        kiosk.onDelete = { touched(); editDigits { it.dropLast(1) } }
        kiosk.onSearch = { touched(); identify() }

        // La compra entra en la rebanada siguiente. Un callback nulo hace que la pantalla
        // NI PINTE su botón: un botón muerto de cara al cliente es peor que no tenerlo.
        kiosk.onSeePacks = null
        kiosk.onBuy = null

        scope.launch {
            kiosk.enabled.collect { on -> if (on) start() else stop() }
        }
    }

    private fun start() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                refresh()
                delay(POLL_MS)
            }
        }
    }

    private fun stop() {
        ticker?.cancel(); ticker = null
        work?.cancel(); work = null
    }

    private fun touched() {
        lastTouchAt = System.currentTimeMillis()
        kiosk.keepAlive()
    }

    private fun refreshNow() {
        work?.cancel()
        scope.launch { refresh() }
    }

    // MARK: - El reloj decide

    private suspend fun refresh() {
        // 🔴 Nunca le quites la pantalla a alguien que está tocando. Es lo que
        // pidió el founder tal cual, y es lo que separa un kiosco usable de uno
        // que se cierra justo cuando ibas a picar tu nombre.
        if (System.currentTimeMillis() - lastTouchAt < INTERACTION_HOLD_MS) return

        // La tolerancia la manda el admin; si el servidor no la dice, se cae al
        // mismo default que él usa. Nunca se adivina un número distinto.
        reservations.reservationSettings().getOrNull()
            ?.scheduling?.noShowGraceMin
            ?.let { graceMin = it }

        val candidatas = reservasEnVentana() ?: return  // Sin red: deja lo que ya estaba, no parpadea.

        val activo = grupoEnVentana(candidatas)

        if (activo == null) {
            if (kiosk.content.value !is KioskContent.Welcome) kiosk.show(KioskContent.Welcome)
            return
        }

        kiosk.show(
            KioskContent.Roster(
                classTitle = titulo(activo.first()),
                timeLabel = dates.formatTime(activo.first().startsAt),
                // 🔴 En una clase el instructor vive en la SESIÓN, no en la
                // reserva (44 de 44 contra 0 de 9, medido). Primero la sesión.
                staffLabel = (
                    activo.first().classSession?.assignedStaff?.firstName
                        ?: activo.first().assignedStaff?.firstName
                    )?.let { "con $it" },
                people = activo.map(::toPerson),
            ),
        )
    }


    /**
     * Las reservas que PUEDEN estar en ventana ahora mismo.
     *
     * Se le pide al servidor el rango exacto, no "el día":
     *
     * ```
     * ahora − tolerancia   <   inicio   ≤   ahora + 20 min
     * ```
     *
     * que es despejar `inicio` de la condición de ventana. Son instantes
     * absolutos, así que no hay zona horaria de por medio.
     *
     * 🔴 **Pedir "hoy" estaba MAL y sólo se vio corriéndolo.** Yo mandaba
     * `LocalDate.now(zonaDelVenue)` — "2026-08-24" en México — mientras el
     * servidor compara contra `startsAt`, que se guarda en UTC: una clase de las
     * 19:58 de México vive como `2026-08-25T01:58Z`. El servidor contestaba 200
     * con la lista VACÍA y el kiosco se quedaba en "Bienvenida" sin explicar
     * nada. Es la trampa de zona horaria de siempre, y aquí se cierra pidiendo
     * instantes en vez de fechas.
     *
     * De pasada arregla el otro problema: una ventana de ~35 minutos nunca trae
     * cientos de renglones, así que la paginación deja de importar.
     */
    private suspend fun reservasEnVentana(): List<Reservation>? {
        val now = Instant.now()
        val desde = now.minusSeconds(graceMin * 60L)
        val hasta = now.plusSeconds(KioskWindow.EARLY_MIN * 60L)

        return reservations.fetchList(
            ReservationFilters(
                dateFrom = desde.toString(),
                dateTo = hasta.toString(),
                page = 1,
                pageSize = PAGE_SIZE,
            ),
            background = true,
        ).getOrNull()?.data
    }

    /**
     * De todo lo de hoy, el grupo cuya ventana está abierta AHORA.
     *
     * Se agrupa por sesión de clase; una cita suelta es su propio grupo de una
     * persona, así que el mismo código sirve para un estudio y para un salón de
     * belleza sin ramificar.
     *
     * Si dos clases se traslapan gana **la que empieza más pronto**: es la que
     * la gente que está parada enfrente está buscando.
     */
    private fun grupoEnVentana(all: List<Reservation>): List<Reservation>? {
        val now = Instant.now()
        return all
            .filter { it.status == ReservationStatus.PENDING ||
                it.status == ReservationStatus.CONFIRMED ||
                it.status == ReservationStatus.CHECKED_IN }
            .filter { enVentana(it, now) }
            .groupBy { it.classSessionId ?: it.id }
            .entries
            .minByOrNull { (_, rs) -> rs.first().startsAt }
            ?.value
    }

    private fun enVentana(r: Reservation, now: Instant): Boolean {
        val starts = runCatching { Instant.parse(r.startsAt) }.getOrNull() ?: return false
        return KioskWindow.isOpen(starts, now, graceMin)
    }

    // MARK: - Confirmar llegada

    /**
     * Tocó su nombre.
     *
     * 🔴 Se queda EN LA LISTA. En una clase se registran varias personas
     * seguidas: mandar a cada una a un "Listo, Ana" de pantalla completa
     * bloquearía a la siguiente de la fila durante segundos. Su renglón se marca
     * y la de atrás toca el suyo de inmediato.
     */
    /** Edita los dígitos sin perder el resto del estado de la pantalla. */
    private fun editDigits(transform: (String) -> String) {
        val current = kiosk.content.value as? KioskContent.Identify ?: return
        kiosk.show(current.copy(national = transform(current.national).take(10), notFound = false, failed = false))
    }

    /**
     * Manda el teléfono y, si el servidor hace el check-in, lo confirma en pantalla.
     *
     * Es UN paso, no dos: el servidor no devuelve "esta persona tiene estas clases" y
     * luego se elige. Eso sería un buscador de personas en la entrada del negocio. Aquí,
     * cuando llega algo que pintar, es porque el check-in YA ocurrió.
     */
    private fun identify() {
        val current = kiosk.content.value as? KioskContent.Identify ?: return
        if (!current.canSearch) return

        work?.cancel()
        kiosk.show(current.copy(searching = true, notFound = false, failed = false))

        work = scope.launch {
            val result = kioskCheckIn.checkIn(current.national)
            val base = kiosk.content.value as? KioskContent.Identify ?: return@launch

            result.fold(
                onSuccess = { c ->
                    kiosk.show(
                        KioskContent.CheckedIn(
                            customerName = c.displayName,
                            session = KioskSession(
                                reservationId = c.reservationId.orEmpty(),
                                title = c.title,
                                timeLabel = "",
                                staffLabel = c.staffLabel,
                                alreadyCheckedIn = c.alreadyCheckedIn,
                            ),
                        ),
                    )
                },
                onFailure = { err ->
                    // "No encontramos nada" y "se cayó la red" NO son lo mismo para quien
                    // está parado ahí: uno se arregla tecleando otra vez, el otro no.
                    kiosk.show(
                        base.copy(
                            searching = false,
                            notFound = err is com.avoqado.pos.kiosk.data.KioskCheckInApi.NotFound,
                            failed = err !is com.avoqado.pos.kiosk.data.KioskCheckInApi.NotFound,
                        ),
                    )
                },
            )
        }
    }

    private fun checkIn(person: KioskPerson) {
        val current = kiosk.content.value as? KioskContent.Roster ?: return
        if (person.checkedIn) return

        work?.cancel()
        kiosk.show(current.copy(busyId = person.reservationId, failed = false))

        work = scope.launch {
            val result = reservations.runAction(person.reservationId, ReservationAction.CHECK_IN)
            val base = kiosk.content.value as? KioskContent.Roster ?: return@launch

            val ok = result.isSuccess ||
                // Encolado sin red: la persona SÍ llegó y el intent ya está
                // guardado. Pintarlo como error sería mentir al revés — la regla
                // del repo lo prohíbe explícitamente.
                result.exceptionOrNull() is ReservationRepository.OfflineEnqueuedException

            kiosk.show(
                base.copy(
                    busyId = null,
                    failed = !ok,
                    justConfirmedId = if (ok) person.reservationId else null,
                    people = base.people.map {
                        if (it.reservationId == person.reservationId && ok) it.copy(checkedIn = true) else it
                    },
                ),
            )

            // Se encoge solo. Si mientras tanto alguien más confirma, ese otro
            // renglón manda y este cierre ya no aplica.
            if (ok) {
                delay(CONFIRMED_EXPANDED_MS)
                val ahora = kiosk.content.value as? KioskContent.Roster ?: return@launch
                if (ahora.justConfirmedId == person.reservationId) {
                    kiosk.show(ahora.copy(justConfirmedId = null))
                }
            }
        }
    }

    // MARK: - Traducción de lo que manda el servidor

    private fun titulo(r: Reservation): String =
        r.classSession?.productName ?: r.product?.name ?: "Tu cita"

    /**
     * Nombre + inicial. **Nunca el apellido completo ni el teléfono**: esto se
     * pinta de cara a la entrada, donde cualquiera que pase lo ve.
     */
    private fun toPerson(r: Reservation): KioskPerson {
        val first = r.customer?.firstName?.takeIf { it.isNotBlank() }
            ?: r.guestName?.trim()?.substringBefore(' ')?.takeIf { it.isNotBlank() }
        val last = r.customer?.lastName?.takeIf { it.isNotBlank() }
            ?: r.guestName?.trim()?.substringAfter(' ', "")?.takeIf { it.isNotBlank() }

        val label = when {
            first == null -> "Invitada"
            last == null -> first
            else -> "$first ${last.first().uppercaseChar()}."
        }

        return KioskPerson(
            reservationId = r.id,
            displayName = label,
            checkedIn = r.status == ReservationStatus.CHECKED_IN,
            // El acomodo del salón vive en el producto; la reserva sólo guarda
            // el id del lugar. Sin acomodo configurado esto queda en null y la
            // pantalla simplemente no lo enseña.
            spotLabel = r.spotIds.firstOrNull()
                ?.let { r.product?.layoutConfig?.labelFor(it) }
                ?.let { "Lugar $it" },
        )
    }

    private companion object {
        /** Tope del servidor. Pedir más devuelve 400 de validación. */
        const val PAGE_SIZE = 100

        /** Cada cuánto se pregunta "¿qué clase toca?". */
        const val POLL_MS = 15_000L

        /** Cuánto se respeta a quien acaba de tocar antes de volver a mandar el reloj. */
        const val INTERACTION_HOLD_MS = 25_000L

        /** Cuánto se queda abierto el renglón con el lugar y el instructor. */
        const val CONFIRMED_EXPANDED_MS = 8_000L
    }
}
