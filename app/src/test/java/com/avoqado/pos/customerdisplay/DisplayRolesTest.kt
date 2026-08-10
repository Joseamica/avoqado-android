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

    // MARK: - Reponer la ventana del cliente (decideCustomerRemount)
    //
    // El letrero no usa lock-task: un HOME del cliente, un overlay del sistema o
    // la gestión de energía del fabricante lo mandan al fondo y la Activity queda
    // viva pero fuera de pantalla. Remontar desde su propio onStop es lo único
    // instantáneo, y es exactamente donde vive el riesgo de lazo
    // (dismiss → onStop → remontar → …). Estos tests son la prueba de que el lazo
    // no existe: la decisión no la toma el onStop, la toma el DESEO del manager.

    @Test
    fun `remonte - el manager la sigue queriendo aqui, se repone`() {
        val v = decideCustomerRemount(
            desiredDisplayId = 0,
            stoppedDisplayId = 0,
            previousAttempts = 0,
            lastRemountAtMs = 0L,
            nowMs = 1_000L,
        )
        assertTrue(v.remount)
        assertEquals(1, v.attempts)
        assertFalse(v.gaveUp)
    }

    @Test
    fun `remonte - el manager la cerro a proposito, NO se repone (este es el lazo)`() {
        // finishCustomerActivity() retira el deseo ANTES del finish(), así que el
        // onStop que ese cierre provoca llega con el deseo en null. Si esto
        // devolviera true, el manager no podría cerrar nunca la ventana:
        // dismiss → onStop → remontar → dismiss → … para siempre.
        val v = decideCustomerRemount(
            desiredDisplayId = null,
            stoppedDisplayId = 0,
            previousAttempts = 2,
            lastRemountAtMs = 1_000L,
            nowMs = 1_200L,
        )
        assertFalse(v.remount)
        assertFalse(v.gaveUp)
        assertEquals(0, v.attempts) // y la ráfaga muere con el deseo
    }

    @Test
    fun `remonte - el deseo apunta a OTRA pantalla, no se repone`() {
        // Cambió el modo mientras esta ventana se apagaba: el manager quiere al
        // cliente en la secundaria (camino Presentation), no aquí.
        val v = decideCustomerRemount(
            desiredDisplayId = 2,
            stoppedDisplayId = 0,
            previousAttempts = 1,
            lastRemountAtMs = 1_000L,
            nowMs = 1_200L,
        )
        assertFalse(v.remount)
        assertEquals(0, v.attempts)
    }

    @Test
    fun `remonte - tope de la rafaga - se rinde al tercero seguido`() {
        // Con el modo kiosco activo, lanzar esta Activity es tarea nueva y
        // lock-task lo rechaza devolviendo un CÓDIGO, no una excepción: nadie se
        // entera del fallo. Sin tope, cada caída pediría otro lanzamiento.
        var attempts = 0
        var last = 0L
        repeat(MAX_CUSTOMER_REMOUNTS) { i ->
            val now = 1_000L + i * 100L // ráfaga rápida: dentro del periodo de calma
            val v = decideCustomerRemount(0, 0, attempts, last, now)
            assertTrue("intento ${i + 1} debería reponerse", v.remount)
            attempts = v.attempts
            last = now
        }
        val rendido = decideCustomerRemount(0, 0, attempts, last, 1_400L)
        assertFalse(rendido.remount)
        assertTrue(rendido.gaveUp)
        assertEquals(MAX_CUSTOMER_REMOUNTS, rendido.attempts) // la cuenta NO se pierde al rendirse
    }

    @Test
    fun `remonte - rendirse es para el parpadeo rapido, no para el turno entero`() {
        // Si la ventana aguantó el periodo de calma, la ráfaga se cerró: el
        // siguiente HOME del cliente merece servicio. Sin esto, el tercer HOME de
        // la mañana lo dejaría viendo el escritorio hasta reiniciar la app.
        val v = decideCustomerRemount(
            desiredDisplayId = 0,
            stoppedDisplayId = 0,
            previousAttempts = MAX_CUSTOMER_REMOUNTS,
            lastRemountAtMs = 1_000L,
            nowMs = 1_000L + CUSTOMER_REMOUNT_QUIET_PERIOD_MS,
        )
        assertTrue(v.remount)
        assertFalse(v.gaveUp)
        assertEquals(1, v.attempts) // arranca ráfaga nueva
    }

    @Test
    fun `remonte - seguir rendido mientras el parpadeo siga siendo rapido`() {
        // Justo por debajo del periodo de calma: no cuenta como aguantar.
        val v = decideCustomerRemount(
            desiredDisplayId = 0,
            stoppedDisplayId = 0,
            previousAttempts = MAX_CUSTOMER_REMOUNTS,
            lastRemountAtMs = 1_000L,
            nowMs = 1_000L + CUSTOMER_REMOUNT_QUIET_PERIOD_MS - 1,
        )
        assertFalse(v.remount)
        assertTrue(v.gaveUp)
    }

    @Test
    fun `remonte - el primer arranque no arrastra una rafaga fantasma`() {
        // lastRemountAtMs = 0 (nunca se remontó) no puede parecer "acaba de
        // pasar": la primera caída del turno se repone siempre.
        val v = decideCustomerRemount(
            desiredDisplayId = 0,
            stoppedDisplayId = 0,
            previousAttempts = 0,
            lastRemountAtMs = 0L,
            nowMs = CUSTOMER_REMOUNT_QUIET_PERIOD_MS * 10,
        )
        assertTrue(v.remount)
        assertEquals(1, v.attempts)
    }
}
