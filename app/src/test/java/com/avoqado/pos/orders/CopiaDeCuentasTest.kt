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
    private val cache = mockk<PayloadCache> {
        coEvery { load(any(), any()) } answers { if (falloAlLeer != null) null else disco["${firstArg<String>()}|${secondArg<String>()}"] }
        coEvery { loadDurable(any(), any()) } answers { falloAlLeer?.let { throw it }; disco["${firstArg<String>()}|${secondArg<String>()}"] }
        coEvery { saveDurable(any(), any(), any()) } answers {
            if (falloAlGuardar) throw IllegalStateException("disco lleno")
            disco["${firstArg<String>()}|${secondArg<String>()}"] = PayloadCache.Cached(thirdArg(), 0)
        }
    }
    private var reloj = 1_000_000_000L
    private val copia = CopiaDeCuentas(cache).also { it.ahora = { reloj } }
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
    @Test fun `si falla la lectura del disco la copia anterior se queda`() = runTest {
        // Revisión T4 #2: un error de E/S o una cancelación al leer no es «no hay copia»: no se escribe una copia nueva encima.
        copia.alLeer("v1", listOf(c("A")), Lectura.SUELTA)
        listOf(java.io.IOException("disco"), kotlinx.coroutines.CancellationException("cancelada")).forEach { falla ->
            falloAlLeer = falla
            runCatching { copia.alLeer("v1", listOf(c("B")), Lectura.LISTA_COMPLETA) }
            runCatching { copia.alLeerDetalle("v1", "A", EvidenciaDelDetalle.NoExiste) }
            falloAlLeer = null
            assertEquals(falla.toString(), setOf("A"), ids())
        }
    }
    @Test fun `si falla el guardado la copia anterior se queda y no se anota ningun turno`() = runTest {
        copia.alLeer("v1", listOf(c("A")), Lectura.SUELTA)
        val entre = copia.abrirLectura(); val fallida = copia.abrirLectura()
        falloAlGuardar = true
        copia.alLeer("v1", emptyList(), Lectura.LISTA_COMPLETA, fallida) // no lanza
        falloAlGuardar = false
        assertEquals(setOf("A"), ids())
        // Si `fallida` hubiera quedado anotada (por cuenta o por sucursal), estas dos evidencias más viejas se descartarían.
        copia.alLeerDetalle("v1", "A", EvidenciaDelDetalle.Vigente(true, 6000, 0, cobrosConSaldo = 1, esCuentaDeTarjetas = false), entre)
        copia.alLeer("v1", listOf(c("N")), Lectura.SUELTA, entre)
        assertEquals(RazonSinRed.TARJETAS_DE_REGALO, razonParaNoCobrar(copia.cuenta("v1", "A")!!.cuenta))
        assertNotNull(copia.cuenta("v1", "N"))
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
