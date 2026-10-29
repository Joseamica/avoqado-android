package com.avoqado.pos.kiosk

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.kiosk.data.PackStatus
import com.avoqado.pos.kiosk.domain.KioskContent
import com.avoqado.pos.kiosk.domain.KioskPack
import com.avoqado.pos.kiosk.domain.KioskState
import com.avoqado.pos.kiosk.domain.waitForPackOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/**
 * La pantalla de pago del kiosco (D3 del pizarrón): sabe cuándo se pagó, y nunca se queda
 * colgada en la entrada.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KioskPagoTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainRule = MainDispatcherRule(dispatcher)

    private val pack = KioskPack("p1", "10 clases", 150_000, null)

    @Test
    fun `sigue preguntando con OPEN y con la red caida hasta PAID, cada 3 s`() = runTest(dispatcher) {
        val r = ArrayDeque(listOf(Result.success(PackStatus.OPEN), Result.failure(IOException()), Result.success(PackStatus.PAID)))
        var llamadas = 0

        assertEquals(PackStatus.PAID, waitForPackOutcome({ llamadas++; r.removeFirst() }))
        assertEquals(3, llamadas)
        assertEquals(6_000L, currentTime)
    }

    @Test
    fun `EXPIRED termina la espera`() = runTest(dispatcher) {
        assertEquals(PackStatus.EXPIRED, waitForPackOutcome({ Result.success(PackStatus.EXPIRED) }))
    }

    @Test
    fun `el QR se vence a los 5 minutos y regresa solo`() = runTest(dispatcher) {
        val state = KioskState()
        var vencio = false
        state.onTimedOut = { vencio = true }
        state.show(KioskContent.Paying(customerName = "", pack = pack, payUrl = "https://pay/x"))

        advanceTimeBy(299_999)
        assertTrue(state.content.value is KioskContent.Paying)

        advanceTimeBy(2)
        assertEquals(KioskContent.Welcome, state.content.value)
        assertTrue(vencio)
    }

    @Test
    fun `si no se pudo preparar el pago, la pantalla no ocupa la entrada 5 minutos`() = runTest(dispatcher) {
        val state = KioskState()
        state.show(KioskContent.Paying(customerName = "", pack = pack, payUrl = null, failed = true))

        advanceTimeBy(15_001)

        assertEquals(KioskContent.Welcome, state.content.value)
    }
}
