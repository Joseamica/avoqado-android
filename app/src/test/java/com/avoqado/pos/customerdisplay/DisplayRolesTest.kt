package com.avoqado.pos.customerdisplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lo que protegen estos tests: invertir las pantallas solo es válido cuando la
 * segunda es FÍSICA. En un Sunmi T3 Pro la pantalla del cliente es virtual y de
 * otra app (com.sunmi.usbscreen): Android NO le entrega toques, así que poner la
 * caja ahí dejaría al cajero con una pantalla muerta y el local sin cobrar.
 */
class DisplayRolesTest {

    private val hints = listOf("anydesk", "teamviewer", "scrcpy")

    // `val`, no `const val`: en Kotlin un const solo va top-level o en un object.
    private val DEFAULT = 0

    @Test
    fun `sin segunda pantalla la caja se queda en la principal y no es invertible`() {
        val roles = resolveDisplayRoles(DEFAULT, emptyList(), hints, inverted = false)
        assertEquals(DEFAULT, roles.cashierDisplayId)
        assertNull(roles.customerDisplayId)
        assertFalse(roles.invertible)
    }

    @Test
    fun `modo normal con segunda fisica - caja en la principal, cliente en la secundaria`() {
        val roles = resolveDisplayRoles(DEFAULT, listOf(CandidateDisplay(2, null)), hints, inverted = false)
        assertEquals(DEFAULT, roles.cashierDisplayId)
        assertEquals(2, roles.customerDisplayId)
        assertTrue(roles.invertible)
    }

    @Test
    fun `modo invertido con segunda fisica - caja en la secundaria, cliente en la principal`() {
        val roles = resolveDisplayRoles(DEFAULT, listOf(CandidateDisplay(2, null)), hints, inverted = true)
        assertEquals(2, roles.cashierDisplayId)
        assertEquals(DEFAULT, roles.customerDisplayId)
        assertTrue(roles.invertible)
    }

    @Test
    fun `T3 Pro - la virtual de vendor sirve como cliente pero NO es invertible`() {
        val roles = resolveDisplayRoles(
            DEFAULT,
            listOf(CandidateDisplay(3, "com.sunmi.usbscreen")),
            hints,
            inverted = true, // aunque lo pidan
        )
        assertEquals(DEFAULT, roles.cashierDisplayId) // la caja NO se mueve
        assertEquals(3, roles.customerDisplayId)
        assertFalse(roles.invertible)
    }

    @Test
    fun `la captura de escritorio remoto se descarta y no habilita invertir`() {
        val roles = resolveDisplayRoles(
            DEFAULT,
            listOf(CandidateDisplay(5, "com.anydesk.anydeskandroid")),
            hints,
            inverted = true,
        )
        assertEquals(DEFAULT, roles.cashierDisplayId)
        assertNull(roles.customerDisplayId)
        assertFalse(roles.invertible)
    }

    @Test
    fun `fisica y captura a la vez - gana la fisica y si es invertible`() {
        val roles = resolveDisplayRoles(
            DEFAULT,
            listOf(CandidateDisplay(5, "com.anydesk.anydeskandroid"), CandidateDisplay(2, null)),
            hints,
            inverted = true,
        )
        assertEquals(2, roles.cashierDisplayId)
        assertEquals(DEFAULT, roles.customerDisplayId)
    }

    @Test
    fun `invertido y desaparece la pantalla del cajero - vuelve a la principal`() {
        val roles = resolveDisplayRoles(DEFAULT, emptyList(), hints, inverted = true)
        assertEquals(DEFAULT, roles.cashierDisplayId)
        assertNull(roles.customerDisplayId)
    }

    @Test
    fun `anti-bucle - no relanza si ya esta en la pantalla correcta`() {
        assertFalse(shouldRelaunchCashier(currentDisplayId = 2, targetDisplayId = 2, attemptsForTarget = 0))
    }

    @Test
    fun `anti-bucle - relanza en el primer y segundo intento`() {
        assertTrue(shouldRelaunchCashier(currentDisplayId = 0, targetDisplayId = 2, attemptsForTarget = 0))
        assertTrue(shouldRelaunchCashier(currentDisplayId = 0, targetDisplayId = 2, attemptsForTarget = 1))
    }

    @Test
    fun `anti-bucle - al segundo intento fallido se rinde`() {
        // Sin esto, un equipo que ignore setLaunchDisplayId relanza la app para siempre.
        assertFalse(shouldRelaunchCashier(currentDisplayId = 0, targetDisplayId = 2, attemptsForTarget = 2))
    }

    // MARK: - Contabilidad de intentos (accountForEnforce)
    //
    // Es la única lógica no trivial del guard y falla en los dos sentidos:
    // reiniciar de más deja la caja relanzándose para siempre; reiniciar de menos
    // deja la caja en la pantalla equivocada cuando el escenario SÍ cambió — que
    // es exactamente lo que pasaba al reconectar la pantalla en caliente.

    @Test
    fun `contabilidad - la primera llamada arranca en cero y registra el escenario`() {
        val a = accountForEnforce(RelaunchAccounting(), presentDisplays = setOf(2), target = 2)
        assertEquals(setOf(2), a.displaySet)
        assertEquals(2, a.target)
        assertEquals(0, a.attempts)
    }

    @Test
    fun `contabilidad - mismo hardware y mismo destino NO reinicia los intentos`() {
        // Lo que corta el bucle infinito en un equipo que ignora setLaunchDisplayId:
        // pasar otra vez por enforce no compra intentos nuevos.
        val previo = RelaunchAccounting(displaySet = setOf(2), target = 2, attempts = 2)
        val a = accountForEnforce(previo, presentDisplays = setOf(2), target = 2)
        assertEquals(2, a.attempts)
        assertFalse(shouldRelaunchCashier(currentDisplayId = 0, targetDisplayId = 2, attemptsForTarget = a.attempts))
    }

    @Test
    fun `contabilidad - reconectar la pantalla es escenario nuevo y devuelve los intentos`() {
        // El caso que estaba roto: se rindió con la pantalla desconectada y al
        // volver a enchufarla hay que reintentar mover la caja.
        val rendido = RelaunchAccounting(displaySet = emptySet(), target = 0, attempts = 2)
        val a = accountForEnforce(rendido, presentDisplays = setOf(2), target = 2)
        assertEquals(0, a.attempts)
        assertTrue(shouldRelaunchCashier(currentDisplayId = 0, targetDisplayId = 2, attemptsForTarget = a.attempts))
    }

    @Test
    fun `contabilidad - desconectar la pantalla tambien es escenario nuevo`() {
        val previo = RelaunchAccounting(displaySet = setOf(2), target = 2, attempts = 2)
        val a = accountForEnforce(previo, presentDisplays = emptySet(), target = 0)
        assertEquals(emptySet<Int>(), a.displaySet)
        assertEquals(0, a.attempts)
    }

    @Test
    fun `contabilidad - cambiar de destino con el mismo hardware reinicia los intentos`() {
        // El usuario tocó el interruptor de invertir: mismo hardware, otro destino.
        val previo = RelaunchAccounting(displaySet = setOf(2), target = 0, attempts = 2)
        val a = accountForEnforce(previo, presentDisplays = setOf(2), target = 2)
        assertEquals(0, a.attempts)
    }

    @Test
    fun `escenario nuevo - solo lo son el cambio de hardware y el cambio de destino`() {
        // Es lo que decide, además de reiniciar la cuenta, si una instancia de
        // caja recupera el derecho a pedir la mudanza (ver relaunchRequestedFor).
        val previo = RelaunchAccounting(displaySet = setOf(2), target = 2, attempts = 1)
        assertFalse(isNewRelaunchScenario(previo, presentDisplays = setOf(2), target = 2))
        assertTrue(isNewRelaunchScenario(previo, presentDisplays = setOf(2, 3), target = 2))
        assertTrue(isNewRelaunchScenario(previo, presentDisplays = setOf(2), target = 0))
    }

    @Test
    fun `contabilidad - resetAttempts deja el siguiente enforce contando desde cero`() {
        // resetAttempts olvida el destino (no el hardware): el siguiente enforce
        // ve un escenario nuevo aunque el hardware no se haya movido.
        val trasReset = RelaunchAccounting(displaySet = setOf(2), target = null, attempts = 0)
        val a = accountForEnforce(trasReset, presentDisplays = setOf(2), target = 2)
        assertEquals(0, a.attempts)
    }
}
