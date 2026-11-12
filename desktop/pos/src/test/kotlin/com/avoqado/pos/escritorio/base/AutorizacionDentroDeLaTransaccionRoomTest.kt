package com.avoqado.pos.escritorio.base

import androidx.room.withTransaction
import com.avoqado.escritorio.ContextoDeEscritorio
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.di.DatabaseModule
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.SalidaDelApartado
import com.avoqado.pos.payment.data.model.CreateOrderRequest
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * D12 · familia A (Codex r4 #1, R1): la autorización de OTRA caja vale 20 s; se comprueba DENTRO de la transacción de Room que guarda la
 * reserva `EN_VUELO`, ya con el escritor y justo antes del INSERT. Antes se comprobaba en el repositorio y DESPUÉS el DAO esperaba la cola
 * de Room (otra escritura tenía el escritor) y leía la fila: una caja que esperaba ahí más de 20 s guardaba su efectivo con el permiso
 * vencido, aunque en la dueña ya hubieran declarado «No se cobró» y cobrado. Room de verdad (la misma configuración que la app), no un DAO
 * simulado.
 */
class AutorizacionDentroDeLaTransaccionRoomTest {
    private val venta = CreateOrderRequest(items = emptyList(), subtotal = 10_000, total = 10_000, paymentMethod = "CASH")

    @Test fun laReservaQueEsperaAlEscritorNoSeGuardaSiSuAutorizacionVencioMientras() = runBlocking {
        withContext(Dispatchers.IO) {
            Base().use { b ->
                val entro = CompletableDeferred<Unit>()
                val liberar = CompletableDeferred<Unit>()
                // Otra escritura tiene el escritor de Room: la transacción del INSERT de B espera su turno en la cola.
                val holder = launch { b.db.withTransaction { entro.complete(Unit); liberar.await() } }
                var reserva: kotlinx.coroutines.Deferred<Result<Unit>>? = null
                try {
                    withTimeout(10_000) { entro.await() }
                    // El reloj monotónico de B: la dueña confirmó en 0; vale 20 s (RegistroDeCuentas.AUTORIZACION_MS).
                    val reloj = java.util.concurrent.atomic.AtomicLong(0L)
                    val consultas = ConcurrentLinkedQueue<Boolean>()
                    val vencida = IllegalStateException("la autorización de la caja que abrió la cuenta venció")
                    val antesDeGuardar = {
                        val vigente = reloj.get() < 20_000
                        consultas += vigente
                        if (!vigente) throw vencida
                    }
                    reserva = async {
                        runCatching {
                            b.cash.reservarCobro(venta, id(91), 10_000, 0, null, orderId = id(80), idempotencyKey = id(1), venueId = id(90),
                                antesDeGuardar = antesDeGuardar)
                        }
                    }
                    // B ya leyó lo suyo y espera al escritor (si su comprobación corriera ANTES de esa espera, ya habría dicho «vigente»).
                    withTimeoutOrNull(500) { while (consultas.isEmpty()) delay(10) }
                    delay(200)
                    reloj.set(2 * 60_000L) // pasaron los 2 min: en la dueña declararon «No se cobró» y cobraron
                    liberar.complete(Unit)
                    val r = withTimeout(10_000) { holder.join(); reserva.await() }
                    val fila = b.db.pendingPaymentDao().findForVenue(id(90), id(1))

                    laMisma(vencida, r.exceptionOrNull())
                    assertNull(fila, "B no guardó su efectivo con aquella autorización")
                    assertEquals(listOf(false), consultas.toList(), "se comprobó UNA vez, ya con el escritor y después de la espera")
                } finally {
                    liberar.complete(Unit)
                    withContext(NonCancellable) {
                        reserva?.cancelAndJoin()
                        holder.cancelAndJoin()
                    }
                }
            }
        }
    }

    /** Con apartados anteriores de la misma venta, se borran en la MISMA transacción: si la autorización venció, no se borra nada. */
    @Test fun conApartadosAnterioresUnaAutorizacionVencidaNoDejaNadaAMedias() = runBlocking {
        withContext(Dispatchers.IO) {
            Base().use { b ->
                b.cash.reservarCobro(venta, id(91), 10_000, 0, null, orderId = id(80), idempotencyKey = id(1), venueId = id(90))
                val antes = assertNotNull(b.db.pendingPaymentDao().findForVenue(id(90), id(1)))
                val vencida = IllegalStateException("vencida")
                val r = runCatching {
                    b.cash.reservarCobro(venta, id(91), 10_000, 0, null, orderId = id(80), idempotencyKey = id(2), venueId = id(90),
                        enLugarDe = listOf(id(1)), antesDeGuardar = { throw vencida })
                }
                val despues = b.db.pendingPaymentDao().findForVenue(id(90), id(1))
                val nueva = b.db.pendingPaymentDao().findForVenue(id(90), id(2))
                laMisma(vencida, r.exceptionOrNull())
                assertEquals(antes, despues, "el apartado anterior sigue, intacto")
                assertNull(nueva)
            }
        }
    }

    /**
     * La excepción de quien llama sale de la transacción SIN envolver (el ViewModel la reconoce por su tipo para volver a comprometer). Al
     * cruzar corrutinas, kotlinx puede entregar una copia del mismo tipo con la original de causa (recuperación de la pila).
     */
    private fun laMisma(esperada: Throwable, real: Throwable?) {
        assertNotNull(real, "no se guardó: tenía que salir la excepción")
        assertEquals(esperada::class, real::class, "mismo tipo, sin envolver: $real")
        assertSame(esperada, generateSequence(real) { it.cause }.firstOrNull { it === esperada }, "es la misma (o su copia): $real")
    }

    private class Base : AutoCloseable {
        val carpeta = Files.createTempDirectory("r1-dentro-de-la-tx-")
        val contexto = ContextoDeEscritorio(carpeta)
        val db = DatabaseModule.provideDatabase(contexto)
        private val storage = SecureStorage(contexto).apply { venueId = id(90); userId = id(91) }
        val cash = CashPaymentRepository(storage, db.pendingPaymentDao(), SalidaDelApartado(db.pendingPaymentDao(), db.cashDrawerDao(), storage, db))
        override fun close() {
            db.close()
            carpeta.toFile().deleteRecursively()
        }
    }

    private companion object { fun id(n: Int) = "c" + n.toString().padStart(24, '0') }
}
