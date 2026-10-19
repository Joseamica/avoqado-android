package com.avoqado.pos.customerdisplay

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.kiosk.domain.KioskState
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
 * 🔴 Hilos: `afterPoll` y la descarga corren en IO; [CustomerDisplayState.offerDesign]/[CustomerDisplayState.clearDesign]
 * no tienen candado y suponen Main, así que SIEMPRE se llaman dentro de `Dispatchers.Main.immediate`: la ficha y el logo se
 * resuelven antes en IO y sólo el [AppliedDesign] listo cruza a Main.
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

    /** Sube con cada cambio de sesión o desvinculación: todo trabajo que capturó otra generación se descarta. */
    private val generation = AtomicLong(0)

    private class Download(val key: String, val ref: LogoRef, val job: Job)

    private val downloadLock = Any()
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
    private val refetch: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Cuántas veces se ofreció un diseño. Se lee y se suma sólo dentro de bloques de Main; el arranque lo captura al entrar
     * y publica lo guardado sólo si nadie ofreció nada mientras leía el disco (si no, taparía la ficha del primer poll).
     */
    private val offers = AtomicInteger(0)

    // Un disco que falla en un trabajo de fondo se registra; nunca tumba la app (excepción sin manejar en la corrutina).
    private val logErrors = CoroutineExceptionHandler { _, error ->
        Log.w(TAG, "Falló la sincronización de la pantalla del cliente: ${error.message}")
    }

    init {
        restart()
    }

    /**
     * Después de cada poll. Devuelve `true` = «reintenta pronto» (la ficha no se pudo bajar por algo pasajero).
     * No espera la descarga del logo: el poll, las solicitudes de inversión y el reporte siguen a su ritmo.
     */
    suspend fun afterPoll(venueId: String, terminalId: String, signals: PollSignals): Boolean = withContext(Dispatchers.IO) {
        val generation = generation.get()   // (1)
        val env = environment()
        val key = store.manifestKey(env, venueId, terminalId)
        // (2)
        if (!write(generation, venueId) { it.copy(lastTerminalByVenue = it.lastTerminalByVenue + (store.venueKey(env, venueId) to terminalId)) }) {
            return@withContext false
        }
        // (3)
        var retrySoon = false
        val announced = signals.fingerprints.customerDisplay
        val known = store.load().manifests[key]
        val kept = known?.ficha
        if (announced != null && kept != null && announced == kept.fingerprint && known?.unsupportedFingerprint != null) {
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
        } else if (announced != null &&
            (key in refetch || announced != known?.ficha?.fingerprint && announced != known?.unsupportedFingerprint)
        ) {
            when (val outcome = remote.fetchCustomerDisplay(venueId)) {
                is DisplayModeRemoteOutcome.Success -> {
                    refetch.remove(key)
                    saveFicha(generation, venueId, key, announced, outcome.data)
                }
                is DisplayModeRemoteOutcome.Rejected -> if (outcome.status == 404 && outcome.code == DEVICE_NOT_FOUND) {
                    onDeviceUnbound(venueId)
                    return@withContext false
                }
                is DisplayModeRemoteOutcome.Retryable -> retrySoon = true
                // Sesión inválida (la maneja el coordinador) o NoRequest (no aplica a este GET): no se toca nada.
                else -> Unit
            }
        }
        // (4) YA, con el logo que haya en disco (o sin él: se ve el nombre).
        val manifest = store.load().manifests[key]
        val ficha = manifest?.ficha?.toFicha(manifest.imageOrigins)
        publish(generation, venueId, ficha?.let(::applied))
        // (5) Sin logo en disco, o legado que toca revalidar (la copia vigente sigue visible mientras tanto).
        val logo = ficha?.logo
        if (logo != null && (images.cachedFile(logo) == null || images.revalidationDue(logo))) {
            ensureDownload(generation, venueId, key, logo, manifest.imageOrigins)
        } else if (logo != null) {
            // Logo listo en disco: si su acuse «listo» no quedó guardado (el disco falló al encolarlo en la descarga), se
            // encola en este tick, sin descargar nada. Si ya está, no hace nada.
            enqueueImagesAck(generation, venueId, key, ficha.fingerprint, imageCounts(ImageStatus.READY))
        }
        // (6)
        sendPendingAck(generation, venueId, key)
        retrySoon
    }

    /**
     * Lo que la pantalla del cliente agrega al reporte de capacidades: `null` si el server no anunció la capacidad (servidor
     * viejo: el reporte va como siempre) o si la instalación sólo existe en memoria (reportarla ligaría la terminal a una
     * instalación que la siguiente lectura ya no tiene; ese tick va sin la parte de diseño).
     */
    fun designReport(announcedCapability: Int?): DesignReport? {
        announcedCapability ?: return null
        val s = store.load()
        if (!s.persisted) return null
        return DesignReport(minOf(DESIGN_VERSION, announcedCapability), s.installationId)
    }

    /**
     * Otra instalación tomó la fila (`OTHER_INSTALLATION`) y toca volver a reportar: a lo más una vez por hora. Un reloj que
     * quedó ANTES de la última vez (lo cambiaron a mano) no bloquea el reporte.
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
     * lo ligue a esta instalación. Sale en el siguiente poll.
     */
    suspend fun onCapabilitiesReported(venueId: String): Unit = withContext(Dispatchers.IO) {
        val generation = generation.get()
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
                // Esquema desconocido: al adoptar la instalación el server borró su acuse; si ya se había entregado (no hay
                // pendiente) se vuelve a acusar para que el panel no pierda «actualiza la app». Uno pendiente no se toca.
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

    /** Login, logout o cambio de sucursal: retira el diseño AL INSTANTE y publica (sin red) lo guardado de la nueva. */
    fun onSessionChanged() = restart()

    /**
     * El aparato ya no está ligado a esa sucursal (404 `DEVICE_NOT_FOUND`): fábrica al instante y se borran sus
     * manifiestos (con su acuse pendiente) y su última terminal. Así, aunque arranque sin red, no revive ese diseño.
     */
    fun onDeviceUnbound(venueId: String) {
        // Lo que se ve y lo que corre es de la sesión ACTUAL: sólo se retira si el desligado es de esa sucursal. El disco
        // de la sucursal desligada se borra siempre.
        if (sessionVenue() == venueId) {
            generation.incrementAndGet()
            cancelDownload()
            // Main: regla de CustomerDisplayState (sin candado). Inmediato si quien llama ya está en Main.
            scope.launch(Dispatchers.Main.immediate + logErrors) { state.clearDesign() }
        }
        val venueKey = store.venueKey(environment(), venueId)
        synchronized(lastImagesAck) { lastImagesAck.keys.removeAll { it.startsWith("$venueKey|") } }
        runCatching {
            store.update { s ->
                s.copy(
                    manifests = s.manifests.filterKeys { !it.startsWith("$venueKey|") },
                    lastTerminalByVenue = s.lastTerminalByVenue - venueKey,
                )
            }
        }.onFailure { Log.w(TAG, "No se pudo borrar el diseño de la sucursal desligada: ${it.message}") }
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

    private suspend fun saveFicha(generation: Long, venueId: String, key: String, announced: String, payload: CustomerDisplayPayload) {
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
            return
        }
        val counts = logoCounts(ficha.logo)
        val saved = write(generation, venueId) { s ->
            val manifest = DesignManifest(ficha = dto, imageOrigins = payload.imageOrigins, storedAt = isoNow())
            enqueueAck(s, key, manifest, ficha.fingerprint, unsupported = false, images = counts)
        }
        if (saved) rememberImages(key, ficha.fingerprint, counts)
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

    /** UN trabajo de descarga: el de la misma llave y logo se deja correr; cualquier otro se cancela y se lanza el nuevo. */
    private fun ensureDownload(generation: Long, venueId: String, key: String, ref: LogoRef, origins: List<String>) {
        synchronized(downloadLock) {
            // Un afterPoll de una sesión vieja no cancela (ni reemplaza) la descarga de la sesión nueva.
            if (!current(generation, venueId)) return
            val running = download
            if (running != null && running.job.isActive && running.key == key && running.ref == ref) return
            running?.job?.cancel()
            val job = scope.launch(logErrors) {
                val status = images.ensure(ref, origins)
                // La cuota de 100 MB: tras una descarga buena se borra lo que ningún manifiesto guardado usa (en el trabajo,
                // nunca en el poll).
                if (status == ImageStatus.READY) images.prune(referencedLogos())
                // Mientras bajaba pudo cambiar la sesión, desligarse el aparato o llegar otra ficha: se publica lo que
                // diga el manifiesto AHORA, y `publish`/`write` descartan si la generación o la sucursal ya no son.
                // Las ofertas se cuentan en Main ANTES de leer el disco: si un poll ofrece otra ficha mientras se lee,
                // ésta ya no es la más nueva y no la pisa (como `restart`). Ese poll ya pintó el logo: estaba en disco.
                val offersSeen = withContext(Dispatchers.Main.immediate) { offers.get() }
                val manifest = store.load().manifests[key] ?: return@launch
                val ficha = manifest.ficha?.toFicha(manifest.imageOrigins) ?: return@launch
                publish(generation, venueId, applied(ficha), onlyIfNoOfferSince = offersSeen)
                if (ficha.logo != ref) return@launch
                enqueueImagesAck(generation, venueId, key, ficha.fingerprint, imageCounts(status))
            }
            download = Download(key, ref, job)
        }
    }

    /**
     * El acuse con los contadores del logo, si no es el último que ya quedó en disco. `lastImagesAck` sólo se anota tras
     * escribir: si el disco falla, el siguiente poll lo vuelve a encolar (ver el paso 5 de [afterPoll]).
     */
    private suspend fun enqueueImagesAck(generation: Long, venueId: String, key: String, fingerprint: String, counts: Triple<Int, Int, Int>) {
        if (synchronized(lastImagesAck) { lastImagesAck[key] } == (fingerprint to counts)) return
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
        synchronized(lastImagesAck) { lastImagesAck[key] = fingerprint to images }
    }

    private fun cancelDownload() {
        synchronized(downloadLock) {
            download?.job?.cancel()
            download = null
        }
    }

    /** Retira el diseño al instante y publica lo guardado de la sesión actual (la última terminal de esa sucursal). */
    private fun restart() {
        val generation = generation.incrementAndGet()
        cancelDownload()
        // Main: regla de CustomerDisplayState (sin candado). Inmediato si quien llama ya está en Main.
        scope.launch(Dispatchers.Main.immediate + logErrors) {
            state.clearDesign()
            val offersSeen = offers.get()
            val venueId = sessionVenue() ?: return@launch
            val stored = withContext(Dispatchers.IO) { storedDesign(venueId) } ?: return@launch
            publish(generation, venueId, stored, onlyIfNoOfferSince = offersSeen)
        }
    }

    private fun storedDesign(venueId: String): AppliedDesign? {
        val env = environment()
        val s = store.load()
        val terminalId = s.lastTerminalByVenue[store.venueKey(env, venueId)] ?: return null
        val manifest = s.manifests[store.manifestKey(env, venueId, terminalId)] ?: return null
        val ficha = manifest.ficha?.toFicha(manifest.imageOrigins) ?: return null
        return applied(ficha)
    }

    /** Los logos de TODOS los manifiestos guardados (cualquier sucursal o terminal): lo que la poda no puede borrar. */
    private fun referencedLogos(): Set<LogoRef> =
        store.load().manifests.values.mapNotNullTo(HashSet()) { it.ficha?.toFicha(it.imageOrigins)?.logo }

    /** El ÚNICO que arma el diseño: el logo de disco (sólo disco, nunca red) con su marca de `lastModified`. */
    private fun applied(ficha: Ficha): AppliedDesign {
        val logo = ficha.logo?.let(images::cachedFile)
        return AppliedDesign(ficha, logo, logoStamp = logo?.lastModified() ?: 0L)
    }

    /** Sigue siendo la misma sesión y el mismo vínculo que cuando empezó el trabajo. */
    private fun current(generation: Long, venueId: String) = this.generation.get() == generation && sessionVenue() == venueId

    /** Escribe sólo si sigue siendo la misma generación y sucursal (comprobado DENTRO del candado del almacén). */
    private suspend fun write(generation: Long, venueId: String, change: (StoredDesignState) -> StoredDesignState): Boolean {
        currentCoroutineContext().ensureActive()
        var applied = false
        store.update { s -> if (current(generation, venueId)) change(s).also { applied = true } else s }
        return applied
    }

    private suspend fun publish(generation: Long, venueId: String, design: AppliedDesign?, onlyIfNoOfferSince: Int? = null) {
        currentCoroutineContext().ensureActive()
        // Main: regla de CustomerDisplayState (sin candado). La comprobación va en el MISMO bloque de Main que el
        // ofrecimiento, así un clearDesign de otra sesión nunca queda debajo de un diseño viejo.
        withContext(Dispatchers.Main.immediate) {
            if (current(generation, venueId) && (onlyIfNoOfferSince == null || offers.get() == onlyIfNoOfferSince)) {
                state.offerDesign(design)
                offers.incrementAndGet()
            }
        }
    }

    /**
     * Manda el acuse pendiente de esa terminal tal como quedó congelado al encolarlo (mismo cuerpo y `seq`, aunque la app
     * se haya reiniciado) y obedece la respuesta ([ackActionFor]). Lo que escribe pasa por [write]: si cambió la sesión o
     * la sucursal mientras viajaba, no toca nada.
     */
    private suspend fun sendPendingAck(generation: Long, venueId: String, key: String) {
        if (!current(generation, venueId)) return
        val sent = store.load().manifests[key]?.pendingAck ?: return
        val outcome = remote.acknowledgeCustomerDisplay(venueId, sent)
        val manifest = store.load().manifests[key]
        val action = ackActionFor(outcome, manifest?.ficha?.fingerprint)
        if (action == AckAction.Keep) return
        val counts = logoCounts(manifest?.ficha?.toFicha(manifest.imageOrigins)?.logo)
        var reAcked: String? = null
        val applied = write(generation, venueId) { s ->
            val m = s.manifests[key]
            if (m == null || m.pendingAck?.seq != sent.seq) {
                // Cerrar borra el pendiente SÓLO si sigue siendo el que se mandó: uno más nuevo sale en el siguiente poll.
                // Que otra instalación tomó la fila es verdad aunque haya otro acuse pendiente.
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
