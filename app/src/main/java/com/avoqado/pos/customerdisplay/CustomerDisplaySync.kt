package com.avoqado.pos.customerdisplay

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.kiosk.domain.KioskState
import java.time.Instant
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
 * 3. Sólo existe el acuse: uno pendiente por terminal, `seq` monótono, el nuevo reemplaza al viejo.
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
        if (announced != null && announced != known?.ficha?.fingerprint && announced != known?.unsupportedFingerprint) {
            when (val outcome = remote.fetchCustomerDisplay(venueId)) {
                is DisplayModeRemoteOutcome.Success -> saveFicha(generation, venueId, key, announced, outcome.data)
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
        publish(generation, venueId, ficha?.let { AppliedDesign(it, it.logo?.let(images::cachedFile)) })
        // (5) Sin logo en disco, o legado que toca revalidar (la copia vigente sigue visible mientras tanto).
        val logo = ficha?.logo
        if (logo != null && (images.cachedFile(logo) == null || images.revalidationDue(logo))) {
            ensureDownload(generation, venueId, key, logo, manifest.imageOrigins)
        }
        // (6)
        sendPendingAck(venueId, key)
        retrySoon
    }

    /** Login, logout o cambio de sucursal: retira el diseño AL INSTANTE y publica (sin red) lo guardado de la nueva. */
    fun onSessionChanged() = restart()

    /**
     * El aparato ya no está ligado a esa sucursal (404 `DEVICE_NOT_FOUND`): fábrica al instante y se borran sus
     * manifiestos (con su acuse pendiente) y su última terminal. Así, aunque arranque sin red, no revive ese diseño.
     */
    fun onDeviceUnbound(venueId: String) {
        generation.incrementAndGet()
        cancelDownload()
        // Main: regla de CustomerDisplayState (sin candado). Inmediato si quien llama ya está en Main.
        scope.launch(Dispatchers.Main.immediate + logErrors) { state.clearDesign() }
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
     * nunca regresa «Mostrado» a «Guardado». El `seq` real lo asigna [enqueueAck] dentro del mismo `update` que lo reserva.
     */
    internal fun buildAck(
        manifest: DesignManifest,
        fingerprint: String,
        unsupported: Boolean,
        images: Triple<Int, Int, Int>,
    ): CustomerDisplayAckBody = CustomerDisplayAckBody(
        installationId = store.load().installationId,
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
        if (dto.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            // Esquema que esta versión no entiende: se conserva el diseño actual y se le dice al server.
            val saved = write(generation, venueId) { s ->
                val kept = (s.manifests[key] ?: DesignManifest(storedAt = isoNow())).copy(unsupportedFingerprint = announced)
                enqueueAck(s, key, kept, announced, unsupported = true, images = NO_IMAGES)
            }
            if (saved) rememberImages(key, announced, NO_IMAGES)
            return
        }
        val ficha = dto.toFicha(payload.imageOrigins)
        if (ficha == null) {
            Log.w(TAG, "Ficha de la pantalla del cliente sin huella válida; se ignora")
            return
        }
        val counts = imageCounts(ficha.logo?.let { if (images.cachedFile(it) != null) ImageStatus.READY else ImageStatus.PENDING })
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
        val body = buildAck(manifest, fingerprint, unsupported, images).copy(installationId = s.installationId, seq = s.nextSeq)
        return s.copy(nextSeq = s.nextSeq + 1, manifests = s.manifests + (key to manifest.copy(pendingAck = body)))
    }

    /** UN trabajo de descarga: el de la misma llave y logo se deja correr; cualquier otro se cancela y se lanza el nuevo. */
    private fun ensureDownload(generation: Long, venueId: String, key: String, ref: LogoRef, origins: List<String>) {
        synchronized(downloadLock) {
            val running = download
            if (running != null && running.job.isActive && running.key == key && running.ref == ref) return
            running?.job?.cancel()
            val job = scope.launch(logErrors) {
                val status = images.ensure(ref, origins)
                // Mientras bajaba pudo cambiar la sesión, desligarse el aparato o llegar otra ficha: se publica lo que
                // diga el manifiesto AHORA, y `publish`/`write` descartan si la generación o la sucursal ya no son.
                val manifest = store.load().manifests[key] ?: return@launch
                val ficha = manifest.ficha?.toFicha(manifest.imageOrigins) ?: return@launch
                publish(generation, venueId, AppliedDesign(ficha, ficha.logo?.let(images::cachedFile)))
                if (ficha.logo != ref) return@launch
                val counts = imageCounts(status)
                if (synchronized(lastImagesAck) { lastImagesAck[key] } == (ficha.fingerprint to counts)) return@launch
                var enqueued = false
                write(generation, venueId) { s ->
                    val current = s.manifests[key]
                    if (current?.ficha?.fingerprint != ficha.fingerprint) s   // otra ficha entretanto: ese acuse ya no es
                    else enqueueAck(s, key, current, ficha.fingerprint, unsupported = false, images = counts).also { enqueued = true }
                }
                if (enqueued) rememberImages(key, ficha.fingerprint, counts)
            }
            download = Download(key, ref, job)
        }
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
            val venueId = sessionVenue() ?: return@launch
            val stored = withContext(Dispatchers.IO) { storedDesign(venueId) } ?: return@launch
            publish(generation, venueId, stored)
        }
    }

    private fun storedDesign(venueId: String): AppliedDesign? {
        val env = environment()
        val s = store.load()
        val terminalId = s.lastTerminalByVenue[store.venueKey(env, venueId)] ?: return null
        val manifest = s.manifests[store.manifestKey(env, venueId, terminalId)] ?: return null
        val ficha = manifest.ficha?.toFicha(manifest.imageOrigins) ?: return null
        return AppliedDesign(ficha, ficha.logo?.let(images::cachedFile))
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

    private suspend fun publish(generation: Long, venueId: String, design: AppliedDesign?) {
        currentCoroutineContext().ensureActive()
        // Main: regla de CustomerDisplayState (sin candado). La comprobación va en el MISMO bloque de Main que el
        // ofrecimiento, así un clearDesign de otra sesión nunca queda debajo de un diseño viejo.
        withContext(Dispatchers.Main.immediate) {
            if (current(generation, venueId)) state.offerDesign(design)
        }
    }

    /** Task 2.5: manda el acuse pendiente de esa terminal y obedece la respuesta (tabla de `ackActionFor`). */
    @Suppress("UNUSED_PARAMETER", "RedundantSuspendModifier")
    private suspend fun sendPendingAck(venueId: String, key: String) = Unit

    private fun isoNow(): String = Instant.ofEpochMilli(wallClock()).toString()

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
    }
}
