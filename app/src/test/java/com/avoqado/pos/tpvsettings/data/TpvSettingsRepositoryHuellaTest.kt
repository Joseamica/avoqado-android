package com.avoqado.pos.tpvsettings.data

import com.avoqado.pos.core.data.local.PreferencesDataStore
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.PlanSnapshot
import com.avoqado.pos.customerdisplay.CanonicalDeviceIdProvider
import com.avoqado.pos.customerdisplay.DisplayModeAuthorityGate
import com.avoqado.pos.customerdisplay.DisplayModeJournalState
import com.avoqado.pos.customerdisplay.DisplayModePrefs
import com.avoqado.pos.customerdisplay.DisplayModeRequestJournal
import com.avoqado.pos.customerdisplay.JournalEntry
import com.avoqado.pos.printing.receiptlayout.CanonicalLayout
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Task 4.1 (pantalla del cliente F2, Bloque 4): los ajustes que lee el cobro (propina, estrellas, «Ofrecer recibo», PIN de
 * gerente, plan) se refrescan cuando la huella del poll cambia, y NINGUNA respuesta que ya no sea la vigente los escribe:
 * ni la de otra sucursal, ni la de una sesión cerrada, ni una vieja de la misma sucursal, ni un GET anterior a un PATCH.
 * Lo que el servidor marca `"failed"` en `componentsStatus` se conserva como estaba.
 *
 * Es la familia que en iOS costó tres rondas de Codex (`docs/audit/2026-10-18-pantalla-cliente-f2/codex-bloque-5-r3.md`,
 * H2 y H3); aquí se cierra con un boleto por petición.
 */
class TpvSettingsRepositoryHuellaTest {

    // ── respuestas viejas o ajenas ────────────────────────────────────────────────────────────

    @Test
    fun `una respuesta tardia de la sucursal anterior no pisa la actual`() = runTest {
        val deA = Paso(respuesta(terminal = "terminal-a", verCalificacion = false, pin = true, plan = "PREMIUM")).retenido()
        val deB = Paso(respuesta(terminal = "terminal-b", verCalificacion = true, pin = false, plan = "PRO"))
        val repo = repositorio(Guion(deA, deB))

        val a = enVuelo(repo, A, deA)
        sesion.venue = B
        repo.refreshSettingsForVenue(B)
        soltar(a, deA)

        quedanLosDeB(repo)
        assertFalse("la respuesta de A no puede guardar su plan", sesion.planes.containsKey(A))
    }

    @Test
    fun `logout y entrar a otra sucursal con un refresco de A en vuelo - B selecciona sus ajustes y A no escribe`() = runTest {
        val deA = Paso(respuesta(terminal = "terminal-a", verCalificacion = false, pin = true, plan = "PREMIUM")).retenido()
        val deB = Paso(respuesta(terminal = "terminal-b", verCalificacion = true, pin = false, plan = "PRO"))
        val repo = repositorio(Guion(deA, deB))

        val a = enVuelo(repo, A, deA)
        repo.clearCache() // logout
        sesion.venue = B // login en B
        repo.refreshSettingsForVenue(B)
        soltar(a, deA)

        quedanLosDeB(repo)
        assertFalse(sesion.planes.containsKey(A))
    }

    @Test
    fun `cambiar de sucursal despues de pasar la primera guardia no deja escribir el resto`() = runTest {
        val disco = mutableMapOf<String, String>()
        val navegacion = Retencion()
        val repo = repositorio(
            Guion(Paso(respuesta(verCalificacion = false, pin = true, plan = "PREMIUM"))),
            preferencias = preferenciasEn(disco, retenerEscrituraDe = "terminal_navigation_$A", retencion = navegacion),
        )

        val a = async(Dispatchers.Default) { repo.refreshSettingsForVenue(A) }
        navegacion.esperarQueEntre()
        repo.clearCache()
        sesion.venue = B
        navegacion.soltar()
        terminar(a)

        nadaDeAQuedoEscrito(repo, disco)
    }

    @Test
    fun `salir y volver a la misma sucursal descarta la respuesta vieja`() = runTest {
        val disco = mutableMapOf<String, String>()
        val navegacion = Retencion()
        val repo = repositorio(
            Guion(Paso(respuesta(verCalificacion = false, pin = true, plan = "PREMIUM"))),
            preferencias = preferenciasEn(disco, retenerEscrituraDe = "terminal_navigation_$A", retencion = navegacion),
        )

        val a = async(Dispatchers.Default) { repo.refreshSettingsForVenue(A) }
        navegacion.esperarQueEntre()
        repo.clearCache() // logout; el login vuelve a A: la sucursal es la misma, la sesión no
        navegacion.soltar()
        terminar(a)

        nadaDeAQuedoEscrito(repo, disco)
    }

    @Test
    fun `volver a A tras B - la respuesta vieja de A no pisa la nueva`() = runTest {
        val disco = mutableMapOf<String, String>()
        val a1 = Paso(respuesta(terminal = "terminal-a1", verCalificacion = false, pin = true)).retenido()
        val b = Paso(respuesta(terminal = "terminal-b"))
        val a2 = Paso(respuesta(terminal = "terminal-a2", verCalificacion = true, pin = false))
        val repo = repositorio(Guion(a1, b, a2), preferencias = preferenciasEn(disco))

        val viejo = enVuelo(repo, A, a1)
        sesion.venue = B
        repo.refreshSettingsForVenue(B)
        sesion.venue = A
        repo.refreshSettingsForVenue(A)
        soltar(viejo, a1)

        assertEquals("terminal-a2", repo.terminalNavigation.value.terminalId)
        assertEquals(true, repo.settings.value.showReviewScreen)
        assertEquals(false, repo.managerPinOverrideEnabled.value)
        assertFalse(sesion.pin)
        val frio = arranqueEnFrioSinRed(disco)
        assertEquals("el disco de A se quedó con la respuesta vieja", "terminal-a2", frio.terminalNavigation.value.terminalId)
        assertEquals(true, frio.settings.value.showReviewScreen)
    }

    @Test
    fun `un GET anterior al PATCH no revierte lo guardado`() = runTest {
        val disco = mutableMapOf<String, String>()
        val getViejo = Paso(respuesta(verCalificacion = true)).retenido()
        val repo = repositorio(
            Guion(Paso(respuesta(verCalificacion = true)), getViejo, Paso("{}")),
            preferencias = preferenciasEn(disco),
        )
        repo.refreshSettingsForVenue(A)

        val get = enVuelo(repo, A, getViejo)
        assertEquals(AjusteGuardado.Ok, repo.guardarPantallasDelCobro(showReviewScreen = false))
        soltar(get, getViejo)

        assertEquals(false, repo.settings.value.showReviewScreen)
        assertEquals(false, arranqueEnFrioSinRed(disco).settings.value.showReviewScreen)
    }

    @Test
    fun `un 4xx viejo no borra el cache bueno`() = runTest {
        val disco = mutableMapOf<String, String>()
        val negacionVieja = Paso("""{"error":"Forbidden"}""", codigo = 403).retenido()
        val repo = repositorio(
            Guion(negacionVieja, Paso(respuesta(terminal = "terminal-nueva", verCalificacion = false))),
            preferencias = preferenciasEn(disco),
        )

        val viejo = enVuelo(repo, A, negacionVieja)
        repo.refreshSettingsForVenue(A)
        soltar(viejo, negacionVieja)

        assertEquals("terminal-nueva", repo.terminalNavigation.value.terminalId)
        assertEquals(false, repo.settings.value.showReviewScreen)
        val frio = arranqueEnFrioSinRed(disco)
        assertEquals("el 403 viejo borró el caché bueno", "terminal-nueva", frio.terminalNavigation.value.terminalId)
        assertEquals(false, frio.settings.value.showReviewScreen)
    }

    @Test
    fun `una peticion vencida a mitad de la hidratacion no deja la sucursal en fabrica`() = runTest {
        // Lección iOS #2: R1 selecciona A y se queda leyendo el disco; R2 (poll, ON_RESUME o login de la MISMA sucursal) la
        // vence y falla por red. Sin red, A tiene que quedarse con lo de su disco, no con los valores de fábrica.
        val disco = mutableMapOf<String, String>()
        repositorio(Guion(Paso(respuesta(terminal = "terminal-cache", verCalificacion = false))), preferencias = preferenciasEn(disco))
            .refreshSettingsForVenue(A)
        val lectura = Retencion()
        val repo = repositorio(
            Guion(Paso(error = IOException("offline")), Paso(error = IOException("offline"))),
            preferencias = preferenciasEn(disco, retenerLecturaDe = "tpv_settings_$A", retencionLectura = lectura),
        )

        val r1 = async(Dispatchers.Default) { repo.refreshSettingsForVenue(A) }
        lectura.esperarQueEntre()
        repo.refreshSettingsForVenue(A)
        lectura.soltar()
        terminar(r1)

        assertEquals("terminal-cache", repo.terminalNavigation.value.terminalId)
        assertEquals(false, repo.settings.value.showReviewScreen)
    }

    @Test
    fun `un PATCH de A que termina tras cambiar a B no vence el refresco de B`() = runTest {
        val patch = Paso("{}").retenido()
        val deB = Paso(respuesta(terminal = "terminal-b", verCalificacion = true, pin = false, plan = "PRO")).retenido()
        val repo = repositorio(Guion(Paso(respuesta(terminal = "terminal-a", verCalificacion = false)), patch, deB))
        repo.refreshSettingsForVenue(A)

        val guardado = async(Dispatchers.Default) { repo.guardarPantallasDelCobro(showReviewScreen = false) }
        patch.esperarQueSalga()
        repo.clearCache()
        sesion.venue = B
        val b = enVuelo(repo, B, deB)
        patch.soltar()
        withContext(Dispatchers.Default) { withTimeout(30_000) { guardado.await() } }
        soltar(b, deB)

        quedanLosDeB(repo)
    }

    @Test
    fun `clearCache baja el PIN de gerente guardado`() = runTest {
        sesion.pin = true
        val repo = repositorio(Guion())

        repo.clearCache()

        assertFalse("la siguiente sucursal sin red heredaría el PIN de la anterior", sesion.pin)
        assertFalse(repo.managerPinOverrideEnabled.value)
    }

    // ── componentsStatus ──────────────────────────────────────────────────────────────────────

    @Test
    fun `promotionsPanel failed conserva panel y PIN`() = runTest {
        val repo = repositorio(
            Guion(
                Paso(respuesta(panel = "HIDDEN", pin = true, verCalificacion = true)),
                // Con la lectura fallida el servidor manda los DEFAULTS del panel y del PIN: no son la verdad.
                Paso(respuesta(panel = "TAB", pin = false, verCalificacion = false, estados = estados(promotionsPanel = "failed"))),
            ),
        )
        repo.refreshSettingsForVenue(A)
        repo.refreshSettingsForVenue(A)

        assertEquals(PromotionsPanelSettings(PanelMode.HIDDEN, PanelMode.HIDDEN), repo.settings.value.promotions)
        assertTrue(repo.managerPinOverrideEnabled.value)
        assertTrue(sesion.pin)
        assertEquals("lo que no falló sí se aplica", false, repo.settings.value.showReviewScreen)
    }

    @Test
    fun `plan failed conserva el plan`() = runTest {
        val repo = repositorio(
            Guion(
                Paso(respuesta(plan = "PRO")),
                Paso(respuesta(plan = "FREE", estados = estados(plan = "failed"))),
            ),
        )
        repo.refreshSettingsForVenue(A)
        repo.refreshSettingsForVenue(A)

        assertEquals("PRO", sesion.planes[A]?.tier)
    }

    @Test
    fun `receipt failed conserva el ticket`() = runTest {
        val repo = repositorio(
            Guion(
                Paso(respuesta(recibo = 7)),
                // `receipt` es `ok` sólo con encabezado Y receta: `failed` puede traer media pareja.
                Paso(respuesta(recibo = 9, estados = estados(receipt = "failed"))),
            ),
        )
        repo.refreshSettingsForVenue(A)
        repo.refreshSettingsForVenue(A)

        assertEquals(7, repo.receiptTicketFor(A).layout?.revision)
    }

    @Test
    fun `servidor viejo sin componentsStatus se comporta como hoy`() = runTest {
        val repo = repositorio(Guion(Paso(respuesta(panel = "HIDDEN", pin = true, plan = "PRO", recibo = 7, verCalificacion = false))))

        repo.refreshSettingsForVenue(A)

        assertEquals(PromotionsPanelSettings(PanelMode.HIDDEN, PanelMode.HIDDEN), repo.settings.value.promotions)
        assertTrue(repo.managerPinOverrideEnabled.value)
        assertTrue(sesion.pin)
        assertEquals("PRO", sesion.planes[A]?.tier)
        assertEquals(7, repo.receiptTicketFor(A).layout?.revision)
        assertEquals(false, repo.settings.value.showReviewScreen)
    }

    // ── huella ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `huella distinta refresca y la igual no`() = runTest {
        val guion = Guion(Paso(respuesta(huella = HUELLA_1)), Paso(respuesta(huella = HUELLA_2)))
        val repo = repositorio(guion)
        repo.refreshSettingsForVenue(A)

        repo.refreshIfFingerprintChanged(A, HUELLA_1)
        assertEquals("la misma huella no vuelve a pedir", 1, guion.llamadas.get())

        repo.refreshIfFingerprintChanged(A, HUELLA_2)
        assertEquals("una huella nueva sí refresca", 2, guion.llamadas.get())
        repo.refreshIfFingerprintChanged(A, HUELLA_2)
        assertEquals(2, guion.llamadas.get())
    }

    @Test
    fun `con un failed no se guarda la huella y el siguiente poll reintenta`() = runTest {
        val guion = Guion(
            Paso(respuesta(huella = HUELLA_1, estados = estados(plan = "failed"))),
            Paso(respuesta(huella = HUELLA_1)),
        )
        val repo = repositorio(guion)
        repo.refreshSettingsForVenue(A)

        repo.refreshIfFingerprintChanged(A, HUELLA_1)

        assertEquals(2, guion.llamadas.get())
    }

    @Test
    fun `dos huellas seguidas no lanzan dos refrescos a la vez`() = runTest {
        val primero = Paso(respuesta(huella = HUELLA_1)).retenido()
        val guion = Guion(primero, Paso(respuesta(huella = HUELLA_2)))
        val repo = repositorio(guion)

        val enCurso = async(Dispatchers.Default) { repo.refreshIfFingerprintChanged(A, HUELLA_1) }
        primero.esperarQueSalga()
        repo.refreshIfFingerprintChanged(A, HUELLA_2)
        assertEquals("con uno en vuelo, el segundo no sale", 1, guion.llamadas.get())
        soltar(enCurso, primero)
    }

    @Test
    fun `un poll de la sucursal anterior no tumba el refresco de la actual`() = runTest {
        val deB = Paso(respuesta(terminal = "terminal-b", verCalificacion = true, pin = false, plan = "PRO")).retenido()
        val guion = Guion(deB)
        sesion.venue = B
        val repo = repositorio(guion)

        val b = enVuelo(repo, B, deB)
        repo.refreshIfFingerprintChanged(A, HUELLA_1) // un poll que salió con la sesión de A
        soltar(b, deB)

        assertEquals("el poll viejo no puede salir a la red", 1, guion.llamadas.get())
        quedanLosDeB(repo)
    }

    // ── comprobaciones ────────────────────────────────────────────────────────────────────────

    private fun quedanLosDeB(repo: TpvSettingsRepository) {
        assertEquals("terminal-b", repo.terminalNavigation.value.terminalId)
        assertEquals(true, repo.settings.value.showReviewScreen)
        assertEquals(false, repo.managerPinOverrideEnabled.value)
        assertFalse("el PIN guardado es una llave global: A lo dejó prendido para B", sesion.pin)
        assertEquals("PRO", sesion.planes[B]?.tier)
    }

    private fun nadaDeAQuedoEscrito(repo: TpvSettingsRepository, disco: Map<String, String>) {
        assertEquals(TpvSettings.DEFAULT, repo.settings.value)
        assertFalse(repo.managerPinOverrideEnabled.value)
        assertFalse(sesion.pin)
        assertFalse(sesion.planes.containsKey(A))
        assertNull("los ajustes de A no se guardan en disco", disco["tpv_settings_$A"])
    }

    // ── andamiaje ─────────────────────────────────────────────────────────────────────────────

    /** La sesión del aparato: sucursal activa, PIN global guardado y planes por sucursal. */
    private class Sesion {
        @Volatile var venue: String? = A
        @Volatile var pin = false
        val planes = java.util.concurrent.ConcurrentHashMap<String, PlanSnapshot>()
        val storage: SecureStorage = mockk<SecureStorage>(relaxed = true).also { s ->
            every { s.accessToken } returns "access-token"
            every { s.venueId } answers { venue }
            every { s.managerPinOverrideEnabled } answers { pin }
            every { s.managerPinOverrideEnabled = any() } answers { pin = firstArg() }
            every { s.storePlanSnapshot(any(), any()) } answers {
                secondArg<PlanSnapshot?>()?.let { planes[firstArg()] = it }
                Unit
            }
        }
    }

    private val sesion = Sesion()

    private fun repositorio(
        guion: Guion,
        preferencias: PreferencesDataStore = preferenciasEn(mutableMapOf()),
    ) = TpvSettingsRepository(
        secureStorage = sesion.storage,
        client = guion.client,
        preferencesDataStore = preferencias,
        displayModePrefs = mockk<DisplayModePrefs>(relaxed = true) {
            every { inverted } returns MutableStateFlow(false)
            every { dirty } returns MutableStateFlow(false)
            every { generation } returns MutableStateFlow(0L)
        },
        displayModeJournal = object : DisplayModeRequestJournal {
            override fun readState(venueId: String, deviceId: String) = DisplayModeJournalState.Empty
            override fun save(entry: JournalEntry) = Unit
            override fun clear(venueId: String, deviceId: String, requestId: String) = Unit
        },
        deviceIdProvider = CanonicalDeviceIdProvider { "device-test" },
        displayModeAuthorityGate = DisplayModeAuthorityGate(),
    )

    /** Proceso nuevo, sin red, sobre el MISMO disco: lo que quedó guardado para A. */
    private suspend fun arranqueEnFrioSinRed(disco: MutableMap<String, String>): TpvSettingsRepository {
        sesion.venue = A
        val repo = repositorio(Guion(Paso(error = IOException("offline"))), preferencias = preferenciasEn(disco))
        repo.refreshSettingsForVenue(A)
        return repo
    }

    /** Una escritura del disco que se queda a medias hasta que la prueba la suelte. */
    private class Retencion {
        private val entro = CountDownLatch(1)
        private val soltada = CountDownLatch(1)
        fun bloquear() {
            entro.countDown()
            check(soltada.await(30, TimeUnit.SECONDS)) { "la escritura se soltó por el plazo, no por la prueba" }
        }
        suspend fun esperarQueEntre() =
            assertTrue("la escritura nunca empezó", withContext(Dispatchers.Default) { entro.await(10, TimeUnit.SECONDS) })
        fun soltar() = soltada.countDown()
    }

    private fun preferenciasEn(
        disco: MutableMap<String, String>,
        retenerEscrituraDe: String? = null,
        retencion: Retencion? = null,
        retenerLecturaDe: String? = null,
        retencionLectura: Retencion? = null,
    ): PreferencesDataStore = mockk<PreferencesDataStore>(relaxed = true).also { p ->
        val primeraLectura = java.util.concurrent.atomic.AtomicBoolean(true)
        every { p.getString(any()) } answers {
            val llave = firstArg<String>()
            if (llave == retenerLecturaDe && primeraLectura.getAndSet(false)) {
                // Sólo la PRIMERA lectura de esa llave se queda a medias; las demás contestan al instante.
                kotlinx.coroutines.flow.flow {
                    retencionLectura?.bloquear()
                    emit(synchronized(disco) { disco[llave] })
                }
            } else {
                flowOf(synchronized(disco) { disco[llave] })
            }
        }
        every { p.getBooleanOrNull(any()) } returns flowOf(null)
        coEvery { p.setString(any(), any()) } answers {
            if (firstArg<String>() == retenerEscrituraDe) retencion?.bloquear()
            synchronized(disco) { disco[firstArg()] = secondArg() }
        }
        coEvery { p.removeString(any()) } answers { synchronized(disco) { disco.remove(firstArg<String>()) }; Unit }
        coEvery { p.updateStrings(any(), any()) } coAnswers {
            val keys: List<String> = firstArg()
            val transform: (Map<String, String?>) -> Map<String, String?> = secondArg()
            synchronized(disco) {
                transform(keys.associateWith { disco[it] }).forEach { (k, v) -> if (v == null) disco.remove(k) else disco[k] = v }
            }
        }
    }

    /** Una respuesta del servidor; si está `retenido`, se queda en la red hasta que la prueba la suelte. */
    private class Paso(
        private val cuerpo: String = "",
        private val codigo: Int = 200,
        private val error: IOException? = null,
    ) {
        private var retener = false
        private val salio = CountDownLatch(1)
        private val soltada = CountDownLatch(1)
        fun retenido() = apply { retener = true }
        suspend fun esperarQueSalga() =
            assertTrue("la petición nunca salió a la red", withContext(Dispatchers.Default) { salio.await(10, TimeUnit.SECONDS) })
        fun soltar() = soltada.countDown()
        fun responder(request: Request): Response {
            salio.countDown()
            if (retener) check(soltada.await(30, TimeUnit.SECONDS)) { "la respuesta se soltó por el plazo, no por la prueba" }
            error?.let { throw it }
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(codigo)
                .message(if (codigo in 200..299) "OK" else "Error")
                .body(cuerpo.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    /** Las respuestas en el orden en que salen las peticiones. */
    private class Guion(vararg pasos: Paso) {
        private val pasos = pasos.toList()
        val llamadas = AtomicInteger(0)
        val metodos = CopyOnWriteArrayList<String>()
        val client: OkHttpClient = mockk {
            every { newCall(any()) } answers {
                val request = firstArg<Request>()
                val paso = pasos[llamadas.getAndIncrement()]
                metodos += request.method
                mockk<Call> { every { execute() } answers { paso.responder(request) } }
            }
        }
    }

    private suspend fun TestScope.enVuelo(repo: TpvSettingsRepository, venueId: String, paso: Paso): Deferred<Unit> {
        val refresco = async(Dispatchers.Default) { repo.refreshSettingsForVenue(venueId) }
        paso.esperarQueSalga()
        return refresco
    }

    private suspend fun soltar(refresco: Deferred<Unit>, paso: Paso) {
        paso.soltar()
        terminar(refresco)
    }

    private suspend fun terminar(refresco: Deferred<Unit>) =
        withContext(Dispatchers.Default) { withTimeout(30_000) { refresco.await() } }

    private fun estados(
        receipt: String = "ok",
        plan: String = "ok",
        promotionsPanel: String = "ok",
        customerDisplay: String = "not_applicable",
    ) = """{"receipt":"$receipt","plan":"$plan","promotionsPanel":"$promotionsPanel","customerDisplay":"$customerDisplay"}"""

    private fun respuesta(
        terminal: String = "terminal-a",
        verCalificacion: Boolean = true,
        pin: Boolean = false,
        plan: String? = null,
        panel: String? = null,
        estados: String? = null,
        huella: String? = null,
        recibo: Int? = null,
    ): String {
        val campos = listOfNotNull(
            """"settings":{"showReviewScreen":$verCalificacion}""",
            """"deviceTerminal":{"id":"$terminal"}""",
            """"managerPinOverrideEnabled":$pin""",
            plan?.let { """"plan":{"tier":"$it"}""" },
            panel?.let { """"promotions":{"panelCashier":"$it","panelCustomer":"$it"}""" },
            estados?.let { """"componentsStatus":$it""" },
            huella?.let { """"fingerprints":{"settings":"$it"}""" },
            recibo?.let { """"receiptLayout":{"schemaVersion":1,"revision":$it,"blocks":${CanonicalLayout.JSON}}""" },
        )
        return """{"success":true,"data":{${campos.joinToString(",")}}}"""
    }

    private companion object {
        const val A = "venue-a"
        const val B = "venue-b"
        const val HUELLA_1 = "0123456789abcdef0123456789abcdef"
        const val HUELLA_2 = "fedcba9876543210fedcba9876543210"
    }
}
