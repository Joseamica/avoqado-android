package com.avoqado.pos.transactions

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.cashdrawer.data.CorteTicketBuilder
import com.avoqado.pos.core.domain.refresh.RefreshGate
import com.avoqado.pos.core.domain.refresh.RefreshGateFactory
import com.avoqado.pos.transactions.data.RefundRepository
import com.avoqado.pos.transactions.data.RefundResult
import com.avoqado.pos.transactions.data.TransactionRepository
import com.avoqado.pos.transactions.presentation.TransactionsViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.time.Duration

/**
 * 🔴 QUIÉN RESTA EL REEMBOLSO DEL CAJÓN: el SERVIDOR, y NADIE MÁS.
 *
 * Medido en hardware el 2026-08-16: el cajón marcaba $50,380 con $50,230
 * físicos. El sobrante inventado era exactamente lo reembolsado ($150), porque
 * la ruta que la app usa de verdad (`refund.dashboard.service.issueRefund`) no
 * tocaba el cajón. Ese lado ya se arregló en el servidor
 * (`shared/cashDrawerPosting.postCashRefundToDrawer`, commit `08a3fe6f`).
 *
 * El peligro AHORA es el opuesto y es igual de caro: esta app tenía su propio
 * parche que mandaba un PAY_OUT por su cuenta después de cada reembolso. Con el
 * servidor restando, ese parche hace que **el cajón reste DOS VECES** — y el
 * cajero paga de su bolsa un faltante que nadie se robó.
 *
 * La llave de idempotencia del servidor (`srv-refund:<refundId>`) NO puede
 * defenderse de esto: la del cliente es un UUID local, así que no colisiona, y
 * un PAY_OUT empujado por `/cash-drawer/sync` es indistinguible de un retiro a
 * mano. La única defensa es que el cliente NO escriba. Estos tests son esa
 * defensa.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RefundCashDrawerOwnershipTest {

    private val scheduler = TestCoroutineScheduler()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher(scheduler))

    private val repository: TransactionRepository = mockk(relaxed = true)
    private val refundRepository: RefundRepository = mockk(relaxed = true)
    private val cashDrawerRepository: CashDrawerRepository = mockk(relaxed = true)
    private val factory: RefreshGateFactory = mockk()

    private fun buildViewModel(): TransactionsViewModel {
        every { factory.create(any(), any()) } returns
            RefreshGate(clock = { Duration.ZERO }, random = { 0.5 })
        coEvery { repository.fetchTransactions(any(), any()) } returns Result.success(Unit)
        every { repository.transactions } returns MutableStateFlow(emptyList())
        every { repository.isLoading } returns MutableStateFlow(false)
        every { repository.isLoadingMore } returns MutableStateFlow(false)
        return TransactionsViewModel(
            repository = repository,
            refundRepository = refundRepository,
            cashDrawerRepository = cashDrawerRepository,
            terminalPaymentService = mockk(relaxed = true),
            roleManager = mockk(relaxed = true),
            tpvSettingsRepository = mockk(relaxed = true),
            managerOverrideCoordinator = mockk(relaxed = true),
            orderRepository = mockk(relaxed = true),
            printerService = mockk(relaxed = true),
            secureStorage = mockk(relaxed = true),
            refreshGateFactory = factory,
        )
    }

    /**
     * El reembolso desasociado pega en `POST /mobile/venues/:id/refunds`, o sea
     * `refund.mobile.service.createRefund`, que **siempre** ha creado su PAY_OUT
     * (hoy vía el helper compartido). Este cliente manda `method = "CASH"` fijo
     * (`RefundRepository:214`), así que el movimiento del servidor está
     * garantizado: cualquier escritura de aquí lo duplica.
     */
    @Test
    fun `un reembolso desasociado NO escribe el egreso en el cajon`() = runTest(scheduler) {
        coEvery { refundRepository.createUnassociatedRefund(any(), any(), any()) } returns
            Result.success(RefundResult(refundId = "r-1", message = "Reembolso procesado"))

        buildViewModel().processUnassociatedRefund(
            amountText = "150.00",
            reason = "Producto defectuoso",
        )

        coVerify(exactly = 0) { cashDrawerRepository.addPayOut(any(), any()) }
        coVerify(exactly = 0) { cashDrawerRepository.getOpenSession() }
    }

    /**
     * Y si el reembolso FALLA, mucho menos: sacar dinero del cajón por una
     * devolución que nunca ocurrió inventa un faltante.
     */
    @Test
    fun `un reembolso desasociado que falla tampoco toca el cajon`() = runTest(scheduler) {
        coEvery { refundRepository.createUnassociatedRefund(any(), any(), any()) } returns
            Result.failure(RuntimeException("boom"))

        buildViewModel().processUnassociatedRefund(
            amountText = "150.00",
            reason = "Producto defectuoso",
        )

        coVerify(exactly = 0) { cashDrawerRepository.addPayOut(any(), any()) }
    }

    /**
     * `IssueRefundSheet` es un @Composable: no hay forma de instanciarlo en un
     * test de JVM (este módulo no trae `compose-ui-test` ni Robolectric). Pero es
     * justo donde vivía el parche que midió el defecto, así que la regresión se
     * vigila sobre el TEXTO del archivo.
     *
     * Feo a propósito y mejor que nada: el costo de que alguien vuelva a añadir
     * la escritura "para que la caja se vea al instante" es dinero mal contado en
     * un local, y no lo atrapa ningún otro test de este repo.
     */
    @Test
    fun `la pantalla de reembolso NO escribe el egreso en el cajon`() {
        val codigo = leerCodigoSinComentarios(
            "app/src/main/java/com/avoqado/pos/transactions/presentation/IssueRefundSheet.kt",
        )
        assertTrue(
            "IssueRefundSheet volvió a escribir en el cajón: el servidor ya resta el " +
                "reembolso (postCashRefundToDrawer), así que esto lo restaría DOS VECES.",
            !codigo.contains("addPayOut"),
        )
    }

    @Test
    fun `la pantalla de ventas NO escribe el egreso en el cajon`() {
        val codigo = leerCodigoSinComentarios(
            "app/src/main/java/com/avoqado/pos/transactions/presentation/TransactionsViewModel.kt",
        )
        assertTrue(
            "TransactionsViewModel volvió a escribir en el cajón: el servidor ya resta el " +
                "reembolso, así que esto lo restaría DOS VECES.",
            !codigo.contains("addPayOut"),
        )
    }

    /**
     * 🔴 CONTRATO CROSS-REPO, ahora que la nota la escribe el SERVIDOR.
     *
     * El corte separa "Reembolsos en efectivo" del resto de los retiros por el
     * PREFIJO de la nota (`CorteTicketBuilder:66`, `DailyReportView:96`). Antes
     * lo escribía esta app; ahora lo escribe `DRAWER_REFUND_NOTE_PREFIX` en
     * `avoqado-server/src/services/shared/cashDrawerPosting.ts`. Si las dos
     * cadenas se separan, el dinero sí baja del cajón pero el ticket lo cuenta
     * como un retiro a mano y el dueño no puede explicar el hueco.
     *
     * La cadena de abajo es LITERAL, copiada de lo que produce el servidor.
     */
    @Test
    fun `la nota que escribe el servidor la lee el corte como reembolso`() {
        val notaDelServidor = "Reembolso: Productos devueltos"
        assertTrue(
            "El corte ya no reconocería como reembolso lo que escribe el servidor.",
            notaDelServidor.startsWith(CorteTicketBuilder.PREFIJO_REEMBOLSO),
        )
    }

    /**
     * El guard mira CÓDIGO, no prosa: los comentarios se quitan antes de buscar.
     *
     * Si no, el propio comentario que explica por qué ya no se llama a `addPayOut`
     * hace fallar el test — y la salida sería borrar la explicación, que es justo
     * lo que evita que alguien reinstale el parche.
     */
    private fun leerCodigoSinComentarios(rutaRelativa: String): String =
        leerFuente(rutaRelativa)
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""//[^\n]*"""), " ")

    /**
     * Los tests corren con el working dir en `app/` o en la raíz del repo según
     * cómo se invoque Gradle. Un guard que no encuentra su archivo y pasa en
     * silencio no vale nada, así que aquí se busca hacia arriba y se revienta si
     * no aparece.
     */
    private fun leerFuente(rutaRelativa: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidato = File(dir, rutaRelativa)
            if (candidato.isFile) return candidato.readText()
            // También con el working dir ya dentro de `app/`.
            val sinModulo = File(dir, rutaRelativa.removePrefix("app/"))
            if (sinModulo.isFile) return sinModulo.readText()
            dir = dir.parentFile
        }
        throw AssertionError("No se encontró $rutaRelativa desde ${System.getProperty("user.dir")}")
    }
}
