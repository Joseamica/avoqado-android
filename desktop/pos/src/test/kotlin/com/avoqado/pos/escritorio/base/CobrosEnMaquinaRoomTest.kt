package com.avoqado.pos.escritorio.base

import androidx.sqlite.execSQL
import com.avoqado.escritorio.ContextoDeEscritorio
import com.avoqado.pos.core.di.DatabaseModule
import com.avoqado.pos.maquina.CobroEnMaquinaEntity
import com.avoqado.pos.maquina.EstadoDelIntento
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * v20 (máquina de efectivo, fase 1a) con Room de verdad, el mismo de WINDOWS: la migración la valida Room contra el
 * esquema que exporta Android, y el `@Transaction` del DAO corre en la base real (las pruebas de `app` lo corren sobre
 * SQLite con las mismas constantes).
 */
class CobrosEnMaquinaRoomTest {

    @Test fun `una base v19 llega a v20 con la tabla de la maquina vacia y sus cobros sin red intactos`() {
        val carpeta = Files.createTempDirectory("migraciones")
        try {
            val ayudante = ayudanteDeMigraciones(carpeta.resolve("base"))
            val antes = ayudante.createDatabase(19).use { v19 ->
                v19.execSQL(SIEMBRA_V18)
                v19.volcar("pending_payments")
            }
            ayudante.runMigrationsAndValidate(20, migracionesDeAndroid().map { it.paraRoom() }).use { v20 ->
                assertEquals(antes, v20.volcar("pending_payments"), "Los cobros sin red de antes no cambian")
                assertEquals(0L, v20.leerLargo("SELECT COUNT(*) FROM cobros_en_maquina"))
            }
        } finally { carpeta.toFile().deleteRecursively() }
    }

    @Test fun `el DAO real deja un solo intento abierto por venta y el cierre se escribe una sola vez`() = runBlocking {
        val carpeta = Files.createTempDirectory("cobros-en-maquina")
        val base = DatabaseModule.provideDatabase(ContextoDeEscritorio(carpeta))
        try {
            val dao = base.cobrosEnMaquinaDao()
            fun intento(ref: String, llave: String = "venta-1") = CobroEnMaquinaEntity(
                ref, "venue-1", llave, "{}", 18_000, 750, 18_750, null, null, creadoEn = 1, estado = EstadoDelIntento.EN_CURSO,
            )
            assertTrue(dao.abrirIntento(intento("AQ000000000001")))
            assertFalse(dao.abrirIntento(intento("AQ000000000002")))
            assertTrue(dao.abrirIntento(intento("AQ000000000003", llave = "venta-2")))

            assertEquals(1, dao.cerrar("AQ000000000001", EstadoDelIntento.PAGADO, 20_000, 1_250, 99, "crudo"))
            assertEquals(0, dao.cerrar("AQ000000000001", EstadoDelIntento.PAGADO, 1, 1, 100, "otro"))
            val pagado = dao.porReferencia("AQ000000000001")!!
            assertEquals(listOf<Any?>(EstadoDelIntento.PAGADO, 20_000, 1_250, 99L, true, false, "MAQUINA", "crudo"),
                listOf(pagado.estado, pagado.creditCents, pagado.changeDispensedCents, pagado.cierreEn, pagado.cierreConocido,
                    pagado.subido, pagado.resueltoPor, pagado.mensajesCrudos))
            assertFalse(dao.abrirIntento(intento("AQ000000000004")), "una venta pagada no se vuelve a cobrar")
            assertEquals(listOf(pagado), dao.porSubir())
        } finally {
            base.close()
            carpeta.toFile().deleteRecursively()
        }
    }
}
