package com.avoqado.pos.core.data.local

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D2 (vales-ciclo, Codex r7 #1/#2/#4): la llave de emisión de vales, por negocio. Las preferencias de la prueba separan el disco
 * de la memoria del proceso, como AOSP: la memoria cambia ANTES de escribir, y `reiniciar()` es lo que ve la app al volver.
 */
class LlaveDeEmisionDeValesTest {
    private class Prefs(val disco: MutableMap<String, Any> = mutableMapOf()) {
        var commitFalla = false
        private val memoria = disco.toMutableMap()

        fun reiniciar() = Prefs(disco.toMutableMap())

        val prefs: SharedPreferences = mockk<SharedPreferences>().also { p ->
            every { p.getString(any(), any()) } answers { memoria[firstArg()] as String? ?: secondArg() }
            every { p.getBoolean(any(), any()) } answers { memoria[firstArg()] as Boolean? ?: secondArg() }
            every { p.edit() } answers {
                val cambios = mutableMapOf<String, Any?>() // null = borrar
                mockk<SharedPreferences.Editor>().also { e ->
                    every { e.putString(any(), any()) } answers { cambios[firstArg()] = secondArg<String?>(); e }
                    every { e.putBoolean(any(), any()) } answers { cambios[firstArg()] = secondArg<Boolean>(); e }
                    every { e.remove(any()) } answers { cambios[firstArg()] = null; e }
                    fun aplicar(m: MutableMap<String, Any>) = cambios.forEach { (k, v) -> if (v == null) m.remove(k) else m[k] = v }
                    every { e.commit() } answers { aplicar(memoria); if (commitFalla) false else { aplicar(disco); true } }
                    every { e.apply() } answers { aplicar(memoria) } // apply() no escribe en esta prueba: se pierde al reiniciar
                }
            }
        }
    }

    @Test
    fun `D2 la llave de una version anterior la usa cualquier negocio hasta que ese negocio la suelta`() {
        // Una versión anterior dejó UNA llave para todo el aparato, sin decir de qué negocio era. Si se ignoraba, el reintento del
        // vale cuya respuesta se perdió antes de actualizar salía con llave nueva: segundo vale. Dársela a UN negocio (al primero
        // que emitía, r8 #1, o al activo al arrancar, r9 #1-#3) fallaba cuando era otro. Ahora cada negocio la ve hasta soltarla.
        val p = Prefs(mutableMapOf("pendingAreaTicketIssueKey" to "llave-de-la-version-anterior"))
        assertEquals("llave-de-la-version-anterior", LlaveDeEmisionDeVales.leer(p.prefs, "venue-1"))
        assertEquals("llave-de-la-version-anterior", LlaveDeEmisionDeVales.leer(p.prefs, "venue-2"))

        // B emitió con ella y el servidor confirmó: B la suelta; A la sigue teniendo para recuperar SU vale.
        LlaveDeEmisionDeVales.guardar(p.prefs, "venue-2", "llave-de-la-version-anterior")
        LlaveDeEmisionDeVales.soltar(p.prefs, "venue-2", siEs = "llave-de-la-version-anterior")

        val tras = p.reiniciar()
        assertNull("B no la vuelve a heredar", LlaveDeEmisionDeVales.leer(tras.prefs, "venue-2"))
        assertEquals("llave-de-la-version-anterior", LlaveDeEmisionDeVales.leer(tras.prefs, "venue-1"))
    }

    @Test
    fun `D2 Vaciar despues de actualizar suelta tambien la llave de la version anterior`() {
        // Codex r8 #3: si la global seguía viva para ese negocio, el siguiente vale volvía a chocar con 409.
        val p = Prefs(mutableMapOf("pendingAreaTicketIssueKey" to "llave-de-la-version-anterior"))

        LlaveDeEmisionDeVales.soltar(p.prefs, "venue-1")

        assertNull(LlaveDeEmisionDeVales.leer(p.prefs, "venue-1"))
        assertNull("Al volver a arrancar no revive", LlaveDeEmisionDeVales.leer(p.reiniciar().prefs, "venue-1"))
    }

    @Test
    fun `D2 guardar escribe en disco y dice false si el disco no lo confirma`() {
        // `apply()` cambia la memoria y escribe después sin avisar: releer decía «guardada» aunque el disco fallara, y tras
        // reiniciar la llave ya no estaba.
        val p = Prefs()
        assertTrue(LlaveDeEmisionDeVales.guardar(p.prefs, "venue-1", "llave-1"))
        assertEquals("llave-1", LlaveDeEmisionDeVales.leer(p.reiniciar().prefs, "venue-1"))

        val falla = Prefs().apply { commitFalla = true }
        assertFalse(LlaveDeEmisionDeVales.guardar(falla.prefs, "venue-1", "llave-1"))
    }

    @Test
    fun `D2 soltar con la llave usada no borra la de otra emision`() {
        // Una confirmación que llega tarde (otra pantalla ya emitió con otra llave) no puede soltar la llave vigente.
        val p = Prefs()
        LlaveDeEmisionDeVales.guardar(p.prefs, "venue-1", "llave-2")

        LlaveDeEmisionDeVales.soltar(p.prefs, "venue-1", siEs = "llave-1")
        assertEquals("llave-2", LlaveDeEmisionDeVales.leer(p.prefs, "venue-1"))

        LlaveDeEmisionDeVales.soltar(p.prefs, "venue-1", siEs = "llave-2")
        assertNull(LlaveDeEmisionDeVales.leer(p.reiniciar().prefs, "venue-1"))

        LlaveDeEmisionDeVales.guardar(p.prefs, "venue-1", "llave-3")
        LlaveDeEmisionDeVales.soltar(p.prefs, "venue-1") // «Vaciar»: sin condición
        assertNull(LlaveDeEmisionDeVales.leer(p.prefs, "venue-1"))
    }
}
