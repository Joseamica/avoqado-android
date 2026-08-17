package com.avoqado.pos.cashdrawer

import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.cashdrawer.data.CobroSinReproducir
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 🔴 **LA VENTANA DE LA CAJA COMPARA DOS RELOJES DISTINTOS.**
 *
 * `createdAt >= session.openedAt` suena a una comparación de fechas. No lo es: el
 * `createdAt` de las colas lo estampa el APARATO (`System.currentTimeMillis()`) y el
 * `openedAt` lo escribe el SERVER. Entre los dos hay el desfase que tenga la tablet,
 * que nadie garantiza y que en un local real nadie mira.
 *
 * Las dos direcciones, medidas aquí abajo con el mismo cajón:
 *
 * ```
 *  APARATO ADELANTADO  → un cobro atorado de ANTES de abrir queda estampado DESPUÉS,
 *                        entra en la ventana y protege una venta que el server ya
 *                        tiene → 560000 en pantalla con 530000 en el cajón.
 *                        **FALTANTE**: es la dirección que acusa a un cajero de robar.
 *  APARATO ATRASADO    → un cobro legítimo de esta caja queda estampado ANTES de la
 *                        apertura, se cae de la ventana y su venta se borra
 *                        → 520000 con 550000 de verdad. **SOBRANTE**: nadie acusa a
 *                        nadie por dinero que le sobra.
 * ```
 *
 * De ahí la tolerancia de CINCO minutos —`PendingCashSales.TOLERANCIA_DE_RELOJ_MILLIS`—
 * y su dirección: la ventana arranca 5 minutos DESPUÉS de lo que dijo el server, así
 * que un sello dentro de la banda de duda no alcanza para proteger. **En la duda se
 * elige el lado que no acusa a nadie**, aunque cueste un sobrante.
 *
 * El límite queda anclado a propósito: con un desfase MAYOR que la tolerancia el
 * faltante vuelve, y eso se prueba en vez de descubrirse.
 *
 * ⚠️ Espejo exacto de iOS: mismo valor (5 min) y misma dirección. Un margen distinto en
 * cada plataforma daría dos números para el mismo cajón, que es justo el defecto que
 * esta serie de commits vino a cerrar.
 */
class CashDrawerRelojDesfasadoTest {

    /** La caja del SERVER abrió hace una hora. Es el único reloj de referencia. */
    private val abrioHace = haceMinutos(60)

    private val cincoMinutos = 5 * 60_000L

    // MARK: - El cajón de estos tests

    /**
     * El caso "mi venta de mostrador sigue en la cola": el server confirma una venta de
     * $200 que este aparato YA había copiado (no llega por primera vez, así que no
     * reclama nada) y mi venta de $300 vive sólo mientras su cobro la proteja.
     *
     *  - protegida  → fondo 5000 + la del server 200 + la mía 300 = **550000**
     *  - desprotegida → fondo 5000 + la del server 200 = **520000** (sobrante de $300)
     */
    private fun cajonConMiVentaEncolada(
        dao: FakeCashDrawerDao,
        selloDelCobro: Long,
        payload: String,
    ) = cashDrawerRepo(
        dao.also {
            it.sessions.clear()
            it.events.clear()
            it.sessions["srv-1"] = sesionLocal("srv-1", openedAt = abrioHace)
            it.events["srv-ev-otra"] = eventoLocal(
                "srv-ev-otra", "srv-1", "CASH_SALE", 20_000,
                orderId = "order-otra", createdAt = haceMinutos(50),
            )
            it.events["local-venta"] = eventoLocal(
                "local-venta", "srv-1", "CASH_SALE", 30_000,
                orderId = null, createdAt = haceMinutos(45),
            )
        },
        cashDrawerClient("/cash-drawer/current" to payload),
        pendingCashSales = colaDeCobros(
            cobros = listOf(cobroEncolado("cobro-mio", 30_000, createdAt = selloDelCobro)),
        ),
    )

    /** El payload con la venta que este aparato ya había copiado en un sync anterior. */
    private fun payloadConLaVentaYaCopiada(openedAt: Long = abrioHace) = sesionJson(
        "srv-1",
        aperturaDelServer,
        eventoJson(
            "srv-ev-otra", "CASH_SALE", "200.00",
            localId = "srv-cash-sale:pay-otra", orderId = "order-otra", createdAt = haceMinutos(50),
        ),
        openedAt = openedAt,
    )

    /**
     * El caso contrario: el cobro atorado es de ANTES de que abriera esta caja, así que
     * su dinero NO está en este cajón. La copia local que protege es la de una venta que
     * el server ya tiene, o sea que protegerla la cuenta dos veces.
     *
     *  - desprotegida (correcto) → fondo 5000 + la del server 300 = **530000**
     *  - protegida (el defecto)  → 530000 + 300 otra vez = **560000**, faltante de $300
     */
    private fun cajonConUnCobroAtorado(selloDelCobro: Long): Pair<FakeCashDrawerDao, CashDrawerRepository> {
        val dao = FakeCashDrawerDao()
        dao.sessions["srv-1"] = sesionLocal("srv-1", openedAt = abrioHace)
        dao.events["srv-ev-hoy"] = eventoLocal(
            "srv-ev-hoy", "srv-1", "CASH_SALE", 30_000,
            orderId = "order-hoy", createdAt = haceMinutos(20),
        )
        dao.events["local-venta-hoy"] = eventoLocal(
            "local-venta-hoy", "srv-1", "CASH_SALE", 30_000,
            orderId = null, createdAt = haceMinutos(20),
        )
        val repo = cashDrawerRepo(
            dao,
            cashDrawerClient(
                "/cash-drawer/current" to sesionJson(
                    "srv-1",
                    aperturaDelServer,
                    eventoJson(
                        "srv-ev-hoy", "CASH_SALE", "300.00",
                        localId = "srv-cash-sale:pay-hoy", orderId = "order-hoy", createdAt = haceMinutos(20),
                    ),
                    openedAt = abrioHace,
                ),
            ),
            pendingCashSales = colaDeCobros(
                cobros = listOf(cobroEncolado("cobro-atorado", 30_000, createdAt = selloDelCobro)),
            ),
        )
        return dao to repo
    }

    private suspend fun arqueo(repo: CashDrawerRepository): Int {
        val sesion = repo.getOpenSession()!!
        return repo.computeExpectedAmount(sesion.id, sesion.startingAmountCents)
    }

    // MARK: - (1) El aparato ADELANTADO: la dirección que acusa

    /**
     * 🔴 **EL NÚMERO QUE ABRIÓ EL HUECO.** La tablet va 5 minutos adelantada. Un cobro
     * que se hizo 3 minutos ANTES de abrir la caja —dinero que NO está en este cajón—
     * queda estampado 2 minutos DESPUÉS de la apertura, entra en la ventana y protege la
     * copia local de una venta que el server ya confirmó.
     *
     * Sin tolerancia salen **560000** con $5,300.00 en el cajón: $300 de faltante
     * inventado. Con la ventana arrancando 5 minutos después de la apertura, ese sello
     * ya no alcanza: **530000**, que es lo que hay.
     */
    @Test
    fun `el aparato ADELANTADO 5 minutos ya no inventa un faltante`() = runTest {
        val (dao, repo) = cajonConUnCobroAtorado(selloDelCobro = haceMinutos(58))
        repo.syncFromApi()

        assertEquals(
            "un cobro de ANTES de abrir protegió una venta que el server ya tiene: el " +
                "cajero cierra con \$300 de faltante inventado (fondo 5000 + venta 300)",
            530_000,
            arqueo(repo),
        )
        assertNull("sobrevivió la copia local que el cobro adelantado protegía", dao.events["local-venta-hoy"])
    }

    // MARK: - (2) El aparato ATRASADO: el cobro legítimo NO se pierde

    /**
     * El contrapeso. La tablet va 5 minutos atrasada y el cobro es legítimo: se hizo 12
     * minutos después de abrir la caja, así que su dinero SÍ está en el cajón. Queda
     * estampado 7 minutos después de la apertura.
     *
     * Si la tolerancia creciera —o si la ventana se cerrara de más por cualquier otro
     * motivo— este sello se caería fuera y la venta desaparecería del arqueo: 520000 con
     * $5,500.00 de verdad. Tiene que seguir dando **550000**.
     */
    @Test
    fun `el aparato ATRASADO 5 minutos sigue protegiendo el cobro legitimo`() = runTest {
        val dao = FakeCashDrawerDao()
        val repo = cajonConMiVentaEncolada(
            dao,
            selloDelCobro = haceMinutos(53),
            payload = payloadConLaVentaYaCopiada(),
        )
        repo.syncFromApi()

        assertEquals(
            "la ventana se comió un cobro legítimo de esta caja y su venta desapareció " +
                "del arqueo (fondo 5000 + la del server 200 + la mía 300)",
            550_000,
            arqueo(repo),
        )
        assertNotNull(dao.events["local-venta"])
    }

    // MARK: - (3) El límite, explícito

    /**
     * ⚠️ **RESIDUO DECLARADO — el límite de la tolerancia, anclado a propósito.** El
     * mismo cobro atorado del test (1), pero con la tablet 20 minutos adelantada: el
     * sello cae 17 minutos después de la apertura, muy dentro de la ventana, y el
     * faltante vuelve.
     *
     * **560000 con $5,300.00 en el cajón.** No es un descuido: una tolerancia lo bastante
     * ancha para tapar cualquier desfase dejaría entrar cobros de verdad viejos, que es
     * la fuga que la ventana vino a cerrar. Cinco minutos cubren el desfase de un reloj
     * sin NTP; media hora ya es un aparato mal configurado, y eso se arregla en el
     * aparato, no aflojando el arqueo.
     */
    @Test
    fun `RESIDUO — con el reloj 20 minutos adelantado, mas que la tolerancia, vuelve el faltante`() = runTest {
        val (dao, repo) = cajonConUnCobroAtorado(selloDelCobro = haceMinutos(43))
        repo.syncFromApi()

        assertEquals(
            "RESIDUO CONOCIDO: un desfase MAYOR que la tolerancia vuelve a colar un cobro " +
                "de antes de abrir. Dirección faltante (\$5,600 en pantalla con \$5,300 en el cajón)",
            560_000,
            arqueo(repo),
        )
        assertNotNull(dao.events["local-venta-hoy"])
    }

    // MARK: - (4) Sin `openedAt`: sin cota, jamás "de ahora en adelante"

    /**
     * 🔴 **UN CAMPO QUE FALTA PUEDE QUITAR LA COTA; NUNCA HACER DESAPARECER DINERO.**
     *
     * El server siempre manda `openedAt` (`cash-drawer.mobile.service.ts`), así que esto
     * es latente — pero la degradación iba en la dirección prohibida: `parseTimestamp`
     * rellena con `now`, y una ventana "de ahora en adelante" deja fuera a TODOS los
     * cobros pendientes (todos se encolaron antes de "ahora"). La protección entera se
     * colapsaba y cada venta cobrada sin red desaparecía del arqueo de golpe: **520000**
     * donde hay $5,500.00.
     *
     * Con la ventana leída del PAYLOAD y `0` cuando el server no lo dijo, se vuelve al
     * comportamiento anterior a la cota —que como mucho deja vivo un cobro atorado— en
     * vez de perder todo lo cobrado sin red. Es el mismo criterio con el que
     * `PrintConfigRepository` conserva una config vieja antes que quedarse sin imprimir.
     */
    @Test
    fun `sin openedAt la ventana queda SIN cota y la proteccion sigue viva`() = runTest {
        val dao = FakeCashDrawerDao()
        val repo = cajonConMiVentaEncolada(
            dao,
            selloDelCobro = haceMinutos(45),
            payload = sesionJsonSinApertura(
                "srv-1",
                aperturaDelServer,
                eventoJson(
                    "srv-ev-otra", "CASH_SALE", "200.00",
                    localId = "srv-cash-sale:pay-otra", orderId = "order-otra", createdAt = haceMinutos(50),
                ),
            ),
        )
        repo.syncFromApi()

        assertEquals(
            "sin `openedAt` la ventana se volvió \"de ahora en adelante\" y barrió todo lo " +
                "cobrado sin red (fondo 5000 + la del server 200 + la mía 300)",
            550_000,
            arqueo(repo),
        )
        assertNotNull(dao.events["local-venta"])
    }

    /** La ventana, leída directamente: `0` = el server no lo dijo, o sea SIN cota. */
    @Test
    fun `la ventana de un payload sin openedAt es CERO, nunca now`() {
        val repo = CashDrawerRepository(
            dao = mockk(relaxed = true),
            secureStorage = mockk(relaxed = true),
            client = mockk(relaxed = true),
            pendingCashSales = mockk(relaxed = true),
        )
        fun ventana(json: String) =
            repo.ventanaDeLaCaja(Json.parseToJsonElement(json) as JsonObject)

        assertEquals("una ventana inventada colapsa la protección entera", 0L, ventana("""{"id":"srv-1"}"""))
        // 2026-08-16T15:00:00Z. El epoch va a mano y no calculado: si se sacara con el
        // mismo `Instant.parse` que usa la implementación, la prueba sólo se probaría a
        // sí misma y un parseo torcido pasaría igual.
        assertEquals(
            1_786_892_400_000L,
            ventana("""{"id":"srv-1","openedAt":"2026-08-16T15:00:00Z"}"""),
        )
        assertEquals(
            "el server viejo manda `createdAt` en vez de `openedAt`",
            1_786_892_400_000L,
            ventana("""{"id":"srv-1","createdAt":"2026-08-16T15:00:00Z"}"""),
        )
    }

    // MARK: - (5) El caso común no se mueve ni un centavo

    /**
     * El reloj en hora y el cobro donde siempre: mismo número que el test (2). La
     * tolerancia no puede cobrarle nada al local que tiene la hora bien.
     */
    @Test
    fun `con el reloj en hora el caso comun no se mueve ni un centavo`() = runTest {
        val dao = FakeCashDrawerDao()
        val repo = cajonConMiVentaEncolada(
            dao,
            selloDelCobro = haceMinutos(48),
            payload = payloadConLaVentaYaCopiada(),
        )
        repo.syncFromApi()

        assertEquals(
            "la tolerancia movió el caso común (fondo 5000 + la del server 200 + la mía 300)",
            550_000,
            arqueo(repo),
        )
        assertNotNull(dao.events["local-venta"])
    }

    // MARK: - El valor y la dirección de la tolerancia, clavados

    /**
     * 🔴 CINCO MINUTOS EXACTOS, HACIA ADELANTE, Y EN LAS **DOS** COLAS. Los dos bordes
     * en la misma prueba: el sello que cae justo en `openedAt + 5 min` protege; el de un
     * milisegundo antes, no.
     *
     * Si alguien cambia el valor, o le da la vuelta al signo —que es el error fácil,
     * porque "tolerancia" suena a *dejar pasar más*— este test se pone rojo. Restar la
     * tolerancia en vez de sumarla dejaría entrar cobros de ANTES de abrir la caja, que
     * es exactamente la fuga del test (1).
     *
     * 🔴 Las dos colas van en la MISMA prueba a propósito: un cobro sin red cae en
     * `pending_payments` o en el outbox según cómo naciera la mesa, y acotar sólo una
     * deja la fuga viva por la puerta de al lado. Hoy la suma vive en `sinReproducir`,
     * antes de partirse en dos, así que las dos quedan atadas por construcción — pero
     * eso es una decisión de implementación y esta prueba la fija desde afuera.
     */
    @Test
    fun `la tolerancia son CINCO minutos exactos, hacia adelante, y en las DOS colas`() = runTest {
        val abrio = haceMinutos(60)
        val cola = colaDeCobros(
            cobros = listOf(
                cobroEncolado("justo-dentro", 30_000, createdAt = abrio + cincoMinutos),
                cobroEncolado("un-milisegundo-antes", 20_000, createdAt = abrio + cincoMinutos - 1),
            ),
            intents = listOf(
                intentPayCash("local-order-dentro", 40_000, createdAt = abrio + cincoMinutos),
                intentPayCash("local-order-antes", 15_000, createdAt = abrio + cincoMinutos - 1),
            ),
        )

        assertEquals(
            "la banda de duda del reloj no mide 5 minutos exactos hacia adelante en las dos colas",
            listOf(
                CobroSinReproducir(orderId = null, totalCents = 30_000),
                CobroSinReproducir(orderId = "local-order-dentro", totalCents = 40_000),
            ),
            cola.sinReproducir(VENUE_ID, abrio),
        )
    }
}
