package com.avoqado.pos.customerdisplay

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.kiosk.domain.KioskState
import java.time.Instant
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * La pantalla del cliente se pone al día por HUELLA (F2): el poll anuncia la huella de la ficha; si difiere de la del
 * manifiesto en disco, se baja la ficha, se guarda (atómico) y se ofrece al estado, que la aplica en el siguiente `Idle`.
 *
 * Sin red (las cuatro preguntas):
 * 1. El cliente ve la ficha del último manifiesto con el logo ya verificado en disco, o la fábrica. Al arrancar o cambiar
 *    de sesión se publica lo guardado SIN red ([restart]).
 * 2. En la tablet no se edita nada; manifiesto y acuse se escriben atómicos ([CustomerDisplayDesignStore.update]) ANTES de
 *    cualquier POST.
 * 3. Sólo existe el acuse: uno pendiente por terminal, `seq` monótono, el nuevo reemplaza al viejo. Sale en cada poll con
 *    el mismo cuerpo y `seq` hasta que el server responda algo que lo cierre ([ackActionFor]).
 * 4. Al volver la red, la huella nueva llega en el poll y se aplica en el siguiente `Idle`; lo que llegue de una sesión o
 *    un vínculo viejo (generación distinta, otra sucursal) se descarta sin escribir ni publicar.
 *
 * 🔴 UNA SOLA FILA ([lane]). Todo cambio del estado del sync —el almacén, la generación, la descarga en curso, la sucursal
 * publicada, los acuses en memoria, la poda— y la publicación o retirada que le sigue corren como UNA unidad, una detrás de
 * otra en orden de llegada. La red (ficha, logo, acuse) va FUERA de la fila: su resultado vuelve a entrar en otra unidad y
 * sólo se aplica si la generación y la sucursal siguen siendo las de antes. Así ningún orden entre poll, descarga, arranque,
 * cambio de sesión, desligado y acuses puede dejar en pantalla algo distinto de lo último que hay en disco.
 *
 * 🔴 La cortina (Codex r6#2, r7). Logout o cambio de sucursal quitan el diseño —el visible y el pendiente— en la MISMA
 * llamada de [onSessionChanged], en Main, aunque la fila esté ocupada: eso es «al instante». Se decide comparando la sesión
 * con la ÚLTIMA que vio [onSessionChanged], no con lo ofrecido (un poll de la sucursal nueva puede dejar su diseño pendiente
 * con el anterior todavía visible). Lo demás (generación, cancelar la descarga, publicar lo guardado de la sucursal nueva)
 * lo hace su unidad cuando le toca turno. Nada de la sucursal anterior puede volver en medio: toda oferta revisa la sesión
 * en Main ([publishLatest]). Con la MISMA sucursal no se toca nada (sin parpadeo). Un desligado ([onDeviceUnbound]) no lleva
 * cortina: retira en su unidad.
 *
 * 🔴 Hilos: [CustomerDisplayState.offerDesign]/[CustomerDisplayState.clearDesign] no tienen candado y suponen Main, así que
 * SIEMPRE se llaman en Main: dentro de `withContext(Dispatchers.Main.immediate)` desde una unidad, o en la cortina. Main nunca
 * espera a la fila: las llamadas que no suspenden lanzan su unidad ([launchUnit]). Todo disco de una unidad va en
 * `withContext(Dispatchers.IO)`. Las unidades lanzadas corren con un despachador que no es ni IO ni Main, así que esos dos
 * saltos siempre cambian de hilo cuando hace falta, empiece donde empiece la unidad (Codex r6#1).
 */
@Singleton
class CustomerDisplaySync internal constructor(
    private val remote: DisplayModeRemoteRepository,
    private val store: CustomerDisplayDesignStore,
    private val images: CustomerDisplayImageCache,
    private val state: CustomerDisplayState,
    @Suppress("unused") private val kioskEnabled: StateFlow<Boolean>,   // lo usa el acuse «mostrado» (Task 3.2)
    private val sessionVenue: () -> String?,
    private val environment: () -> String,
    private val wallClock: () -> Long,
    private val scope: CoroutineScope,
    @Suppress("unused") private val refreshSettingsIfChanged: suspend (venueId: String, fingerprint: String) -> Unit = { _, _ -> },   // Bloque 4
) {
    @Inject constructor(
        remote: DisplayModeRemoteRepository,
        store: CustomerDisplayDesignStore,
        images: CustomerDisplayImageCache,
        state: CustomerDisplayState,
        kioskState: KioskState,
        secureStorage: SecureStorage,
        @Named("apiBaseUrl") baseUrlProvider: () -> String,
    ) : this(
        remote = remote,
        store = store,
        images = images,
        state = state,
        kioskEnabled = kioskState.enabled,
        sessionVenue = { secureStorage.venueId?.takeIf { secureStorage.isLoggedIn && it.isNotBlank() } },
        // El ambiente separa los manifiestos de prod y de pruebas en el mismo aparato.
        environment = { baseUrlProvider().toHttpUrlOrNull()?.host ?: baseUrlProvider() },
        wallClock = System::currentTimeMillis,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    /**
     * LA fila. Un [Mutex] de corrutinas es justo (primero en llegar, primero en entrar) y se sostiene durante TODA la
     * unidad, también mientras salta a IO (disco) o a Main (ofrecer/retirar): ninguna otra unidad puede colarse en medio.
     */
    private val lane = Mutex()

    // ── Estado del sync: sólo se lee y se escribe DENTRO de la fila (el candado da el orden y la visibilidad). ──

    /** Sube con cada cambio de sesión o desvinculación: lo que la red traiga para otra generación se descarta. */
    private var generation = 0L

    private class Download(val key: String, val ref: LogoRef, val job: Job)

    private var download: Download? = null

    /**
     * Los contadores de imagen del último acuse encolado por terminal (llave → huella y contadores). Un logo que sigue
     * igual de pendiente o de fallido en cada poll no encola otro acuse (otro `seq` cada 15 s). En memoria: tras reiniciar
     * sale, cuando mucho, uno repetido.
     */
    private val lastImagesAck = HashMap<String, Pair<String, Triple<Int, Int, Int>>>()

    /**
     * Llaves cuyo acuse respondió «lo vigente es otra ficha» ([AckAction.CloseAndFetch]): el siguiente poll baja la ficha
     * aunque anuncie la que ya está. El acuse refresca la caché del server; `refetch` sólo cubre una carrera (un poll que
     * salió antes del acuse y aún anuncia la vieja). En memoria: tras reiniciar, el poll ya anuncia la nueva.
     */
    private val refetch = HashSet<String>()

    /**
     * La sucursal de la sesión que publicó el último [restart]. Si llega otro cambio de sesión con la MISMA (al arrancar,
     * `refreshPlanAndSettings → refreshTabs` lo dispara de nuevo), no se pasa por fábrica: sólo se vuelve a publicar lo
     * último, sin parpadeo ni reinicio del fade. Un desligado de la sucursal actual la borra.
     */
    private var restartedVenue: String? = null

    /**
     * Sucursales desligadas cuyo diseño guardado no se pudo borrar (falló el disco, Codex r6#3). Mientras estén aquí,
     * [publishLatest] publica fábrica y la siguiente unidad que toque la sucursal reintenta el borrado ([retryForget]); un
     * 200 con ficha de esa sucursal (se volvió a ligar) la quita.
     * ponytail: sólo en memoria; si además la app se reinicia sin red antes de poder borrar, ese diseño puede revivir.
     * Mejora: persistir la marca en el almacén.
     */
    private val unboundUndeleted = HashSet<String>()

    /**
     * La última sesión (sucursal) que vio [onSessionChanged]; nace con la del arranque. NO es de la fila: vive en Main (y en el
     * constructor) y la usa la cortina.
     */
    private var lastSessionVenue: String? = sessionVenue()

    // Un disco que falla en un trabajo de fondo se registra; nunca tumba la app (excepción sin manejar en la corrutina).
    private val logErrors = CoroutineExceptionHandler { _, error ->
        Log.w(TAG, "Falló la sincronización de la pantalla del cliente: ${error.message}")
    }

    // Después de declarar todo el estado: la unidad del arranque empieza aquí mismo.
    init {
        restart(lastSessionVenue)
    }

    /**
     * Una unidad de la fila. Esperar turno se puede cancelar; una vez adentro corre COMPLETA ([NonCancellable]): lo que
     * guardó siempre se publica (Codex r5 H1: guardar B y que la pérdida de red cancele su publicación dejaba A). Las
     * unidades no esperan red: la fila sólo queda tomada lo que tarden su disco y su paso por Main.
     */
    private suspend fun <T> inLane(unit: suspend () -> T): T = lane.withLock { withContext(NonCancellable) { unit() } }

    /**
     * Para las llamadas que no suspenden (sesión, desligado). Empieza en el hilo de quien llama (`UNDISPATCHED`): toma su
     * lugar en la fila en esta misma llamada, en el orden de las llamadas. Su despachador es `Default`, ni IO ni Main, para que
     * `withContext(IO)` y `withContext(Main.immediate)` sí cambien de hilo empiece donde empiece (Codex r6#1): con IO, el salto
     * a IO no ocurría al empezar en Main (el disco corría en Main); con Main, el salto a Main no ocurría al empezar en otro
     * hilo (se retiraba fuera de Main). Con la fila ocupada se suspende sin bloquear ese hilo. La unidad vive en [scope], no en
     * quien la lanzó: cancelarlo no la pierde.
     */
    private fun launchUnit(unit: suspend () -> Unit): Job =
        scope.launch(Dispatchers.Default + logErrors, start = CoroutineStart.UNDISPATCHED) { inLane(unit) }

    /**
     * Después de cada poll. Devuelve `true` = «reintenta pronto» (la ficha no se pudo bajar por algo pasajero).
     * No espera la descarga del logo: el poll, las solicitudes de inversión y el reporte siguen a su ritmo.
     */
    suspend fun afterPoll(venueId: String, terminalId: String, signals: PollSignals): Boolean = withContext(Dispatchers.IO) {
        val env = environment()
        val key = store.manifestKey(env, venueId, terminalId)
        val announced = signals.fingerprints.customerDisplay
        // (1)-(3) En la fila: la generación, la última terminal y si hay que bajar la ficha.
        val (generation, fetch) = inLane { startPoll(env, venueId, terminalId, key, announced) } ?: return@withContext false
        // Fuera de la fila: la red.
        val outcome = if (fetch) remote.fetchCustomerDisplay(venueId) else null
        if (outcome is DisplayModeRemoteOutcome.Rejected && outcome.status == 404 && outcome.code == DEVICE_NOT_FOUND) {
            // Unidad lanzada, no atada al tick: si lo cancelan mientras espera turno, el desligado igual ocurre (toma su
            // lugar en la fila en esta misma llamada). Se espera para que el poll regrese con el desligado ya aplicado.
            launchUnit { unbind(venueId) }.join()
            return@withContext false
        }
        // (4)-(5) En la fila: guardar lo que llegó, publicar YA lo último (con el logo que haya en disco, o sin él: se ve el
        // nombre) y decidir el logo.
        inLane { finishPoll(generation, venueId, key, announced, outcome) }
        // (6)
        sendPendingAck(generation, venueId, key)
        // Sesión inválida (la maneja el coordinador) o NoRequest (no aplica a este GET): no se toca nada.
        outcome is DisplayModeRemoteOutcome.Retryable
    }

    /** Unidad (1)-(3) del poll: `null` = la sesión ya no es la de este poll. Devuelve la generación y si hay que bajar. */
    private suspend fun startPoll(env: String, venueId: String, terminalId: String, key: String, announced: String?): Pair<Long, Boolean>? {
        val generation = this.generation
        val venueKey = store.venueKey(env, venueId)
        retryForget(venueKey)   // desligada con el borrado pendiente: se borra ANTES de leer o escribir nada de ella
        var moved = false
        val written = write(generation, venueId) { s ->
            if (s.lastTerminalByVenue[venueKey] == terminalId) s
            else s.copy(lastTerminalByVenue = s.lastTerminalByVenue + (venueKey to terminalId)).also { moved = true }
        }
        if (!written) return null
        // Otra terminal cambia lo que se ve: se publica en esta MISMA unidad.
        if (moved) publishLatest(generation, venueId)
        val known = store.load().manifests[key]
        val kept = known?.ficha
        if (announced != null && kept != null && announced == kept.fingerprint && known.unsupportedFingerprint != null) {
            // El server regresó a la ficha que se conservó: el aviso de esquema ya no es verdad. Se apaga y se acusa esa
            // ficha (si no, sus acuses quedarían bloqueados y el pendiente seguiría diciendo «unsupportedSchema»).
            val counts = logoCounts(kept.toFicha(known.imageOrigins)?.logo)
            var enqueued = false
            write(generation, venueId) { s ->
                val current = s.manifests[key]
                if (current?.ficha?.fingerprint != announced || current.unsupportedFingerprint == null) s
                else reAck(s, key, current, counts).also { enqueued = true }
            }
            if (enqueued) rememberImages(key, announced, counts)
            return generation to false
        }
        val fetch = announced != null &&
            (key in refetch || announced != known?.ficha?.fingerprint && announced != known?.unsupportedFingerprint)
        return generation to fetch
    }

    /** Unidad (4)-(5) del poll: guarda la ficha que bajó (si bajó), publica y decide el logo. */
    private suspend fun finishPoll(
        generation: Long,
        venueId: String,
        key: String,
        announced: String?,
        outcome: DisplayModeRemoteOutcome<CustomerDisplayPayload>?,
    ) {
        if (outcome is DisplayModeRemoteOutcome.Success && announced != null) {
            refetch.remove(key)
            // Un 200 guardado = el aparato está ligado a esta sucursal: ya no hay desligado pendiente que borrar.
            if (saveFicha(generation, venueId, key, announced, outcome.data)) unboundUndeleted -= store.venueKey(environment(), venueId)
        }
        publishLatest(generation, venueId)
        val manifest = store.load().manifests[key]
        val ficha = manifest?.ficha?.toFicha(manifest.imageOrigins)
        // Sin logo en disco, o legado que toca revalidar (la copia vigente sigue visible mientras tanto).
        val logo = ficha?.logo
        if (logo != null && (images.cachedFile(logo) == null || images.revalidationDue(logo))) {
            ensureDownload(generation, venueId, key, logo, manifest.imageOrigins)
        } else if (logo != null) {
            // Logo listo en disco: si su acuse «listo» no quedó guardado (el disco falló al encolarlo en la descarga), se
            // encola en este tick, sin descargar nada. Si ya está, no hace nada.
            enqueueImagesAck(generation, venueId, key, ficha.fingerprint, imageCounts(ImageStatus.READY))
        }
    }

    /**
     * Lo que la pantalla del cliente agrega al reporte de capacidades: `null` si el server no anunció la capacidad (servidor
     * viejo: el reporte va como siempre) o si la instalación sólo existe en memoria (reportarla ligaría la terminal a una
     * instalación que la siguiente lectura ya no tiene; ese tick va sin la parte de diseño). Sólo lee: va fuera de la fila.
     */
    fun designReport(announcedCapability: Int?): DesignReport? {
        announcedCapability ?: return null
        val s = store.load()
        if (!s.persisted) return null
        return DesignReport(minOf(DESIGN_VERSION, announcedCapability), s.installationId)
    }

    /**
     * Otra instalación tomó la fila (`OTHER_INSTALLATION`) y toca volver a reportar: a lo más una vez por hora. Un reloj que
     * quedó ANTES de la última vez (lo cambiaron a mano) no bloquea el reporte. Sólo lee: va fuera de la fila.
     */
    fun otherInstallationReportDue(): Boolean {
        val s = store.load()
        if (!s.otherInstallationPending) return false
        val at = s.otherInstallationReportAtMs ?: return true
        val now = wallClock()
        return now < at || now - at >= OTHER_INSTALLATION_REPORT_INTERVAL_MS
    }

    /**
     * El reporte de capacidades llegó: si iba por `OTHER_INSTALLATION` se guarda la hora y se apaga la marca, y se encola un
     * acuse NUEVO (otro `seq`, armado con [buildAck]) por la ficha del manifiesto vigente de esa sucursal, para que el server
     * lo ligue a esta instalación. Sale en el siguiente poll. No cambia lo que se ve: no publica.
     */
    suspend fun onCapabilitiesReported(venueId: String): Unit = withContext(Dispatchers.IO) {
        inLane {
            val generation = this@CustomerDisplaySync.generation
            val env = environment()
            val before = store.load()
            val key = before.lastTerminalByVenue[store.venueKey(env, venueId)]?.let { store.manifestKey(env, venueId, it) }
            val kept = key?.let { before.manifests[it] }
            val fingerprint = kept?.ficha?.fingerprint
            val counts = logoCounts(kept?.ficha?.toFicha(kept.imageOrigins)?.logo)
            val now = wallClock()
            var remembered: Pair<String, Triple<Int, Int, Int>>? = null
            write(generation, venueId) { s ->
                val marked = if (s.otherInstallationPending) s.copy(otherInstallationPending = false, otherInstallationReportAtMs = now) else s
                val current = key?.let { marked.manifests[it] }
                val unsupported = current?.unsupportedFingerprint
                when {
                    key == null || current == null -> marked
                    // Esquema desconocido: al adoptar la instalación el server borró su acuse; si ya se había entregado (no
                    // hay pendiente) se vuelve a acusar para que el panel no pierda «actualiza la app». Uno pendiente no se toca.
                    unsupported != null -> if (current.pendingAck != null) marked
                    else enqueueAck(marked, key, current, unsupported, unsupported = true, images = NO_IMAGES)
                        .also { remembered = unsupported to NO_IMAGES }
                    // La MISMA regla que el acuse de imágenes: si entretanto llegó otra ficha, no se acusa la vieja.
                    fingerprint == null || current.ficha?.fingerprint != fingerprint -> marked
                    else -> enqueueAck(marked, key, current, fingerprint, unsupported = false, images = counts)
                        .also { remembered = fingerprint to counts }
                }
            }
            remembered?.let { (huella, conteo) -> if (key != null) rememberImages(key, huella, conteo) }
        }
    }

    /**
     * Login, logout o cambio de sucursal. Se llama en Main (AppState). Primero la cortina: si no hay sesión o la sucursal no es
     * la de la última sesión que se vio aquí, el diseño (visible y pendiente) se quita AQUÍ, sin esperar a la fila. Después la
     * unidad, con la sesión de ESTE momento, hace el resto en su turno ([restart]).
     */
    fun onSessionChanged() {
        val venue = sessionVenue()
        if (venue == null || venue != lastSessionVenue) state.clearDesign()
        lastSessionVenue = venue
        restart(venue)
    }

    /**
     * El aparato ya no está ligado a esa sucursal (404 `DEVICE_NOT_FOUND`): fábrica y se borran sus manifiestos (con su
     * acuse pendiente) y su última terminal, en UNA unidad. Así, aunque arranque sin red, no revive ese diseño.
     */
    fun onDeviceUnbound(venueId: String) {
        launchUnit { unbind(venueId) }
    }

    /**
     * Unidad del desligado: invalidar, borrar y retirar juntos. Lo que corra después (una recarga, la publicación de otra
     * sucursal) ya lee el disco sin ese diseño (Codex r5 H4), y nada de aquí puede caer DESPUÉS de una publicación de la
     * sucursal nueva (H3): se ve y se retira sólo si la desligada es la sucursal de la sesión en ESTE momento.
     */
    private suspend fun unbind(venueId: String) {
        val venueKey = store.venueKey(environment(), venueId)
        val isCurrentVenue = sessionVenue() == venueId
        if (isCurrentVenue) {
            restartedVenue = null
            generation++
            cancelDownload()
        }
        lastImagesAck.keys.removeAll { it.startsWith("$venueKey|") }
        // El disco de la sucursal desligada se borra siempre. Si falla, queda marcada: nada vuelve a ofrecer ese diseño y la
        // siguiente unidad que toque la sucursal reintenta (Codex r6#3).
        if (withContext(Dispatchers.IO) { forgetVenue(venueKey) }) unboundUndeleted -= venueKey else unboundUndeleted += venueKey
        // Main: regla de CustomerDisplayState (sin candado).
        if (isCurrentVenue) withContext(Dispatchers.Main.immediate) { state.clearDesign() }
    }

    /** Borra el diseño guardado de esa sucursal (manifiestos con su acuse y la última terminal). `false` si falló el disco. En IO. */
    private fun forgetVenue(venueKey: String): Boolean = runCatching {
        store.update { s ->
            s.copy(
                manifests = s.manifests.filterKeys { !it.startsWith("$venueKey|") },
                lastTerminalByVenue = s.lastTerminalByVenue - venueKey,
            )
        }
    }.onFailure { Log.w(TAG, "No se pudo borrar el diseño de la sucursal desligada: ${it.message}") }.isSuccess

    /** Si esa sucursal quedó desligada sin poder borrar su diseño, lo reintenta. `true` = sigue pendiente. En la fila y en IO. */
    private fun retryForget(venueKey: String): Boolean {
        if (venueKey in unboundUndeleted && forgetVenue(venueKey)) unboundUndeleted -= venueKey
        return venueKey in unboundUndeleted
    }

    /**
     * El ÚNICO que arma acuses. Copia `storedAt`, `shownAt` y `surface` del manifiesto: un acuse nuevo de la misma huella
     * nunca regresa «Mostrado» a «Guardado». El `seq` real lo asigna [enqueueAck] dentro del mismo `update` que lo reserva;
     * `installationId` llega del estado en curso (sin releer el disco dentro del `update`).
     */
    internal fun buildAck(
        manifest: DesignManifest,
        fingerprint: String,
        unsupported: Boolean,
        images: Triple<Int, Int, Int>,
        installationId: String,
    ): CustomerDisplayAckBody = CustomerDisplayAckBody(
        installationId = installationId,
        fingerprint = fingerprint,
        seq = 0,
        storedAt = manifest.storedAt,
        shownAt = manifest.shownAt,
        surface = manifest.surface,
        imagesReady = images.first,
        imagesPending = images.second,
        imagesFailed = images.third,
        unsupportedSchema = unsupported,
    )

    /** `true` = quedó guardada (la de esquema desconocido también). */
    private fun saveFicha(generation: Long, venueId: String, key: String, announced: String, payload: CustomerDisplayPayload): Boolean {
        val dto = payload.ficha
        val ficha = dto.toFicha(payload.imageOrigins)
        if (dto.schemaVersion != SUPPORTED_SCHEMA_VERSION || ficha == null) {
            // Esquema que esta versión no entiende (o una ficha v1 sin huella válida, que tampoco se puede pintar): se
            // conserva el diseño actual, se le dice al server y no se vuelve a pedir esa huella en cada poll. La huella es
            // la de lo que BAJÓ si es válida: con `refetch` el poll aún puede anunciar la ficha que se conserva.
            val huella = dto.fingerprint.takeIf(::isPollFingerprint) ?: announced
            val saved = write(generation, venueId) { s ->
                val kept = (s.manifests[key] ?: DesignManifest(storedAt = isoNow())).copy(unsupportedFingerprint = huella)
                enqueueAck(s, key, kept, huella, unsupported = true, images = NO_IMAGES)
            }
            if (saved) rememberImages(key, huella, NO_IMAGES)
            return saved
        }
        val counts = logoCounts(ficha.logo)
        val saved = write(generation, venueId) { s ->
            val manifest = DesignManifest(ficha = dto, imageOrigins = payload.imageOrigins, storedAt = isoNow())
            enqueueAck(s, key, manifest, ficha.fingerprint, unsupported = false, images = counts)
        }
        if (saved) rememberImages(key, ficha.fingerprint, counts)
        return saved
    }

    /** Encolar = reservar `seq` y congelar el cuerpo en el mismo `update`, ANTES de mandarlo. Reemplaza al pendiente viejo. */
    private fun enqueueAck(
        s: StoredDesignState,
        key: String,
        manifest: DesignManifest,
        fingerprint: String,
        unsupported: Boolean,
        images: Triple<Int, Int, Int>,
    ): StoredDesignState {
        val body = buildAck(manifest, fingerprint, unsupported, images, s.installationId).copy(seq = s.nextSeq)
        return s.copy(nextSeq = s.nextSeq + 1, manifests = s.manifests + (key to manifest.copy(pendingAck = body)))
    }

    /** Un acuse nuevo por la ficha que se conserva (la vigente según el server), con el aviso de esquema apagado. */
    private fun reAck(s: StoredDesignState, key: String, manifest: DesignManifest, images: Triple<Int, Int, Int>): StoredDesignState =
        enqueueAck(s, key, manifest.copy(unsupportedFingerprint = null), manifest.ficha!!.fingerprint, unsupported = false, images = images)

    /**
     * UN trabajo de descarga (en la fila): el de la misma llave y logo se deja correr; cualquier otro se cancela y se lanza
     * el nuevo. La red va fuera de la fila; lo que hace con el resultado (podar, publicar, acusar) es otra unidad.
     */
    private fun ensureDownload(generation: Long, venueId: String, key: String, ref: LogoRef, origins: List<String>) {
        // Un afterPoll de una sesión vieja no cancela (ni reemplaza) la descarga de la sesión nueva.
        if (!current(generation, venueId)) return
        val running = download
        if (running != null && running.job.isActive && running.key == key && running.ref == ref) return
        running?.job?.cancel()
        val job = scope.launch(logErrors) {
            val status = images.ensure(ref, origins)
            inLane {
                // La cuota de 100 MB: tras una descarga buena se borra lo que ningún manifiesto guardado usa AHORA (las
                // referencias se leen dentro de la misma unidad que borra: nada puede referenciar otro logo en medio, r5 H2).
                if (status == ImageStatus.READY) referencedLogos()?.let(images::prune)
                // Mientras bajaba pudo cambiar la sesión, desligarse el aparato o llegar otra ficha: se publica lo último
                // del disco, y `publishLatest`/`write` descartan si la generación o la sucursal ya no son.
                publishLatest(generation, venueId)
                val manifest = store.load().manifests[key] ?: return@inLane
                val ficha = manifest.ficha?.toFicha(manifest.imageOrigins) ?: return@inLane
                if (ficha.logo != ref) return@inLane
                enqueueImagesAck(generation, venueId, key, ficha.fingerprint, imageCounts(status))
            }
        }
        download = Download(key, ref, job)
    }

    /**
     * El acuse con los contadores del logo, si no es el último que ya quedó en disco. `lastImagesAck` sólo se anota tras
     * escribir: si el disco falla, el siguiente poll lo vuelve a encolar (ver el paso 5 de [afterPoll]).
     */
    private fun enqueueImagesAck(generation: Long, venueId: String, key: String, fingerprint: String, counts: Triple<Int, Int, Int>) {
        if (lastImagesAck[key] == (fingerprint to counts)) return
        var enqueued = false
        write(generation, venueId) { s ->
            val current = s.manifests[key]
            // Otra ficha entretanto, o un esquema desconocido pendiente: el acuse de imágenes de la ficha que se
            // conserva taparía (con un seq mayor) el «unsupportedSchema» y el panel perdería «actualiza la app».
            // [onCapabilitiesReported] respeta la MISMA regla al encolar su acuse nuevo.
            if (current?.ficha?.fingerprint != fingerprint || current?.unsupportedFingerprint != null) s
            else enqueueAck(s, key, current, fingerprint, unsupported = false, images = counts).also { enqueued = true }
        }
        if (enqueued) rememberImages(key, fingerprint, counts)
    }

    /** Sólo tras escribir: si el disco falló, el siguiente poll vuelve a intentar ese acuse. */
    private fun rememberImages(key: String, fingerprint: String, images: Triple<Int, Int, Int>) {
        lastImagesAck[key] = fingerprint to images
    }

    private fun cancelDownload() {
        download?.job?.cancel()
        download = null
    }

    /**
     * Retira el diseño y publica lo guardado de [venueNow] (la última terminal de esa sucursal), en UNA unidad. [venueNow] es
     * la sesión que vio quien llamó, no la de cuando le toque turno: con la fila ocupada, A → logout → A es un logout
     * (generación nueva, se cancela la descarga, se retira) y luego otra sucursal, nunca dos recargas de A (Codex r6#2).
     */
    private fun restart(venueNow: String?) = launchUnit {
        if (venueNow != null && venueNow == restartedVenue) {
            // Misma sucursal y sesión: la generación, la descarga y lo publicado siguen siendo válidos.
            publishLatest(generation, venueNow)
            return@launchUnit
        }
        restartedVenue = venueNow
        val generation = ++this.generation
        cancelDownload()
        // Main: regla de CustomerDisplayState (sin candado).
        withContext(Dispatchers.Main.immediate) { state.clearDesign() }
        if (venueNow != null) publishLatest(generation, venueNow)
    }

    /**
     * La ÚNICA publicación (dentro de una unidad: poll, fin de la descarga, arranque/cambio de sesión): lee el manifiesto
     * VIGENTE de la última terminal de esa sucursal y su logo de disco, y lo ofrece en Main con las guardas de generación y
     * sucursal. Como la fila ordena todas las escrituras, lo que lee es lo último que hay. Si el almacén no se pudo leer
     * (estado sólo en memoria), no publica: no se borra un diseño bueno por una lectura pasajera. Una sucursal desligada
     * con el borrado pendiente publica fábrica (Codex r6#3).
     */
    private suspend fun publishLatest(generation: Long, venueId: String) {
        val (s, undeleted) = withContext(Dispatchers.IO) {
            val undeleted = retryForget(store.venueKey(environment(), venueId))
            store.load() to undeleted
        }
        if (!s.persisted) return
        val design = if (undeleted) null else withContext(Dispatchers.IO) { latestDesign(s, venueId) }
        // Main: regla de CustomerDisplayState (sin candado). La sesión puede cambiar fuera de la fila (SecureStorage): la
        // comprobación va en el MISMO bloque de Main que el ofrecimiento.
        withContext(Dispatchers.Main.immediate) {
            if (current(generation, venueId)) state.offerDesign(design)
        }
    }

    private fun latestDesign(s: StoredDesignState, venueId: String): AppliedDesign? {
        val env = environment()
        val terminalId = s.lastTerminalByVenue[store.venueKey(env, venueId)] ?: return null
        val manifest = s.manifests[store.manifestKey(env, venueId, terminalId)] ?: return null
        return manifest.ficha?.toFicha(manifest.imageOrigins)?.let(::applied)
    }

    /**
     * Los logos de TODOS los manifiestos guardados (cualquier sucursal o terminal): lo que la poda no puede borrar. `null`
     * si el almacén no se pudo leer: un estado vacío en memoria no significa «nada referenciado» y la poda se salta.
     */
    private fun referencedLogos(): Set<LogoRef>? {
        val s = store.load()
        if (!s.persisted) return null
        return s.manifests.values.mapNotNullTo(HashSet()) { it.ficha?.toFicha(it.imageOrigins)?.logo }
    }

    /** El ÚNICO que arma el diseño: el logo de disco (sólo disco, nunca red) con su marca de `lastModified`. */
    private fun applied(ficha: Ficha): AppliedDesign {
        val logo = ficha.logo?.let(images::cachedFile)
        return AppliedDesign(ficha, logo, logoStamp = logo?.lastModified() ?: 0L)
    }

    /**
     * Sigue siendo la misma sesión y el mismo vínculo que cuando empezó el trabajo. La generación sólo cambia en la fila;
     * la sesión puede cambiar en cualquier momento (por eso se vuelve a mirar al escribir y al ofrecer).
     */
    private fun current(generation: Long, venueId: String) = this.generation == generation && sessionVenue() == venueId

    /** Escribe sólo si sigue siendo la misma generación y sucursal (comprobado DENTRO del candado del almacén). En la fila. */
    private fun write(generation: Long, venueId: String, change: (StoredDesignState) -> StoredDesignState): Boolean {
        var applied = false
        store.update { s -> if (current(generation, venueId)) change(s).also { applied = true } else s }
        return applied
    }

    /**
     * Manda el acuse pendiente de esa terminal tal como quedó congelado al encolarlo (mismo cuerpo y `seq`, aunque la app
     * se haya reiniciado) y obedece la respuesta ([ackActionFor]). El PUT va fuera de la fila; su respuesta se aplica en
     * otra unidad que pasa por [write]: si cambió la sesión o la sucursal mientras viajaba, no toca nada.
     */
    private suspend fun sendPendingAck(generation: Long, venueId: String, key: String) {
        val sent = inLane { if (current(generation, venueId)) store.load().manifests[key]?.pendingAck else null } ?: return
        val outcome = remote.acknowledgeCustomerDisplay(venueId, sent)
        inLane {
            val manifest = store.load().manifests[key]
            val action = ackActionFor(outcome, manifest?.ficha?.fingerprint)
            if (action == AckAction.Keep) return@inLane
            val counts = logoCounts(manifest?.ficha?.toFicha(manifest.imageOrigins)?.logo)
            var reAcked: String? = null
            val applied = write(generation, venueId) { s ->
                val m = s.manifests[key]
                if (m == null || m.pendingAck?.seq != sent.seq) {
                    // Cerrar borra el pendiente SÓLO si sigue siendo el que se mandó: uno más nuevo sale en el siguiente
                    // poll. Que otra instalación tomó la fila es verdad aunque haya otro acuse pendiente.
                    if (action == AckAction.CloseAndReport) s.copy(otherInstallationPending = true) else s
                } else {
                    val closed = s.copy(manifests = s.manifests + (key to m.copy(pendingAck = null)))
                    when (action) {
                        is AckAction.CloseAndAckAgain -> if (m.ficha?.fingerprint != action.fingerprint) closed
                        else reAck(closed, key, m, counts).also { reAcked = action.fingerprint }
                        AckAction.CloseAndReport -> closed.copy(otherInstallationPending = true)
                        else -> closed
                    }
                }
            }
            reAcked?.let { rememberImages(key, it, counts) }
            // Sin red fuera del poll: la ficha se baja en el siguiente.
            if (applied && action == AckAction.CloseAndFetch) refetch += key
        }
    }

    private fun isoNow(): String = Instant.ofEpochMilli(wallClock()).toString()

    /** Lo que dice el disco del logo ahora mismo (sin red): listo si ya está, pendiente si no. */
    private fun logoCounts(logo: LogoRef?): Triple<Int, Int, Int> =
        imageCounts(logo?.let { if (images.cachedFile(it) != null) ImageStatus.READY else ImageStatus.PENDING })

    private fun imageCounts(status: ImageStatus?): Triple<Int, Int, Int> = when (status) {
        null -> NO_IMAGES
        ImageStatus.READY -> Triple(1, 0, 0)
        ImageStatus.PENDING -> Triple(0, 1, 0)
        ImageStatus.FAILED -> Triple(0, 0, 1)
    }

    private companion object {
        const val TAG = "CustomerDisplaySync"
        const val DEVICE_NOT_FOUND = "DEVICE_NOT_FOUND"
        val NO_IMAGES = Triple(0, 0, 0)
        /** La versión de diseño que entiende esta app (la del contrato v1). */
        const val DESIGN_VERSION = 1
        const val OTHER_INSTALLATION_REPORT_INTERVAL_MS = 60L * 60L * 1_000L
    }
}

/** Qué hacer con el acuse pendiente según la respuesta del server (spec §5.7.2). «Cerrar» = borrar el pendiente. */
sealed interface AckAction {
    data object Close : AckAction
    /** El server guardó el acuse, pero lo vigente es la ficha que ya está en el manifiesto: se acusa de nuevo. */
    data class CloseAndAckAgain(val fingerprint: String) : AckAction
    /** Lo vigente es otra ficha: el acuse refresca la caché del server; `refetch` sólo cubre una carrera (el siguiente poll la baja aunque aún anuncie la vieja). */
    data object CloseAndFetch : AckAction
    /** Otra instalación tomó la fila: se marca `otherInstallationPending` y el coordinador vuelve a reportar (a lo más una vez por hora). */
    data object CloseAndReport : AckAction
    /** Sin red, 408, 429, 5xx (`ACK_BUSY`) o sesión inválida: el mismo acuse (mismo `seq`) sale en el siguiente poll. */
    data object Keep : AckAction
}

internal fun ackActionFor(
    outcome: DisplayModeRemoteOutcome<CustomerDisplayAckResponse>,
    manifestFingerprint: String?,
): AckAction = when (outcome) {
    is DisplayModeRemoteOutcome.Success -> {
        val response = outcome.data
        when {
            // STALE_SEQ (ya hay uno más nuevo guardado) o un motivo que esta versión no conoce: reenviarlo no cambia nada.
            !response.accepted -> if (response.reason == OTHER_INSTALLATION) AckAction.CloseAndReport else AckAction.Close
            response.current != false -> AckAction.Close
            response.currentFingerprint != null && response.currentFingerprint == manifestFingerprint ->
                AckAction.CloseAndAckAgain(response.currentFingerprint)
            else -> AckAction.CloseAndFetch
        }
    }
    // 404 (el aparato ya no es de esta sucursal; el poll lo desliga) u otro 4xx: el server no lo va a aceptar nunca.
    is DisplayModeRemoteOutcome.Rejected -> AckAction.Close
    is DisplayModeRemoteOutcome.Retryable, DisplayModeRemoteOutcome.SessionInvalid, is DisplayModeRemoteOutcome.NoRequest -> AckAction.Keep
}

private const val OTHER_INSTALLATION = "OTHER_INSTALLATION"
