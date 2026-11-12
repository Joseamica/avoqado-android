package com.avoqado.pos.orders

import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.orders.data.CopiaDeCuentas
import com.avoqado.pos.orders.data.EvidenciaDelDetalle
import com.avoqado.pos.orders.data.Lectura
import com.avoqado.pos.orders.data.RazonSinRed
import com.avoqado.pos.orders.data.razonParaNoCobrar
import com.avoqado.pos.orders.data.model.OrderSummary
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * D12 (Tarea 4): la copia en disco, por sucursal, de las cuentas abiertas de «Pedidos», con la razón de cada gris.
 * El «disco» de memoria imita `PayloadCache` (como `onlineorders/CopiaGuardadaDeFoliosTest`).
 */
class CopiaDeCuentasTest {
    private val disco = mutableMapOf<String, PayloadCache.Cached>()

    /** Lo que lanza la lectura del disco (`loadDurable`); `load`, como el real, se lo traga y da null. */
    private var falloAlLeer: Exception? = null
    private var falloAlGuardar = false
    private var guardados = 0
    private val cache = mockk<PayloadCache> {
        coEvery { load(any(), any()) } answers { if (falloAlLeer != null) null else disco["${firstArg<String>()}|${secondArg<String>()}"] }
        coEvery { loadDurable(any(), any()) } answers { falloAlLeer?.let { throw it }; disco["${firstArg<String>()}|${secondArg<String>()}"] }
        coEvery { saveDurable(any(), any(), any()) } answers {
            if (falloAlGuardar) throw IllegalStateException("disco lleno")
            guardados++
            disco["${firstArg<String>()}|${secondArg<String>()}"] = PayloadCache.Cached(thirdArg(), 0)
        }
    }
    private var reloj = 1_000_000_000L
    private val copia = CopiaDeCuentas(cache).also { it.ahora = { reloj }; it.io = kotlinx.coroutines.Dispatchers.Unconfined }
    private fun c(id: String, saldo: Int? = 6000, creada: String = "2026-11-10T18:00:00Z", status: String = "PENDING",
        pago: String = "PENDING", pagos: Int? = 0, origen: String = "AVOQADO_ANDROID", mesa: Boolean? = false, vales: Boolean? = false,
        tarjetas: Boolean = false, saldoDeRegalo: Int? = 0, reembolso: String = "NONE") = OrderSummary(id = id,
        saldoPendienteCents = saldo, pagosCompletados = pagos, createdAt = creada, status = status, paymentStatus = pago, source = origen,
        esDeMesa = mesa, esDeVales = vales, esCuentaDeTarjetas = tarjetas, cobrosConSaldo = saldoDeRegalo, refundState = reembolso)
    private fun ids(v: String = "v1") = runBlocking { copia.cuentas(v).map { it.cuenta.id }.toSet() }

    @Test fun `guarda las abiertas, cobrables o no, con su hora`() = runTest {
        copia.alLeer("v1", listOf(c("A"), c("regalo", saldoDeRegalo = 1), c("viejo", saldo = null)), Lectura.SUELTA)
        assertEquals(setOf("A", "regalo", "viejo"), ids())
        assertEquals(reloj, copia.cuenta("v1", "A")!!.vistaEn)
    }
    @Test fun `una lectura que la ve pagada, cancelada o reembolsada la quita`() = runTest {
        copia.alLeer("v1", listOf(c("A"), c("B"), c("C")), Lectura.SUELTA)
        copia.alLeer("v1", listOf(c("A", pago = "PAID", saldo = 0), c("B", status = "CANCELLED"), c("C", reembolso = "FULL")), Lectura.SUELTA)
        assertEquals(emptySet<String>(), ids())
    }
    @Test fun `la primera pagina incompleta quita sólo las ausentes de su ventana`() = runTest {
        copia.alLeer("v1", listOf(c("vieja", creada = "2026-11-10T10:00:00Z"), c("hueca", creada = "2026-11-10T18:30:00Z")), Lectura.SUELTA)
        copia.alLeer("v1", listOf(c("nueva", creada = "2026-11-10T19:00:00Z"), c("otra", creada = "2026-11-10T18:00:00Z")), Lectura.PRIMERA_PAGINA)
        assertEquals(setOf("vieja", "nueva", "otra"), ids())
    }
    @Test fun `una lista completa VACIA quita todas`() = runTest {
        copia.alLeer("v1", listOf(c("A")), Lectura.SUELTA)
        copia.alLeer("v1", emptyList(), Lectura.LISTA_COMPLETA)
        assertEquals(emptySet<String>(), ids())
    }
    @Test fun `una lista completa corta sin la cuenta vieja la quita aunque sea mas vieja que la pagina`() = runTest {
        copia.alLeer("v1", listOf(c("vieja", creada = "2026-11-10T10:00:00Z")), Lectura.SUELTA)
        copia.alLeer("v1", listOf(c("nueva", creada = "2026-11-10T19:00:00Z")), Lectura.LISTA_COMPLETA)
        assertEquals(setOf("nueva"), ids())
    }
    @Test fun `un empate en la frontera de la primera pagina se conserva`() = runTest {
        // Codex r3 H2: el SERVIDOR ordena sólo por createdAt (order.mobile.service.ts:687). 21 cuentas; la 20 y la 21 empatan.
        val t = "2026-11-10T12:00:00Z"
        copia.alLeer("v1", listOf(c("c21", creada = t)), Lectura.SUELTA)
        val pagina1 = (1..19).map { c("c$it", creada = "2026-11-10T13:%02d:00Z".format(it)) } + c("c20", creada = t)
        copia.alLeer("v1", pagina1, Lectura.PRIMERA_PAGINA)
        assertNotNull(copia.cuenta("v1", "c21"))
        assertNull(razonParaNoCobrar(copia.cuenta("v1", "c21")!!.cuenta))
    }
    @Test fun `una respuesta vieja retenida no resucita lo que una lista nueva retiro`() = runTest {
        copia.alLeer("v1", listOf(c("A")), Lectura.SUELTA)
        val vieja = copia.abrirLectura(); val nueva = copia.abrirLectura()
        copia.alLeer("v1", emptyList(), Lectura.LISTA_COMPLETA, nueva)
        copia.alLeer("v1", listOf(c("A")), Lectura.PRIMERA_PAGINA, vieja)
        assertNull(copia.cuenta("v1", "A"))
    }
    @Test fun `una lista vieja que llega tarde no mete una cuenta que una lista nueva ya no trajo`() = runTest {
        // Revisión T4 #1: Y nunca estuvo en la copia. R1 (vieja) la vio abierta; se cobró en otra caja; R2 (nueva) volvió primero sin ella.
        val vieja = copia.abrirLectura(); val nueva = copia.abrirLectura()
        copia.alLeer("v1", listOf(c("B")), Lectura.LISTA_COMPLETA, nueva)
        copia.alLeer("v1", listOf(c("Y"), c("B")), Lectura.PRIMERA_PAGINA, vieja)
        assertNull(copia.cuenta("v1", "Y"))
        assertNotNull(copia.cuenta("v1", "B"))
    }
    /**
     * Final fix (F1, Codex #2): lo que este aparato ya supo no se pierde porque el disco no se deje leer o cancelen a quien llamó.
     * Mientras no se lee, no hay nada que ofrecer; al poder leer, se aplica encima de la copia anterior (sin pisarla: revisión T4 #2).
     */
    @Test fun `si falla la lectura del disco no se ofrece nada y lo que llego se aplica sin perder la copia anterior`() = runTest {
        listOf(java.io.IOException("disco"), kotlinx.coroutines.CancellationException("cancelada")).forEach { falla ->
            disco.clear()
            copia.alLeer("v1", listOf(c("A"), c("C")), Lectura.SUELTA)
            falloAlLeer = falla
            runCatching { copia.alLeer("v1", listOf(c("B")), Lectura.SUELTA) }
            runCatching { copia.alLeerDetalle("v1", "A", EvidenciaDelDetalle.Vigente(true, 6000, 1, cobrosConSaldo = 0, esCuentaDeTarjetas = false)) }
            runCatching { copia.alLeerDetalle("v1", "C", EvidenciaDelDetalle.NoExiste) }
            assertNull("sin poder leer no se ofrece nada ($falla)", runCatching { copia.cuenta("v1", "A") }.getOrNull())
            falloAlLeer = null
            assertEquals(falla.toString(), setOf("A", "B"), ids())
            assertEquals(falla.toString(), RazonSinRed.CON_ABONO, razonParaNoCobrar(copia.cuenta("v1", "A")!!.cuenta))
            assertEquals("y ya llegó al disco", true, disco.values.single().json.contains("\"B\""))
        }
    }

    /**
     * Final fix (F1, Codex #2): A ve la cuenta de $100; otra caja cobra con saldo de regalo; A lee ese detalle y el disco está lleno.
     * La copia vieja NO vuelve a autorizar: lo que A ya sabe manda en memoria (también contra lo más viejo) hasta que se pueda guardar.
     */
    @Test fun `si falla el guardado lo nuevo manda en memoria y se guarda en la siguiente escritura`() = runTest {
        copia.alLeer("v1", listOf(c("A"), c("B")), Lectura.SUELTA)
        val entre = copia.abrirLectura(); val fallida = copia.abrirLectura()
        falloAlGuardar = true
        copia.alLeerDetalle("v1", "A", EvidenciaDelDetalle.Vigente(true, 6000, 0, cobrosConSaldo = 1, esCuentaDeTarjetas = false), fallida)
        copia.alLeerDetalle("v1", "B", EvidenciaDelDetalle.NoExiste) // no lanza
        assertEquals(RazonSinRed.TARJETAS_DE_REGALO, razonParaNoCobrar(copia.cuenta("v1", "A")!!.cuenta))
        assertNull(copia.cuenta("v1", "B"))
        copia.alLeer("v1", listOf(c("A"), c("B")), Lectura.SUELTA, entre) // más vieja que lo que ya se sabe: no las regresa a cobrables
        assertEquals(RazonSinRed.TARJETAS_DE_REGALO, razonParaNoCobrar(copia.cuenta("v1", "A")!!.cuenta))
        assertNull(copia.cuenta("v1", "B"))

        falloAlGuardar = false
        copia.alLeer("v1", listOf(c("N")), Lectura.SUELTA)
        val trasReiniciar = CopiaDeCuentas(cache).also { it.ahora = { reloj }; it.io = kotlinx.coroutines.Dispatchers.Unconfined }
        assertEquals(RazonSinRed.TARJETAS_DE_REGALO, razonParaNoCobrar(trasReiniciar.cuenta("v1", "A")!!.cuenta))
        assertNull(trasReiniciar.cuenta("v1", "B"))
        assertNotNull(trasReiniciar.cuenta("v1", "N"))
    }

    /** Final fix (F1, I-2): un cobro de ESTE aparato con red. Con saldo ⇒ «ya tiene un abono» (o de regalo); pagada ⇒ sale. */
    @Test fun `un cobro de este aparato con saldo deja abono y uno que la pago la retira`() = runTest {
        copia.alLeer("v1", listOf(c("parcial"), c("regalo"), c("pagada")), Lectura.SUELTA)
        copia.alLeerDetalle("v1", "parcial", EvidenciaDelDetalle.Abono(saldoCents = 2000, conSaldo = false))
        copia.alLeerDetalle("v1", "regalo", EvidenciaDelDetalle.Abono(saldoCents = null, conSaldo = true))
        copia.alLeerDetalle("v1", "pagada", EvidenciaDelDetalle.NoExiste)
        copia.alLeerDetalle("v1", "ajena", EvidenciaDelDetalle.Abono(saldoCents = 1000, conSaldo = false))
        val parcial = copia.cuenta("v1", "parcial")!!.cuenta
        assertEquals(RazonSinRed.CON_ABONO, razonParaNoCobrar(parcial))
        assertEquals(2000, parcial.saldoPendienteCents)
        assertEquals(RazonSinRed.TARJETAS_DE_REGALO, razonParaNoCobrar(copia.cuenta("v1", "regalo")!!.cuenta))
        assertNull(copia.cuenta("v1", "pagada"))
        assertNull("nunca agrega una cuenta", copia.cuenta("v1", "ajena"))
    }

    /** Final fix (F1, I-2): cada venta nueva avisa a la copia; si no cambia nada, no se reescribe el disco (pero su turno sí cuenta). */
    @Test fun `una evidencia que no cambia nada no reescribe el disco y su turno igual cuenta`() = runTest {
        copia.alLeer("v1", listOf(c("A")), Lectura.SUELTA)
        val vieja = copia.abrirLectura()
        val antes = guardados
        copia.alLeerDetalle("v1", "Y", EvidenciaDelDetalle.NoExiste)
        copia.alLeerDetalle("v1", "Z", EvidenciaDelDetalle.Abono(saldoCents = 0, conSaldo = false))
        assertEquals(antes, guardados)
        copia.alLeer("v1", listOf(c("Y")), Lectura.PRIMERA_PAGINA, vieja)
        assertNull("una lista más vieja no la mete", copia.cuenta("v1", "Y"))
    }

    /**
     * Final fix ronda 2 (menor 3): lo que anota un cobro entra EN EL ACTO y en orden (cualquier lectura posterior ya lo aplica), y leer,
     * decodificar y guardar la copia va por su despachador, nunca por el hilo de quien llama.
     */
    @Test fun `anotar en el acto deja la evidencia antes de cualquier lectura y el trabajo pesado va por su despachador`() = runTest {
        copia.alLeer("v1", listOf(c("A"), c("B")), Lectura.SUELTA)
        var usos = 0
        copia.io = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun isDispatchNeeded(context: kotlin.coroutines.CoroutineContext) = true
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { usos++; block.run() }
        }
        copia.anotarYa("v1", "A", EvidenciaDelDetalle.NoExiste) // no suspende: entra ya
        copia.anotarYa("v1", "B", EvidenciaDelDetalle.Abono(saldoCents = 2000, conSaldo = false))
        assertNull(copia.cuenta("v1", "A"))
        assertEquals(RazonSinRed.CON_ABONO, razonParaNoCobrar(copia.cuenta("v1", "B")!!.cuenta))
        assertEquals("leer, decodificar y guardar no van por el hilo de quien llama", true, usos > 0)
    }

    @Test fun `los turnos de una sucursal no frenan a otra`() = runTest {
        val vieja = copia.abrirLectura(); val nueva = copia.abrirLectura()
        copia.alLeer("v1", listOf(c("A")), Lectura.LISTA_COMPLETA, nueva)
        copia.alLeerDetalle("v1", "A", EvidenciaDelDetalle.NoExiste, copia.abrirLectura())
        copia.alLeer("v2", listOf(c("A"), c("B")), Lectura.SUELTA, vieja)
        assertEquals(setOf("A", "B"), ids("v2"))
        assertNull(copia.cuenta("v1", "A"))
    }
    @Test fun `el detalle con abono, saldo de regalo, cierre o 404 actualiza o retira la fila`() = runTest {
        copia.alLeer("v1", listOf(c("abono"), c("regalo"), c("cerrada"), c("borrada")), Lectura.SUELTA)
        copia.alLeerDetalle("v1", "abono", EvidenciaDelDetalle.Vigente(true, 6000, pagosCompletados = 1, cobrosConSaldo = 0, esCuentaDeTarjetas = false))
        copia.alLeerDetalle("v1", "regalo", EvidenciaDelDetalle.Vigente(true, 6000, pagosCompletados = 1, cobrosConSaldo = 1, esCuentaDeTarjetas = false))
        copia.alLeerDetalle("v1", "cerrada", EvidenciaDelDetalle.Vigente(false, 0, pagosCompletados = 1, cobrosConSaldo = 0, esCuentaDeTarjetas = false))
        copia.alLeerDetalle("v1", "borrada", EvidenciaDelDetalle.NoExiste)
        assertEquals(RazonSinRed.CON_ABONO, razonParaNoCobrar(copia.cuenta("v1", "abono")!!.cuenta))
        assertEquals(RazonSinRed.TARJETAS_DE_REGALO, razonParaNoCobrar(copia.cuenta("v1", "regalo")!!.cuenta))
        assertNull(copia.cuenta("v1", "cerrada")); assertNull(copia.cuenta("v1", "borrada"))
    }
    @Test fun `una lista vieja no pisa lo que dijo un detalle mas nuevo`() = runTest {
        copia.alLeer("v1", listOf(c("A")), Lectura.SUELTA)
        val lista = copia.abrirLectura(); val detalle = copia.abrirLectura()
        copia.alLeerDetalle("v1", "A", EvidenciaDelDetalle.Vigente(true, 6000, pagosCompletados = 0, cobrosConSaldo = 1, esCuentaDeTarjetas = false), detalle)
        copia.alLeer("v1", listOf(c("A")), Lectura.SUELTA, lista)
        assertEquals(RazonSinRed.TARJETAS_DE_REGALO, razonParaNoCobrar(copia.cuenta("v1", "A")!!.cuenta))
    }
    @Test fun `el detalle no agrega una cuenta que la copia no tiene`() = runTest {
        copia.alLeerDetalle("v1", "nueva", EvidenciaDelDetalle.Vigente(true, 6000, 0, 0, false))
        assertNull(copia.cuenta("v1", "nueva"))
    }
    @Test fun `una lectura suelta no quita lo que no vino`() = runTest {
        copia.alLeer("v1", listOf(c("A")), Lectura.SUELTA)
        copia.alLeer("v1", listOf(c("B")), Lectura.SUELTA)
        assertEquals(setOf("A", "B"), ids())
    }
    @Test fun `a las 24 horas de vista ya no se ofrece y la siguiente escritura la borra del disco`() = runTest {
        copia.alLeer("v1", listOf(c("A")), Lectura.SUELTA)
        reloj += 24 * 60 * 60 * 1000L + 1
        assertNull(copia.cuenta("v1", "A"))
        copia.alLeer("v1", listOf(c("B")), Lectura.SUELTA)
        assertFalse(disco.values.single().json.contains("\"A\""))
    }
    @Test fun `cada sucursal tiene su copia`() = runTest {
        copia.alLeer("v1", listOf(c("A")), Lectura.SUELTA)
        assertNull(copia.cuenta("v2", "A"))
    }
    @Test fun `la razon de cada gris`() {
        assertNull(razonParaNoCobrar(c("ok")))
        assertEquals(RazonSinRed.CON_ABONO, razonParaNoCobrar(c("abono", pago = "PARTIAL", pagos = 1)))
        assertEquals(RazonSinRed.CON_ABONO, razonParaNoCobrar(c("abonoServidorSinConteo", pago = "PARTIAL", pagos = null)))
        assertEquals(RazonSinRed.SIN_SALDO_DEL_SERVIDOR, razonParaNoCobrar(c("viejo", saldo = null)))
        assertEquals(RazonSinRed.SIN_SALDO, razonParaNoCobrar(c("cero", saldo = 0)))
        assertEquals(RazonSinRed.TARJETAS_DE_REGALO, razonParaNoCobrar(c("regalo", saldoDeRegalo = 1)))
        assertEquals(RazonSinRed.TARJETAS_DE_REGALO, razonParaNoCobrar(c("tarjetas", tarjetas = true)))
        listOf(c("mesa", mesa = true), c("mesaDesconocida", mesa = null), c("vales", vales = true), c("rappi", origen = "RAPPI"),
            c("web", origen = "WEB")).forEach { assertEquals(it.id, RazonSinRed.NO_ES_DE_MOSTRADOR, razonParaNoCobrar(it)) }
    }
    @Test fun `un saldo raro en el JSON se lee como sin saldo y no tumba la fila`() {
        val json = Json { ignoreUnknownKeys = true }
        listOf("60.5", "-5", "\"6000\"", "null", "{}").forEach { raro ->
            val fila = json.decodeFromString<OrderSummary>("""{"id":"A","saldoPendienteCents":$raro,"pagosCompletados":$raro}""")
            assertNull(raro, fila.saldoPendienteCents); assertNull(raro, fila.pagosCompletados)
        }
        assertEquals(6000, json.decodeFromString<OrderSummary>("""{"id":"A","saldoPendienteCents":6000}""").saldoPendienteCents)
    }
}
