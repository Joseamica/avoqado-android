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

    @Test fun `una base v20 con un cobro de la maquina llega a v21 con su respuesta en NULL y el cobro intacto`() {
        val carpeta = Files.createTempDirectory("migraciones")
        try {
            val ayudante = ayudanteDeMigraciones(carpeta.resolve("base"))
            ayudante.createDatabase(20).use { v20 ->
                v20.execSQL(
                    "INSERT INTO cobros_en_maquina (referenceCC, venueId, llaveDeVenta, pedidoJson, saleCents, tipCents, dueCents, " +
                        "creadoEn, estado, creditCents, changeDispensedCents, cierreConocido, subido, dudaSubida, ticketImpreso, " +
                        "mensajesCrudos, cancelPedido) VALUES ('AQ000000000001', 'venue-1', 'venta-1', '{}', 18000, 750, 18750, 1, " +
                        "'GUARDADO', 5000, 0, 1, 0, 0, 0, 'crudo', '2')",
                )
            }
            ayudante.runMigrationsAndValidate(21, migracionesDeAndroid().map { it.paraRoom() }).use { v21 ->
                assertEquals(1L, v21.leerLargo("SELECT COUNT(*) FROM cobros_en_maquina WHERE cancelPedido = '2' AND respuestaServidor IS NULL"))
            }
        } finally { carpeta.toFile().deleteRecursively() }
    }

    @Test fun `una base v21 con un cobro pagado llega a v22 con lo que se debe calculado y el cobro intacto`() {
        val carpeta = Files.createTempDirectory("migraciones")
        try {
            val ayudante = ayudanteDeMigraciones(carpeta.resolve("base"))
            ayudante.createDatabase(21).use { v21 ->
                v21.execSQL(
                    "INSERT INTO cobros_en_maquina (referenceCC, venueId, llaveDeVenta, pedidoJson, saleCents, tipCents, dueCents, " +
                        "creadoEn, estado, creditCents, changeDispensedCents, cierreConocido, subido, dudaSubida, ticketImpreso, " +
                        "mensajesCrudos) VALUES ('AQ000000000001', 'venue-1', 'venta-1', '{}', 8000, 0, 8000, 1, " +
                        "'PAGADO', 10000, 1500, 1, 0, 0, 0, 'crudo')",
                )
            }
            ayudante.runMigrationsAndValidate(22, migracionesDeAndroid().map { it.paraRoom() }).use { v22 ->
                assertEquals(500L, v22.leerLargo("SELECT aDeberCents FROM cobros_en_maquina WHERE estado = 'PAGADO' AND bindingId IS NULL"))
            }
        } finally { carpeta.toFile().deleteRecursively() }
    }

    @Test fun `Codex r3 una base v22 con una duda llega a v23 con las huellas vacias, sin comanda y la duda intacta`() {
        val carpeta = Files.createTempDirectory("migraciones")
        try {
            val ayudante = ayudanteDeMigraciones(carpeta.resolve("base"))
            ayudante.createDatabase(22).use { v22 ->
                v22.execSQL(
                    "INSERT INTO cobros_en_maquina (referenceCC, venueId, llaveDeVenta, pedidoJson, saleCents, tipCents, dueCents, " +
                        "creadoEn, estado, creditCents, changeDispensedCents, cierreConocido, subido, dudaSubida, ticketImpreso, " +
                        "mensajesCrudos, aDeberCents) VALUES ('AQ000000000001', 'venue-1', 'venta-1', '{}', 8000, 0, 8000, 1, " +
                        "'ESPERANDO', 5000, 0, 0, 0, 1, 0, 'g1 crudo', 0)",
                )
            }
            ayudante.runMigrationsAndValidate(23, migracionesDeAndroid().map { it.paraRoom() }).use { v23 ->
                assertEquals(1L, v23.leerLargo("SELECT COUNT(*) FROM cobros_en_maquina WHERE huellasDelDiario = '' AND comandaJson IS NULL AND creditCents = 5000"))
            }
        } finally { carpeta.toFile().deleteRecursively() }
    }

    @Test fun `Codex POS r7 una base v23 con un cobro pagado llega a v24 con su cierre fisico y sin contradiccion`() {
        val carpeta = Files.createTempDirectory("migraciones")
        try {
            val ayudante = ayudanteDeMigraciones(carpeta.resolve("base"))
            ayudante.createDatabase(23).use { v23 ->
                v23.execSQL(
                    "INSERT INTO cobros_en_maquina (referenceCC, venueId, llaveDeVenta, pedidoJson, saleCents, tipCents, dueCents, " +
                        "creadoEn, estado, creditCents, changeDispensedCents, cierreConocido, resueltoPor, subido, dudaSubida, ticketImpreso, " +
                        "mensajesCrudos, aDeberCents, huellasDelDiario) VALUES ('AQ000000000001', 'venue-1', 'venta-1', '{}', 8000, 0, 8000, 1, " +
                        "'PAGADO', 10000, 1500, 1, 'MAQUINA', 1, 0, 0, 'g1 crudo', 500, '')",
                )
            }
            ayudante.runMigrationsAndValidate(24, migracionesDeAndroid().map { it.paraRoom() }).use { v24 ->
                assertEquals(1L, v24.leerLargo(
                    "SELECT COUNT(*) FROM cobros_en_maquina WHERE creditoFisicoCents = 10000 AND cierreFisico = 'PAGADO' " +
                        "AND cambioFisicoCents = 1500 AND maquinaSeContradijo = 0",
                ))
            }
        } finally { carpeta.toFile().deleteRecursively() }
    }

    @Test fun `Codex POS r7 el DAO real - un segundo cierre distinto con la fila ya PAGADA se guarda y queda contradicho para siempre`() = runBlocking {
        val carpeta = Files.createTempDirectory("cobros-en-maquina")
        val base = DatabaseModule.provideDatabase(ContextoDeEscritorio(carpeta))
        try {
            val dao = base.cobrosEnMaquinaDao()
            dao.insertar(CobroEnMaquinaEntity("AQ000000000001", "venue-1", "venta-1", "{}", 8_000, 0, 8_000, null, null, creadoEn = 1, estado = EstadoDelIntento.EN_CURSO))
            fun cierre(cambio: Double) = """{"comando":"status","cobrando":false,"Status":"Pago Completado","referenceCC":"AQ000000000001","Credit":100,"Due":80,"changeDispensed":$cambio}"""
            assertTrue(dao.anotarMensaje("AQ000000000001", 1, cierre(0.0), 10_000, 8_000))
            assertEquals(1, dao.cerrar("AQ000000000001", EstadoDelIntento.PAGADO, 10_000, 0, 99, aDeber = 2_000))
            assertTrue(dao.anotarMensaje("AQ000000000001", 1, cierre(20.0), 10_000, 8_000), "ya PAGADA: el segundo cierre no se tira")
            val f = dao.porReferencia("AQ000000000001")!!
            assertEquals(listOf<Any?>(10_000, "PAGADO", 0, true), listOf(f.creditoFisicoCents, f.cierreFisico, f.cambioFisicoCents, f.maquinaSeContradijo))
            // Codex código POS r8 #4: el SERVIDOR todavía no tenía ese cierre: deja de ser desenlace y sube como duda (lo registrado no cambia).
            assertEquals(listOf<Any?>(EstadoDelIntento.ESPERANDO, 10_000, 0), listOf(f.estado, f.creditCents, f.changeDispensedCents))
            assertEquals(listOf("AQ000000000001"), dao.porSubir().map { it.referenceCC })
        } finally {
            base.close()
            carpeta.toFile().deleteRecursively()
        }
    }

    @Test fun `Codex r3 el DAO real - una duda propia frena salir y lo resuelto no`() = runBlocking {
        val carpeta = Files.createTempDirectory("cobros-en-maquina")
        val base = DatabaseModule.provideDatabase(ContextoDeEscritorio(carpeta))
        try {
            val dao = base.cobrosEnMaquinaDao()
            val duda = CobroEnMaquinaEntity(
                "AQ000000000001", "venue-1", "venta-1", "{}", 18_000, 750, 18_750, null, null, creadoEn = 1, estado = EstadoDelIntento.ESPERANDO,
                dudaSubida = true,
            )
            dao.insertar(duda)
            assertEquals(1, dao.pendientes("venue-1"), "una duda propia ya subida sigue frenando salir (r3 #2)")
            assertEquals(1, dao.resolver("AQ000000000001", "GERENTE", 0, 0, null, """{"outcome":"CANCELADO","creditCents":0}""", "CANCELADO", "", 0))
            assertEquals(0, dao.pendientes(null), "lo que el SERVIDOR resolvió no frena")
            assertEquals(0, dao.bloqueanLaVenta("venta-1"), "«Cancelado, \$0» sin nada que lo contradiga: la tarjeta sí")
            assertTrue(dao.anotarMensaje("AQ000000000001", 1, "tarde", 10_000, 18_750), "lo que llega después de resolver es evidencia (r3 #1)")
            assertEquals(1, dao.bloqueanLaVenta("venta-1"), "y frena la venta")
            assertEquals(1, dao.pendientes(null), "Codex código POS r4 #4: y salir, mientras sólo está en esta PC")
            val conEvidencia = dao.porReferencia("AQ000000000001")!!
            assertEquals(1, dao.marcarEvidenciaEntregada("AQ000000000001", conEvidencia.mensajesCrudos, conEvidencia.creditCents))
            assertEquals(0, dao.pendientes(null), "entregada, ya no frena salir")
            assertEquals(1, dao.bloqueanLaVenta("venta-1"), "Codex código POS r4 #1: la máquina contó \$100 que la resolución no dice")
        } finally {
            base.close()
            carpeta.toFile().deleteRecursively()
        }
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

            // Codex código r2, bloque A: el mensaje va al diario de SU folio ANTES de decidir; repetido no cambia nada.
            assertTrue(dao.anotarMensaje("AQ000000000001", 1, "crudo", 20_000, 18_750))
            assertFalse(dao.anotarMensaje("AQ000000000001", 2, "crudo", 20_000, 18_750))
            assertTrue(dao.anotarMensaje("AQ000000000003", 1, "otro", 9_000, 100), "es evidencia de su folio…")
            assertEquals(0, dao.porReferencia("AQ000000000003")!!.creditCents, "…pero el dinero de otro Due no se le atribuye")
            assertEquals(1, dao.cerrar("AQ000000000001", EstadoDelIntento.PAGADO, 20_000, 1_250, 99))
            assertEquals(0, dao.cerrar("AQ000000000001", EstadoDelIntento.PAGADO, 1, 1, 100))
            assertFalse(dao.anotarMensaje("AQ000000000001", 1, "tarde", null, null), "lo final ya no cambia")
            val pagado = dao.porReferencia("AQ000000000001")!!
            assertEquals(listOf<Any?>(EstadoDelIntento.PAGADO, 20_000, 1_250, 99L, true, false, "MAQUINA", "g1 crudo"),
                listOf(pagado.estado, pagado.creditCents, pagado.changeDispensedCents, pagado.cierreEn, pagado.cierreConocido,
                    pagado.subido, pagado.resueltoPor, pagado.mensajesCrudos))
            assertFalse(dao.abrirIntento(intento("AQ000000000004")), "una venta pagada no se vuelve a cobrar")
            assertEquals(listOf(pagado), dao.porSubir())

            assertEquals(1, dao.marcarSubido("AQ000000000001", null, """{"outcome":"PAGADO","excedenteCents":500}""", pagado.mensajesCrudos, pagado.creditCents))
            assertEquals("""{"outcome":"PAGADO","excedenteCents":500}""", dao.porReferencia("AQ000000000001")!!.respuestaServidor)
            // Codex código r2 H9: el papel se reclama una sola vez (un ganador) y se marca con el resultado real.
            assertEquals(1, dao.reclamarTicket("AQ000000000001"))
            assertEquals(0, dao.reclamarTicket("AQ000000000001"), "el segundo no gana")
            assertEquals(1, dao.terminarTicket("AQ000000000001", 1))
            assertEquals(1, dao.porReferencia("AQ000000000001")!!.ticketImpreso)
        } finally {
            base.close()
            carpeta.toFile().deleteRecursively()
        }
    }
}
