package com.avoqado.pos.kiosk

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.kiosk.domain.KioskContent
import com.avoqado.pos.kiosk.domain.KioskPerson
import com.avoqado.pos.kiosk.domain.KioskSession
import com.avoqado.pos.kiosk.domain.KioskState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Al terminar con alguien (su «Listo» se vence, o toca «Regresar»), el kiosco vuelve a la
 * LISTA de la clase abierta, no a «Bienvenida».
 *
 * 🔴 Nace de la D3 (26-oct, demo de Mindform): tras el check-in por teléfono la pantalla
 * caía en «Bienvenida · El registro se abre antes de cada clase» —falso, la clase estaba
 * abierta— y se quedaba así ~15 s, hasta el siguiente tic del reloj. El temporizador ponía
 * el reposo sin avisarle a nadie, y el reloj además esperaba 25 s desde el último toque.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KioskVolverALaListaTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainRule = MainDispatcherRule(dispatcher)

    private val lista = KioskContent.Roster(
        classTitle = "Yoga",
        timeLabel = "7:00 PM",
        staffLabel = "con María",
        people = listOf(KioskPerson(reservationId = "r1", displayName = "Ana R.", checkedIn = false, spotLabel = "Tapete 4")),
        justConfirmedId = "r1",
    )
    private val listo = KioskContent.CheckedIn(
        customerName = "Paulina L.",
        session = KioskSession(reservationId = "r2", title = "Yoga", timeLabel = "", staffLabel = "con María"),
    )

    @Test
    fun `al vencerse el Listo regresa a la lista de la clase abierta`() {
        val kiosk = KioskState()
        kiosk.showFromServer(lista)
        kiosk.show(listo)

        dispatcher.scheduler.advanceUntilIdle()

        val ahora = kiosk.content.value
        assertTrue("esperaba la lista, llegó $ahora", ahora is KioskContent.Roster)
        // Vuelve limpia: sin el renglón abierto de quien ya se fue.
        assertNull((ahora as KioskContent.Roster).justConfirmedId)
    }

    @Test
    fun `al vencerse le avisa a quien conduce para que pida la lista al dia`() {
        val kiosk = KioskState()
        var avisos = 0
        kiosk.onTimedOut = { avisos++ }
        kiosk.showFromServer(lista)
        kiosk.show(listo)

        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, avisos)
    }

    @Test
    fun `Regresar vuelve a la lista sin esperar a la red`() {
        val kiosk = KioskState()
        kiosk.showFromServer(lista)
        kiosk.show(KioskContent.Identify(country = com.avoqado.pos.designsystem.components.Countries.pinned.first(), national = "55"))

        kiosk.backToList()

        assertTrue(kiosk.content.value is KioskContent.Roster)
    }

    @Test
    fun `sin clase abierta el Listo si regresa al reposo`() {
        val kiosk = KioskState()
        kiosk.showFromServer(lista)
        kiosk.show(KioskContent.Welcome) // el reloj cerró la clase
        kiosk.show(listo)

        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(KioskContent.Welcome, kiosk.content.value)
        assertNull(kiosk.lastRoster)
    }

    @Test
    fun `una lista vieja no regresa - la clase pudo cerrarse mientras alguien tecleaba`() {
        val kiosk = KioskState()
        var reloj = 1_000_000L
        kiosk.now = { reloj }
        kiosk.showFromServer(lista)
        kiosk.show(KioskContent.Identify(country = com.avoqado.pos.designsystem.components.Countries.pinned.first(), national = "55"))

        reloj += 3 * 60_000L
        kiosk.backToList()

        assertEquals(KioskContent.Welcome, kiosk.content.value)
    }

    @Test
    fun `volver a la lista no la hace mas nueva`() {
        val kiosk = KioskState()
        var reloj = 1_000_000L
        kiosk.now = { reloj }
        val telefono = KioskContent.Identify(country = com.avoqado.pos.designsystem.components.Countries.pinned.first(), national = "")
        kiosk.showFromServer(lista)

        reloj += 90_000L
        kiosk.show(lista.copy(justConfirmedId = null)) // una confirmación local, sin red
        kiosk.show(telefono); kiosk.backToList()
        assertTrue(kiosk.content.value is KioskContent.Roster)

        reloj += 60_000L // 150 s desde que llegó del servidor
        kiosk.show(telefono); kiosk.backToList()
        assertEquals(KioskContent.Welcome, kiosk.content.value)
    }

    @Test
    fun `al cambiar de sesion se olvida la lista de la sucursal anterior`() {
        val kiosk = KioskState()
        kiosk.showFromServer(lista)
        kiosk.show(KioskContent.Identify(country = com.avoqado.pos.designsystem.components.Countries.pinned.first(), national = ""))

        val antes = kiosk.epoch
        kiosk.forgetRoster()

        // Lo que estaba a la vista se va YA, y las consultas en camino quedan invalidadas.
        assertEquals(KioskContent.Welcome, kiosk.content.value)
        assertEquals(antes + 1, kiosk.epoch)
        kiosk.backToList()
        assertEquals(KioskContent.Welcome, kiosk.content.value)
    }

    @Test
    fun `mientras busca el telefono no se vence`() {
        val kiosk = KioskState()
        kiosk.showFromServer(lista)
        kiosk.show(KioskContent.Identify(country = com.avoqado.pos.designsystem.components.Countries.pinned.first(), national = "5510000002", searching = true))

        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(kiosk.content.value is KioskContent.Identify)
    }

    @Test
    fun `un numero tecleado y abandonado se borra solo`() {
        val kiosk = KioskState()
        kiosk.showFromServer(lista)
        kiosk.show(KioskContent.Identify(country = com.avoqado.pos.designsystem.components.Countries.pinned.first(), national = "5510000002"))

        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(kiosk.content.value is KioskContent.Roster)
    }
}
