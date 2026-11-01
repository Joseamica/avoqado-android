package com.avoqado.pos.transactions

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.cashdrawer.data.model.CashDrawerSessionEntity
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.transactions.data.*
import com.avoqado.pos.transactions.data.model.Transaction
import com.avoqado.pos.transactions.presentation.exchange.*
import io.mockk.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ExchangePaymentViewModelTest {
    @get:Rule val main=MainDispatcherRule(UnconfinedTestDispatcher())
    private val repo=mockk<ExchangeRepository>()
    private val store=mockk<ExchangePendingStore>()
    private val secure=mockk<SecureStorage>(relaxed=true) { every { venueId } returns "venue"; every { userId } returns "actor" }
    private val monitor=mockk<ConnectivityMonitor> { every { isOnline() } returns true }
    private val drawer=mockk<CashDrawerRepository>(relaxed=true)
    private val overrideEnabled = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val storeCreditOnly = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val settings = mockk<com.avoqado.pos.tpvsettings.data.TpvSettingsRepository> {
        every { managerPinOverrideEnabled } returns overrideEnabled
        every { this@mockk.storeCreditOnly } returns this@ExchangePaymentViewModelTest.storeCreditOnly
        every { storeCreditAvailable } returns kotlinx.coroutines.flow.MutableStateFlow<Boolean?>(true)
    }
    private val customers = mockk<com.avoqado.pos.customers.data.CustomersRepository>()
    private fun vm()=ExchangePaymentViewModel(repo,store,secure,mockk(relaxed=true),drawer,mockk(relaxed=true),mockk(relaxed=true),mockk(relaxed=true),monitor,settings,customers)

    @Test fun `cash collection and payout send with only a confirmed open drawer from this venue`() {
        val confirmed = CashDrawerSessionEntity("c123456789012345678901234", "venue", null, "actor", "Ana", 0L, 0)
        val cases = listOf(
            Triple("no drawer", null, null),
            Triple("confirmed drawer", confirmed, confirmed.id),
            Triple("another venue", confirmed.copy(venueId = "other"), null),
            Triple("local id", confirmed.copy(id = "local-drawer"), null),
            Triple("closed drawer", confirmed.copy(status = "CLOSED"), null),
            Triple("read failure", null, null),
        )
        for (kind in listOf("COLLECT", "PAYOUT")) for ((name, session, expectedId) in cases) {
            clearMocks(repo, store, drawer)
            coEvery { store.load("venue", "payment") } returns null
            coEvery { store.save(any()) } answers { firstArg<PendingExchange>() }
            coEvery { store.remove(any()) } just Runs
            if (name == "read failure") coEvery { drawer.getOpenSession() } throws java.io.IOException("disk")
            else coEvery { drawer.getOpenSession() } returns session
            val difference = if (kind == "COLLECT") 250L else -250L
            var sentBody: String? = null
            coEvery { repo.create(any(), "venue") } answers {
                sentBody = firstArg()
                ExchangeResult("exchange", "COMPLETED", "original", "ORD-1", "new", "ORD-2", 1000, 1000 + difference,
                    difference, ExchangeResultSettlement(kind, "CASH", 250), ExchangeResultLines(emptyList(), emptyList()))
            }
            val vm = vm()
            vm.open(Transaction("payment", payoutOptions = listOf(
                com.avoqado.pos.transactions.data.model.PayoutOption("CASH", 1000, 1000, false))))
            val draft = ExchangeDraft("payment", emptyList(), emptyList(), ExchangeSettlementDraft(kind, difference))
            vm.begin(draft)
            vm.choose(vm.options(draft).single { it.method == "CASH" })
            val label = "$kind: $name"
            assertNotNull(label, sentBody)
            val settlement = Json.parseToJsonElement(requireNotNull(sentBody)).jsonObject.getValue("settlement").jsonObject
            assertEquals(label, expectedId, settlement["cashDrawerSessionId"]?.jsonPrimitive?.contentOrNull)
            assertEquals(label, "CASH", settlement.getValue("method").jsonPrimitive.content)
            assertEquals(label, kind, settlement.getValue("kind").jsonPrimitive.content)
            assertEquals(label, ExchangeSendPhase.COMPLETED, vm.state.value.phase)
            assertNull(label, vm.message.value)
            coVerify(exactly = 1) { repo.create(any(), "venue") }
        }
    }

    @Test fun `disabled cash cannot create pending even after override changes`() {
        coEvery { store.load("venue", "payment") } returns null
        val vm = vm()
        vm.open(Transaction("payment", payoutOptions = listOf(com.avoqado.pos.transactions.data.model.PayoutOption("CASH", 1000, 0, true))))
        val draft = ExchangeDraft("payment", emptyList(), emptyList(), ExchangeSettlementDraft("PAYOUT", -250))
        vm.begin(draft)
        overrideEnabled.value = true
        val enabled = vm.options(draft).single()
        assertTrue(enabled.requiresPin)
        overrideEnabled.value = false
        val disabled = vm.options(draft).single()
        assertEquals(TextosCambio.EFECTIVO_SIN_PERMISO, disabled.disabledReason)
        vm.choose(disabled)
        vm.choose(enabled)
        assertFalse(vm.state.value.locked)
        assertNotNull(vm.draft.value)
        coVerify(exactly = 0) { store.save(any()) }
        coVerify(exactly = 0) { repo.create(any(), any()) }
    }
    @Test fun `opening pending sale does not post or charge without explicit confirmation`() {
        coEvery { store.load("venue","payment") } returns PendingExchange("venue","payment","ORD-1","Ana","""{"staffId":"actor"}""")
        val vm=vm(); vm.open(Transaction("payment"))
        assertTrue(vm.ready.value); assertTrue(vm.state.value.locked)
        assertEquals(TextosCambio.CONFIRMAR_ESTADO,vm.state.value.action)
        coVerify(exactly=0) { repo.create(any(),any()) }
    }
    @Test fun `another actor cannot use pending card and pending body survives`() {
        val p=PendingExchange("venue","payment","ORD-1","Ana","""{"staffId":"other"}""")
        coEvery { store.load("venue","payment") } returns p
        val vm=vm(); vm.open(Transaction("payment")); vm.retry()
        assertEquals(TextosCambio.ACTOR_MISMATCH.replace("{nombre}","Ana"),vm.message.value)
        coVerify(exactly=0) { repo.create(any(),any()) }
        coVerify(exactly=0) { store.remove(any()) }
    }
    @Test fun `offline confirmation never creates or queues an exchange`() {
        coEvery { store.load("venue","payment") } returns null
        every { monitor.isOnline() } returns false
        val vm=vm(); vm.open(Transaction("payment"))
        vm.begin(ExchangeDraft("payment",emptyList(),emptyList(),ExchangeSettlementDraft("EVEN",0)))
        assertEquals(TextosCambio.SIN_CONEXION,vm.message.value)
        coVerify(exactly=0) { store.save(any()) }
        coVerify(exactly=0) { repo.create(any(),any()) }
    }
    @Test fun `failed pending read keeps editing locked and explicit retry reads storage again`() {
        coEvery { store.load("venue","payment") } throws java.io.IOException("disk")
        val vm=vm(); vm.open(Transaction("payment"))
        assertTrue(vm.ready.value); assertTrue(vm.state.value.locked)
        assertEquals(TextosCambio.RECUPERACION_FALLIDA,vm.message.value)
        coEvery { store.load("venue","payment") } returns null
        vm.retry()
        assertFalse(vm.state.value.locked)
        coVerify(exactly=0) { repo.create(any(),any()) }
    }

    @Test fun `late storage read cannot show previous sale pending under the new sale`() = runTest {
        val old=kotlinx.coroutines.CompletableDeferred<PendingExchange?>()
        coEvery { store.load("venue","old") } coAnswers { old.await() }
        coEvery { store.load("venue","new") } returns null
        val vm=vm(); vm.open(Transaction("old")); vm.open(Transaction("new"))
        old.complete(PendingExchange("venue","old","ORD-OLD","Ana","{}"))
        advanceUntilIdle()
        assertTrue(vm.ready.value); assertFalse(vm.state.value.locked)
        coVerify(exactly=0) { repo.create(any(),any()) }
    }

    @Test fun `returning to the same sale reloads a pending cleared by the exchange screen`() {
        coEvery { store.load("venue","payment") } returns PendingExchange("venue","payment","ORD-1","Ana","""{"staffId":"actor"}""")
        val vm=vm(); val sale=Transaction("payment"); vm.open(sale)
        assertTrue(vm.state.value.locked)
        coEvery { store.load("venue","payment") } returns null
        vm.open(sale,force=true)
        assertFalse(vm.state.value.locked)
        coVerify(exactly=0) { repo.create(any(),any()) }
    }

    // MARK: - Saldo a favor (A2)
    private val saldo = com.avoqado.pos.transactions.data.model.PayoutOption("STORE_CREDIT", 50000, 0, false)
    private fun draft(reason: String = "SIZE") = ExchangeDraft("payment", listOf(ExchangeReturnedInput("line", 1, true, reason)),
        listOf(ExchangeTakenInput("product", 1, displayedUnitPriceCents = 30000)), ExchangeSettlementDraft("PAYOUT", -20000))
    private fun completed() = ExchangeResult("exchange", "COMPLETED", "original", "ORD-1", "new", "ORD-2", 50000, 30000, -20000,
        ExchangeResultSettlement("PAYOUT", "STORE_CREDIT", 20000), ExchangeResultLines(emptyList(), emptyList()))

    @Test fun `el saldo a favor va por defecto al cliente de la venta y Cambiar lo reemplaza`() {
        coEvery { store.load(any(), any()) } returns null
        val vm = vm()
        vm.open(Transaction("payment", customerId = "ana", customerName = "Ana López"))
        assertEquals(ExchangeStoreCreditCustomer("ana", "Ana López"), vm.storeCreditCustomer.value)
        vm.chooseCustomer(com.avoqado.pos.customers.data.model.Customer("mama", firstName = "Rosa", lastName = "López"))
        assertEquals(ExchangeStoreCreditCustomer("mama", "Rosa López", null, phoneChecked = true), vm.storeCreditCustomer.value)
        vm.open(Transaction("venta-sin-cliente"))
        assertNull(vm.storeCreditCustomer.value)
    }

    @Test fun `con sólo saldo el diálogo deja sólo el saldo a favor salvo que una pieza tenga Defecto`() {
        coEvery { store.load(any(), any()) } returns null
        storeCreditOnly.value = true
        val vm = vm()
        vm.open(Transaction("payment", customerId = "ana", customerName = "Ana López",
            payoutOptions = listOf(com.avoqado.pos.transactions.data.model.PayoutOption("CASH", 50000, 50000, false), saldo)))
        assertEquals(listOf("STORE_CREDIT"), vm.options(draft()).map { it.method })
        assertEquals(listOf("STORE_CREDIT", "CASH"), vm.options(draft("DEFECT")).map { it.method })
        storeCreditOnly.value = false
        assertEquals(listOf("STORE_CREDIT", "CASH"), vm.options(draft()).map { it.method })
    }

    @Test fun `un rechazo por tope deja corregir y continuar con las mismas prendas`() {
        coEvery { store.load(any(), any()) } returns null
        coEvery { store.save(any()) } answers { firstArg<PendingExchange>() }
        coEvery { store.remove(any()) } just Runs
        val tope = "El saldo a favor de Ana López pasaría de $10,000. Devuelve $150.00 de otra forma o elige a otra persona."
        val bodies = mutableListOf<String>()
        coEvery { repo.create(any(), "venue") } coAnswers {
            bodies += firstArg<String>()
            if (bodies.size == 1) throw ExchangeApiException(422, "STORE_CREDIT_OVER_CAP", tope) else completed()
        }
        val vm = vm()
        vm.open(Transaction("payment", customerId = "ana", customerName = "Ana López", payoutOptions = listOf(saldo)))
        vm.begin(draft()); vm.choose(vm.options(draft()).single())
        assertEquals(tope, vm.state.value.message)
        assertFalse(vm.state.value.locked)
        assertEquals(ExchangeSendPhase.EDITING, vm.state.value.phase)
        coVerify(exactly = 1) { store.remove(any()) }
        vm.chooseCustomer(com.avoqado.pos.customers.data.model.Customer("hija", firstName = "Sofía", lastName = "López", phone = "+525512345678"))
        vm.begin(draft()); vm.choose(vm.options(draft()).single())
        assertEquals(ExchangeSendPhase.COMPLETED, vm.state.value.phase)
        val (first, second) = bodies.map { Json.parseToJsonElement(it).jsonObject }
        fun customer(body: JsonObject) = body.getValue("settlement").jsonObject.getValue("customerId").jsonPrimitive.content
        assertEquals("ana", customer(first)); assertEquals("hija", customer(second))
        assertEquals("STORE_CREDIT", second.getValue("settlement").jsonObject.getValue("method").jsonPrimitive.content)
        assertEquals(first["returned"], second["returned"]); assertEquals(first["taken"], second["taken"])
        assertNotEquals(first["idempotencyKey"], second["idempotencyKey"])
    }

    // MARK: - Teléfono obligatorio (r1 I2): se pide ahí mismo, se guarda con la edición de cliente de siempre y el Cambio sigue.
    private val mama = com.avoqado.pos.customers.data.model.Customer("mama", firstName = "Rosa", lastName = "López")
    private fun sinTelefono() = TextosCambio.SALDO_SIN_TELEFONO.replace("{nombre}", "Rosa López")
    private fun abierto(): ExchangePaymentViewModel {
        coEvery { store.load(any(), any()) } returns null
        coEvery { store.save(any()) } answers { firstArg<PendingExchange>() }
        coEvery { store.remove(any()) } just Runs
        return vm().also { it.open(Transaction("payment", payoutOptions = listOf(saldo))); it.begin(draft()); it.chooseCustomer(mama) }
    }

    @Test fun `un cliente sin teléfono se pide, se guarda y el cambio continúa`() {
        val bodies = mutableListOf<String>()
        coEvery { repo.create(any(), "venue") } coAnswers { bodies += firstArg<String>(); completed() }
        coEvery { customers.updateCustomer(mama, com.avoqado.pos.customers.data.model.UpdateCustomerRequest(phone = "5512345678")) } returns
            Result.success(mama.copy(phone = "5512345678"))
        val vm = abierto()
        assertEquals(sinTelefono(), vm.options(draft()).single().disabledReason)
        vm.saveStoreCreditPhone("55 1234 5678")
        assertEquals(ExchangeStoreCreditCustomer("mama", "Rosa López", "5512345678", phoneChecked = true), vm.storeCreditCustomer.value)
        assertNull(vm.message.value)
        assertEquals(draft(), vm.draft.value)
        vm.choose(vm.options(draft()).single())
        assertEquals(ExchangeSendPhase.COMPLETED, vm.state.value.phase)
        assertEquals("mama", Json.parseToJsonElement(bodies.single()).jsonObject.getValue("settlement").jsonObject
            .getValue("customerId").jsonPrimitive.content)
    }

    @Test fun `si guardar el teléfono falla se queda con el motivo y no se pierde lo capturado`() {
        coEvery { customers.updateCustomer(any(), any()) } returns
            Result.failure(com.avoqado.pos.customers.data.RechazoAlEditarCliente(403, "No tienes permiso para editar clientes."))
        val vm = abierto()
        vm.saveStoreCreditPhone("5512345678")
        assertEquals("No tienes permiso para editar clientes.", vm.message.value)
        assertEquals(draft(), vm.draft.value)
        assertEquals(ExchangeStoreCreditCustomer("mama", "Rosa López", null, phoneChecked = true), vm.storeCreditCustomer.value)
        assertEquals(sinTelefono(), vm.options(draft()).single().disabledReason)
        coEvery { customers.updateCustomer(any(), any()) } returns Result.failure(java.io.IOException("timeout"))
        vm.saveStoreCreditPhone("5512345678")
        assertEquals(TextosCambio.SALDO_SIN_RED, vm.message.value)
        assertEquals(draft(), vm.draft.value)
        coVerify(exactly = 0) { store.save(any()) }
        coVerify(exactly = 0) { repo.create(any(), any()) }
    }

    @Test fun `sin red el teléfono no se guarda ni se encola`() {
        val vm = abierto()
        every { monitor.isOnline() } returns false
        vm.saveStoreCreditPhone("5512345678")
        assertEquals(TextosCambio.SALDO_SIN_RED, vm.message.value)
        assertEquals(draft(), vm.draft.value)
        coVerify(exactly = 0) { customers.updateCustomer(any(), any()) }
    }

    @Test fun `un teléfono que no sirve no se manda`() {
        val vm = abierto()
        vm.saveStoreCreditPhone("123")
        assertEquals(TextosCambio.SALDO_TELEFONO_INVALIDO, vm.message.value)
        vm.saveStoreCreditPhone("   ")
        assertEquals(TextosCambio.SALDO_TELEFONO_INVALIDO, vm.message.value)
        coVerify(exactly = 0) { customers.updateCustomer(any(), any()) }
    }

    @Test fun `si el servidor pide el teléfono de la clienta de la venta, la app lo pide`() {
        coEvery { store.load(any(), any()) } returns null
        coEvery { store.save(any()) } answers { firstArg<PendingExchange>() }
        coEvery { store.remove(any()) } just Runs
        coEvery { repo.create(any(), "venue") } throws ExchangeApiException(400, "STORE_CREDIT_CUSTOMER_PHONE_REQUIRED",
            "Para darle saldo a favor a Ana López hace falta su teléfono: con él lo usa. Agrégalo a su ficha.")
        val vm = vm()
        vm.open(Transaction("payment", customerId = "ana", customerName = "Ana López", payoutOptions = listOf(saldo)))
        vm.begin(draft()); vm.choose(vm.options(draft()).single())
        assertFalse(vm.state.value.locked)
        assertEquals(ExchangeStoreCreditCustomer("ana", "Ana López", null, phoneChecked = true), vm.storeCreditCustomer.value)
        assertEquals(TextosCambio.SALDO_SIN_TELEFONO.replace("{nombre}", "Ana López"), vm.options(draft()).single().disabledReason)
    }

    @Test fun `sin a quién abonar el saldo a favor no se manda`() {
        coEvery { store.load(any(), any()) } returns null
        val vm = vm()
        vm.open(Transaction("payment", payoutOptions = listOf(saldo)))
        vm.begin(draft())
        val option = vm.options(draft()).single()
        assertEquals(TextosCambio.SALDO_ELIGE_CLIENTE, option.disabledReason)
        vm.choose(option)
        coVerify(exactly = 0) { store.save(any()) }
        coVerify(exactly = 0) { repo.create(any(), any()) }
    }

    // MARK: - Codex apps r5: el desenlace del alta es ESTADO con su número, y sólo lo aplica el formulario EXACTO que la pidió.
    private fun beatriz() = com.avoqado.pos.customers.data.model.Customer("beatriz", firstName = "Beatriz", phone = "+525533334444")
    private fun cambio(clientes: com.avoqado.pos.customers.presentation.CustomersViewModel, payments: ExchangePaymentViewModel) =
        PantallaConAlta(clientes, sobreviveAlGiro = false) { payments.chooseCustomer(it); cerrarFormulario() }

    @Test fun `Cambio - girar con el formulario perdido, escoger a Beatriz y que llegue Ana - sigue Beatriz`() {
        val llegaAna = kotlinx.coroutines.CompletableDeferred<Unit>()
        val clientes = clientesConAltaEnVuelo(llegaAna, "ana", "Ana")
        val payments = vm()
        val pantalla = cambio(clientes, payments)
        pantalla.abrirFormulario(); pantalla.guardar("Ana")
        pantalla.girar()                       // el selector y el formulario del Cambio usan `remember`: no vuelven
        payments.chooseCustomer(beatriz())     // «Cambiar» otra vez → Beatriz de la lista
        llegaAna.complete(Unit)
        pantalla.componer()
        assertEquals("beatriz", payments.storeCreditCustomer.value?.id)
        // Ni un formulario nuevo hereda el alta de Ana.
        pantalla.abrirFormulario(); pantalla.componer()
        assertEquals("beatriz", payments.storeCreditCustomer.value?.id)
        assertEquals("Ana quedó creada, nunca ligada", "ana", clientes.customers.value.first().id)
    }

    @Test fun `Cambio - cerrar el formulario a propósito y que el alta llegue tarde - se ignora`() {
        val llegaAna = kotlinx.coroutines.CompletableDeferred<Unit>()
        val clientes = clientesConAltaEnVuelo(llegaAna, "ana", "Ana")
        val payments = vm()
        val pantalla = cambio(clientes, payments)
        pantalla.abrirFormulario(); pantalla.guardar("Ana")
        pantalla.cerrarFormulario()
        payments.chooseCustomer(beatriz())
        llegaAna.complete(Unit)
        pantalla.componer(); pantalla.abrirFormulario(); pantalla.componer()
        assertEquals("beatriz", payments.storeCreditCustomer.value?.id)
        // Con su formulario abierto, su propia alta sí escoge (y lo cierra).
        val llegaCarla = kotlinx.coroutines.CompletableDeferred<Unit>()
        val otra = cambio(clientesConAltaEnVuelo(llegaCarla, "carla", "Carla"), payments)
        otra.abrirFormulario(); otra.guardar("Carla")
        llegaCarla.complete(Unit)
        otra.componer()
        assertEquals("carla", payments.storeCreditCustomer.value?.id)
        assertFalse(otra.formularioAbierto)
    }

    @Test fun `cobro - girar con el formulario restaurado liga a Ana y cierra el formulario restaurado`() {
        // El recibo guarda su bandera y el número del alta con `rememberSaveable`: el formulario vuelve tras girar.
        val llegaAna = kotlinx.coroutines.CompletableDeferred<Unit>()
        val clientes = clientesConAltaEnVuelo(llegaAna, "ana", "Ana")
        val ligadas = mutableListOf<String>()
        // `attachCustomer` de la composición vigente: liga al cobro y cierra el formulario restaurado.
        val recibo = PantallaConAlta(clientes, sobreviveAlGiro = true) { ligadas += it.id; cerrarFormulario() }
        recibo.abrirFormulario(); recibo.guardar("Ana")
        recibo.girar()
        llegaAna.complete(Unit)                // puede llegar mientras la actividad se recrea: el estado espera
        recibo.componer(); recibo.componer()
        assertEquals(listOf("ana"), ligadas)   // una sola vez
        assertFalse(recibo.formularioAbierto)
        assertNull(recibo.alta)
    }

    @Test fun `un rechazo en el formulario restaurado se ve, y el de un formulario perdido o cerrado no`() {
        for (sobrevive in listOf(true, false)) {
            val llega = kotlinx.coroutines.CompletableDeferred<Unit>()
            val fallan = clientesConAltaEnVuelo(llega, "ana", "Ana", falla = "Este número de teléfono ya está registrado.")
            val pantalla = PantallaConAlta(fallan, sobrevive) { fail("no se creó") }
            pantalla.abrirFormulario(); pantalla.guardar("Ana")
            pantalla.girar()
            llega.complete(Unit)
            pantalla.componer()
            if (sobrevive) assertNotNull(pantalla.rechazoVisible)
            else {
                assertNull(pantalla.rechazoVisible)
                pantalla.abrirFormulario(); pantalla.componer()
                assertNull("un formulario nuevo no hereda el rechazo de antes", pantalla.rechazoVisible)
            }
        }
    }

    // MARK: - A5 O-3: la clienta de la venta se muestra con su teléfono, el que manda el servidor, como la escogida de la lista.
    @Test fun `O-3 en el Cambio la clienta de la venta muestra el teléfono que manda el servidor`() {
        coEvery { store.load("venue", "payment") } returns null
        coEvery { customers.buscarClientes("Ana López") } returns Result.success(com.avoqado.pos.customers.data.model.PaginaDeClientes(listOf(
            com.avoqado.pos.customers.data.model.Customer("otra", firstName = "Ana", lastName = "López", phone = "+525599999999"),
            com.avoqado.pos.customers.data.model.Customer("ana", firstName = "Ana", lastName = "López", phone = "+525511112222"),
        ), 2))
        val payments = vm()
        payments.open(Transaction("payment", customerId = "ana", customerName = "Ana López"))
        assertEquals("+525511112222", payments.storeCreditCustomer.value?.phone)
        assertTrue(payments.storeCreditCustomer.value!!.phoneChecked)
        assertEquals("Saldo a favor para: Ana López · +525511112222", ExchangeSettlement.storeCreditCustomerLabel(payments.storeCreditCustomer.value))
    }
}
