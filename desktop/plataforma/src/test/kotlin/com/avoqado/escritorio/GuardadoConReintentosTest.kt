package com.avoqado.escritorio

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ronda 2 de C1: un guardado que falla se reintenta con espera creciente (un antivirus suele soltar el archivo en menos
 * de 2 s) y, si aun así no queda, la memoria vuelve a lo anterior y se REPORTA a [FallasDeGuardado] para que la ventana
 * lo diga. Sin valores en el aviso ni en la bitácora.
 */
class GuardadoConReintentosTest {
    private val carpeta: Path = Files.createTempDirectory("Avoqado POS reintentos ñ")
    private val archivo: Path = carpeta.resolve("shared_prefs/avoqado_secure_prefs.json")
    private val esperas = mutableListOf<Long>()
    private var fallasPendientes = 0

    /** Falla mientras [fallasPendientes] > 0 (como un antivirus que sostiene el archivo); después escribe de verdad. */
    private val escritor: (Path, ByteArray) -> Unit = { ruta, bytes ->
        if (fallasPendientes > 0) { fallasPendientes--; throw AccessDeniedException(ruta.toString()) }
        escribirAtomico(ruta, bytes)
    }

    private fun abrir(ruta: Path = archivo, esperar: (Long) -> Unit = { esperas += it }) =
        PreferenciasEnArchivo(ruta, null, escritor, esperar)

    @BeforeTest @AfterTest fun limpiar() = FallasDeGuardado.entendido()

    @Test fun `un bloqueo pasajero - falla 2 veces y a la tercera guarda, sin aviso`() {
        val p = abrir()
        fallasPendientes = 2
        assertTrue(p.edit().putString("pendingDrawerOps.v1", "retiro-1").commit())
        assertEquals(listOf(50L, 100L), esperas)
        assertEquals("retiro-1", PreferenciasEnArchivo(archivo).getString("pendingDrawerOps.v1", null))
        assertEquals(emptyList(), FallasDeGuardado.fallas.value, "un reintento que sí guardó no se le avisa al cajero")
    }

    @Test fun `si sigue fallando - espera creciente hasta unos 2 s, restaura la memoria y lo reporta sin valores`() {
        val p = abrir()
        assertTrue(p.edit().putString("pendingDrawerOps.v1", "retiro-1").commit())
        fallasPendientes = Int.MAX_VALUE
        val (guardo, bitacora) = bitacoraDe { p.edit().putString("pendingDrawerOps.v1", "retiro-1,retiro-2-SECRETO").commit() }
        assertFalse(guardo)
        assertEquals(listOf(50L, 100L, 200L, 400L, 800L), esperas)
        assertTrue(esperas.sum() <= 2_000, "esperó ${esperas.sum()} ms")
        assertEquals("retiro-1", p.getString("pendingDrawerOps.v1", null), "la memoria volvió a lo que hay en disco")
        val falla = FallasDeGuardado.fallas.value.single()
        assertEquals("avoqado_secure_prefs", falla.archivo)
        assertFalse(falla.despuesSiGuardo)
        assertFalse("SECRETO" in falla.toString() || carpeta.toString() in falla.toString(), "$falla")
        assertTrue("No se pudo guardar" in bitacora && "6 intentos" in bitacora && "AccessDeniedException" in bitacora, bitacora)
        assertFalse("SECRETO" in bitacora, bitacora)
    }

    @Test fun `el siguiente guardado bueno del MISMO archivo lo marca, pero el aviso no se va solo`() {
        val p = abrir()
        val otro = abrir(carpeta.resolve("shared_prefs/avoqado_kiosk.json"))
        fallasPendientes = Int.MAX_VALUE
        assertFalse(p.edit().putString("a", "1").commit())
        fallasPendientes = 0
        assertTrue(otro.edit().putString("b", "2").commit())
        assertFalse(FallasDeGuardado.fallas.value.single().despuesSiGuardo, "otro archivo no dice nada de éste")
        assertTrue(p.edit().putString("a", "1").commit())
        val falla = FallasDeGuardado.fallas.value.single()
        assertEquals("avoqado_secure_prefs", falla.archivo)
        assertTrue(falla.despuesSiGuardo)
    }

    @Test fun `un contenido que no se puede escribir no se reintenta, pero se reporta`() {
        val p = abrir()
        assertFalse(p.edit().putString("cliente", "emoji roto \uD83D").commit())   // surrogate suelto: no lo arregla esperar
        assertEquals(emptyList(), esperas)
        assertEquals("avoqado_secure_prefs", FallasDeGuardado.fallas.value.single().archivo)
    }

    @Test fun `una interrupcion corta los reintentos, conserva la marca y restaura`() {
        val p = abrir(esperar = { throw InterruptedException() })
        fallasPendientes = Int.MAX_VALUE
        try {
            assertFalse(p.edit().putString("a", "1").commit())
            assertTrue(Thread.currentThread().isInterrupted, "la interrupción no se traga")
        } finally {
            Thread.interrupted()
        }
        assertEquals(null, p.getString("a", null))
        assertEquals(1, FallasDeGuardado.fallas.value.size)
    }

    @Test fun `el nombre que se reporta es el logico, sea el claro o el cifrado`() {
        fallasPendientes = Int.MAX_VALUE
        abrir(carpeta.resolve("shared_prefs/avoqado_secure_prefs.cifrado")).edit().putString("a", "1").commit()
        assertEquals(listOf("avoqado_secure_prefs"), FallasDeGuardado.fallas.value.map { it.archivo })
    }
}
