package com.avoqado.pos.cashdrawer

import com.avoqado.pos.cashdrawer.presentation.checklistOnClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Task 7 (plan turno-de-caja fase 2-3): "Cierre del día" tenía tres renglones sin `onClick` — una
 * cajera de Testarudo los veía al abrir la app y no llevaban a ningún lado. Los tres SOLO deben
 * navegar cuando están en ADVERTENCIA (`ok = false`); un renglón ya resuelto (`ok = true`) se
 * comporta EXACTO como antes de este cambio: tocar la fila no hace nada.
 *
 * Una prueba por destino, como pide el brief — comparten el mismo mecanismo (`checklistOnClick`)
 * porque los tres siguen la MISMA regla ("¿ok? no navegues : navega"), pero cada prueba fija el
 * caso de negocio real de ESE renglón.
 */
class EndOfDayChecklistNavigationTest {

    @Test
    fun `cuentas abiertas - navega solo cuando openChecks tiene alguna sin cerrar`() {
        var llamadas = 0
        val destino: () -> Unit = { llamadas++ }

        // openChecks.count == 0 -> ok = true -> "Sin cuentas abiertas": no navega
        assertNull(checklistOnClick(ok = 0 == 0, destino = destino))
        assertEquals(0, llamadas)

        // openChecks.count == 3 -> ok = false -> hay 3 cuentas sin cerrar: SÍ navega
        val onClick = checklistOnClick(ok = 3 == 0, destino = destino)
        assertEquals(0, llamadas) // construir la lambda no la dispara sola
        onClick?.invoke()
        assertEquals(1, llamadas)
    }

    @Test
    fun `caja - navega solo cuando queda una caja sin cerrar`() {
        var llamadas = 0
        val destino: () -> Unit = { llamadas++ }

        // openDrawers vacío -> ok = true -> "Sin cajas abiertas": no navega
        assertNull(checklistOnClick(ok = emptyList<Int>().isEmpty(), destino = destino))
        assertEquals(0, llamadas)

        // openDrawers con una caja -> ok = false -> SÍ navega, a Caja
        val onClick = checklistOnClick(ok = listOf(1).isEmpty(), destino = destino)
        onClick?.invoke()
        assertEquals(1, llamadas)
    }

    @Test
    fun `checador - navega solo cuando alguien tiene la entrada marcada`() {
        var llamadas = 0
        val destino: () -> Unit = { llamadas++ }

        // clockedInStaff vacío -> ok = true -> "Nadie con entrada marcada": no navega
        assertNull(checklistOnClick(ok = emptyList<Int>().isEmpty(), destino = destino))
        assertEquals(0, llamadas)

        // clockedInStaff con una persona -> ok = false -> SÍ navega, al checador
        val onClick = checklistOnClick(ok = listOf(1).isEmpty(), destino = destino)
        onClick?.invoke()
        assertEquals(1, llamadas)
    }

    /**
     * 🔴 P3 #8 de la auditoria de apps: un renglon EN ADVERTENCIA pero SIN destino cableado no
     * puede quedar `clickable` y muerto.
     *
     * Los tres destinos tenian default `= {}`, asi que un llamador que no los pasara producia una
     * lambda vacia: la fila se volvia clickable y tocarla no hacia nada — el boton muerto que
     * describe la memoria del workspace `callback-con-default-se-vuelve-boton-muerto`. Con el
     * default en `null`, la fila se queda QUIETA, que es lo honesto.
     */
    @Test
    fun `un renglon en advertencia SIN destino cableado no es clickable`() {
        assertNull(checklistOnClick(ok = false, destino = null))
    }

    /** Y un renglon resuelto sin destino tampoco, por las dos razones a la vez. */
    @Test
    fun `un renglon resuelto sin destino tampoco es clickable`() {
        assertNull(checklistOnClick(ok = true, destino = null))
    }

    @Test
    fun `un renglon resuelto no navega, exactamente como antes de este cambio`() {
        var llamadas = 0
        val resultado = checklistOnClick(ok = true, destino = { llamadas++ })

        assertNull(resultado)
        assertEquals(0, llamadas)
    }
}
