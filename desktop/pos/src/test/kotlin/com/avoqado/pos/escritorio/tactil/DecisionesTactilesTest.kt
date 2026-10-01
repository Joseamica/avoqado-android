package com.avoqado.pos.escritorio.tactil

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DecisionesTactilesTest {
    // --- instalar y decodificar ---

    @Test fun `sólo se instala en Windows y se puede apagar`() {
        assertNull(motivoParaNoInstalar("Windows 11", null))
        assertNull(motivoParaNoInstalar("Windows 10", "nativo"))
        assertEquals("no es Windows", motivoParaNoInstalar("Mac OS X", null))
        assertEquals("no es Windows", motivoParaNoInstalar("Linux", null))
        assertEquals("apagado con -Davoqado.toque=mouse", motivoParaNoInstalar("Windows 11", "mouse"))
    }

    @Test fun `el id y el contacto salen del wParam`() {
        assertEquals(5, idDePuntero(0x0001_0005L))
        assertEquals(0xFFFF, idDePuntero(0xABCD_FFFFL))
        assertTrue(enContactoDe(0x0004_0005L))
        assertFalse(enContactoDe(0x0002_0005L), "en rango (flotando) no es en contacto")
        assertFalse(enContactoDe(0x0000_0004L), "la bandera va en la palabra ALTA, no en el id")
    }

    @Test fun `cada mensaje de un contacto es una fase, con cancelaciones`() {
        assertEquals(FaseDeToque.BAJA, faseDe(WM_POINTERDOWN, POINTER_FLAG_INCONTACT))
        assertEquals(FaseDeToque.MUEVE, faseDe(WM_POINTERUPDATE, POINTER_FLAG_INCONTACT))
        assertNull(faseDe(WM_POINTERUPDATE, 0), "un update sin contacto (flotando) no es arrastre")
        assertEquals(FaseDeToque.CANCELA, faseDe(WM_POINTERUPDATE, POINTER_FLAG_INCONTACT or POINTER_FLAG_CANCELED))
        assertEquals(FaseDeToque.SUBE, faseDe(WM_POINTERUP, 0))
        assertEquals(FaseDeToque.CANCELA, faseDe(WM_POINTERUP, POINTER_FLAG_CANCELED))
        assertEquals(FaseDeToque.CANCELA, faseDe(WM_POINTERCAPTURECHANGED, 0))
        assertNull(faseDe(WM_POINTERENTER, 0))
        assertNull(faseDe(WM_POINTERLEAVE, 0))
    }

    @Test fun `sin la información de Windows, todo lo del contacto es cancelar`() {
        listOf(WM_POINTERDOWN, WM_POINTERUPDATE, WM_POINTERUP, WM_POINTERCAPTURECHANGED).forEach {
            assertEquals(FaseDeToque.CANCELA, faseDe(it, null), "mensaje 0x${it.toString(16)}")
        }
        assertNull(faseDe(WM_POINTERENTER, null))
        assertNull(faseDe(WM_POINTERLEAVE, null))
    }

    @Test fun `activar la ventana y el mouse no son de un contacto`() {
        assertFalse(esDeUnContacto(WM_POINTERACTIVATE))
        assertFalse(esDeUnContacto(0x0201 /* WM_LBUTTONDOWN */))
        assertTrue(listOf(WM_POINTERENTER, WM_POINTERDOWN, WM_POINTERUPDATE, WM_POINTERUP, WM_POINTERLEAVE, WM_POINTERCAPTURECHANGED).all(::esDeUnContacto))
    }

    // --- contactos: decididos una vez, completos o nada ---

    private val dedo = { true }
    private val noDedo = { false }
    private fun ContactosInterceptados.r(m: Int, id: Int, enContacto: Boolean = m == WM_POINTERDOWN || m == WM_POINTERUPDATE, tactil: () -> Boolean = dedo, tomar: Boolean = true) =
        registrar(m, id, enContacto, tactil, tomar)

    @Test fun `un contacto de dedo se consume completo, de ENTER a LEAVE`() {
        val c = ContactosInterceptados()
        val secuencia = listOf(WM_POINTERENTER, WM_POINTERDOWN, WM_POINTERUPDATE, WM_POINTERUP, WM_POINTERLEAVE)
        assertTrue(secuencia.all { c.r(it, 7) })
        assertTrue(c.sinActivos)
    }

    @Test fun `el mouse y la pluma pasan intactos`() {
        val c = ContactosInterceptados()
        assertFalse(c.r(WM_POINTERDOWN, 1, tactil = noDedo))
        assertFalse(c.r(WM_POINTERUPDATE, 1, tactil = noDedo))
        assertTrue(c.sinActivos)
    }

    @Test fun `activar la ventana pasa aunque el contacto sea nuestro`() {
        val c = ContactosInterceptados()
        c.r(WM_POINTERENTER, 7)
        assertFalse(c.r(WM_POINTERACTIVATE, 7))
    }

    @Test fun `apagado a media arrastrada, el contacto se termina de consumir y el siguiente ya no`() {
        val c = ContactosInterceptados()
        c.r(WM_POINTERDOWN, 7)
        assertTrue(c.r(WM_POINTERUPDATE, 7, tomar = false))
        assertTrue(c.r(WM_POINTERUP, 7, tactil = { error("no se consulta a Windows a media secuencia") }, tomar = false))
        assertTrue(c.r(WM_POINTERLEAVE, 7, tomar = false))
        assertFalse(c.r(WM_POINTERDOWN, 8, tomar = false))
    }

    @Test fun `lo que empezó sin el puente termina sin él`() {
        val c = ContactosInterceptados()
        assertFalse(c.r(WM_POINTERUPDATE, 9))
        assertFalse(c.r(WM_POINTERUP, 9))
        assertFalse(c.r(WM_POINTERLEAVE, 9))
    }

    @Test fun `perder la captura termina el contacto aunque no llegue UP ni LEAVE`() {
        val c = ContactosInterceptados()
        assertTrue(c.r(WM_POINTERDOWN, 4))
        assertTrue(c.r(WM_POINTERUPDATE, 4))
        assertTrue(c.r(WM_POINTERCAPTURECHANGED, 4, enContacto = true))
        assertTrue(c.sinActivos, "ya no hay contacto a medias: se puede restaurar")
        assertTrue(c.r(WM_POINTERLEAVE, 4), "si el LEAVE sí llega tarde, también es nuestro")
        assertFalse(c.r(WM_POINTERUPDATE, 4), "después del LEAVE ya no hay contacto")
    }

    @Test fun `un contacto rechazado sigue rechazado hasta que su secuencia termina`() {
        val c = ContactosInterceptados()
        assertFalse(c.r(WM_POINTERENTER, 6, tomar = false))
        assertFalse(c.r(WM_POINTERDOWN, 6, tomar = true), "un DOWN a media secuencia no la vuelve a decidir")
        assertFalse(c.r(WM_POINTERUPDATE, 6))
        assertFalse(c.r(WM_POINTERCAPTURECHANGED, 6), "el rechazo también termina al perder la captura")
        assertFalse(c.r(WM_POINTERLEAVE, 6))
        assertTrue(c.r(WM_POINTERDOWN, 6), "ya terminada, un contacto nuevo se decide de nuevo")
    }

    @Test fun `un ENTER a media secuencia no cambia la decisión`() {
        val c = ContactosInterceptados()
        c.r(WM_POINTERDOWN, 5)
        assertTrue(c.r(WM_POINTERENTER, 5, tomar = false), "sigue siendo nuestro")
        assertTrue(c.r(WM_POINTERUPDATE, 5, tomar = false))
    }

    @Test fun `un LEAVE con el dedo apoyado no termina la secuencia`() {
        val c = ContactosInterceptados()
        c.r(WM_POINTERDOWN, 5)
        assertTrue(c.r(WM_POINTERLEAVE, 5, enContacto = true))
        assertTrue(c.r(WM_POINTERUPDATE, 5), "el dedo salió de la ventana pero sigue siendo nuestro")
        assertFalse(c.sinActivos)
    }

    @Test fun `un LEAVE sin contacto termina una secuencia vigente aunque nunca haya bajado`() {
        val c = ContactosInterceptados()
        assertTrue(c.r(WM_POINTERENTER, 5))
        assertFalse(c.sinActivos)
        assertTrue(c.r(WM_POINTERLEAVE, 5, enContacto = false), "el LEAVE de lo tomado también es nuestro")
        assertTrue(c.sinActivos, "si no, el puente jamás podría restaurar la ventana")
        assertFalse(c.r(WM_POINTERENTER, 6, tomar = false))
        assertFalse(c.r(WM_POINTERLEAVE, 6, enContacto = false))
        assertTrue(c.r(WM_POINTERDOWN, 6), "el rechazo que flotaba se borró: un DOWN nuevo se decide de nuevo")
    }

    @Test fun `si preguntarle a Windows truena, el contacto queda rechazado completo`() {
        val c = ContactosInterceptados()
        assertFalse(c.r(WM_POINTERDOWN, 3, tactil = { error("GetPointerType truena") }))
        assertFalse(c.r(WM_POINTERUPDATE, 3))
        assertFalse(c.r(WM_POINTERUP, 3))
    }

    @Test fun `sin lugar nunca se tira una secuencia vigente ni se rechaza por eso`() {
        val c = ContactosInterceptados(capacidad = 2)
        assertTrue(c.r(WM_POINTERDOWN, 1))
        assertTrue(c.r(WM_POINTERDOWN, 2))
        assertTrue(c.r(WM_POINTERDOWN, 3), "no se rechaza por capacidad")
        assertTrue(listOf(1, 2, 3).all { c.r(WM_POINTERUPDATE, it) }, "las tres siguen siendo nuestras")
        listOf(1, 2).forEach { c.r(WM_POINTERUP, it) }
        assertTrue(c.r(WM_POINTERDOWN, 4))
        assertTrue(c.r(WM_POINTERUPDATE, 3), "para hacer lugar se tiraron terminadas, no la vigente")
    }

    // --- fallas y reloj ---

    @Test fun `tres fallas seguidas avisan una sola vez y un éxito reinicia la cuenta`() {
        var avisos = 0
        val f = ContadorDeFallas { avisos++ }
        f.fallo(RuntimeException()); f.fallo(RuntimeException()); f.exito()
        f.fallo(RuntimeException()); f.fallo(RuntimeException())
        assertEquals(0, avisos)
        f.fallo(RuntimeException())
        assertEquals(1, avisos)
        repeat(5) { f.fallo(RuntimeException()) }
        assertEquals(1, avisos)
    }

    @Test fun `el reloj sigue el tiempo de Windows y conserva la separación entre contactos`() {
        val r = RelojDePunteros()
        val t0 = r.tiempo(1_000)
        assertEquals(t0 + 16, r.tiempo(1_016))
        assertEquals(t0 + 16, r.tiempo(null), "sin información, el último tiempo")
        assertEquals(t0 + 48, r.tiempo(1_048))
    }

    @Test fun `el reloj cruza la vuelta de los 32 bits sin saltar`() {
        val r = RelojDePunteros()
        val t0 = r.tiempo(0xFFFF_FFF0.toInt())
        assertEquals(t0 + 0x20, r.tiempo(0x10))
    }

    @Test fun `un tiempo fuera de orden no retrocede ni salta 49 días`() {
        val r = RelojDePunteros()
        val t0 = r.tiempo(5_000)
        assertEquals(t0, r.tiempo(4_990))
        assertEquals(t0 + 10, r.tiempo(5_010))
    }

    @Test fun `tras un mes sin tocar, el reloj se re-ancla en vez de congelarse`() {
        val r = RelojDePunteros()
        val t0 = r.tiempo(1_000)
        val t1 = r.tiempo((1_000L + 2_592_000_000L).toInt())   // 30 días después (más de 2^31 ms)
        assertTrue(t1 > t0, "no retrocede")
        assertEquals(t1 + 16, r.tiempo((1_000L + 2_592_000_016L).toInt()), "y los toques siguientes vuelven a llevar su separación")
    }

    @Test fun `la firma de Windows distingue el mouse que generó un dedo`() {
        assertTrue(esMouseDelDedo(0xFF515780L))
        assertTrue(esMouseDelDedo(-0xAEA880L))   // 0xFF515780 extendido a 64 bits
        assertFalse(esMouseDelDedo(0L))
        assertFalse(esMouseDelDedo(0x12345678L))
        assertTrue(esDeMouse(0x0200))
        assertFalse(esDeMouse(0x0246))
    }
}
