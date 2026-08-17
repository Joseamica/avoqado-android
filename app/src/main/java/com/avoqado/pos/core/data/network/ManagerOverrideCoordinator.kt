package com.avoqado.pos.core.data.network

import android.util.Log
import com.avoqado.pos.core.domain.PermissionLabels
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orquesta el PIN de autorización de gerente.
 *
 * 🔴 `awaitToken` BLOQUEA el hilo que lo llama — que es el hilo de red del
 * interceptor, nunca el de UI. Es a propósito: si el token llegara "por fuera",
 * el ViewModel que hizo la llamada ya habría pintado un error y el éxito del
 * reintento sería invisible. Mismo patrón que `TokenRefreshAuthenticator` con
 * el 401.
 *
 * El `Mutex` garantiza UN teclado a la vez: dos acciones bloqueadas al mismo
 * tiempo hacen fila en vez de apilar dos diálogos.
 */
@Singleton
open class ManagerOverrideCoordinator @Inject constructor(
    private val repository: PermissionOverrideRepository,
) {
    /**
     * @param id identifica ESTE teclado. La UI lo usa como key de su estado, de
     *   forma que el PIN tecleado en uno nunca sobreviva al siguiente, y para
     *   que una cancelación tardía no tumbe la espera de otra acción.
     */
    data class Prompt(val id: Long, val permission: String, val actionLabel: String)

    private val _prompt = MutableStateFlow<Prompt?>(null)
    val prompt: StateFlow<Prompt?> = _prompt.asStateFlow()

    companion object {
        /**
         * Tope de la espera del teclado. Generoso a propósito —el encargado
         * puede venir del otro lado del local— pero finito: el hilo de red que
         * espera no puede quedarse tomado para siempre.
         */
        const val PROMPT_TIMEOUT_MS = 120_000L

        /**
         * Cuánto vale un token pedido POR ADELANTADO antes de tirarlo.
         *
         * 🔴 El del server vive 60 s (`OVERRIDE_TTL_MS`), así que aquí va MENOS:
         * mandar un token ya vencido es peor que no mandar ninguno — la petición
         * saldría CON el header, y el guard de `ForbiddenInterceptor` ("si ya
         * traía token, no vuelvas a pedir") apagaría el teclado justo cuando
         * hacía falta. Con margen, un token dudoso se descarta y el 403 abre el
         * teclado como siempre.
         */
        const val PREAUTH_TTL_MS = 45_000L
    }

    /**
     * Tope efectivo de la espera. Es `open` para que las pruebas puedan usar
     * milisegundos en vez de dos minutos: `awaitToken` bloquea de verdad y su
     * timeout corre en tiempo real, así que no hay scheduler virtual que valga.
     */
    internal open val promptTimeoutMs: Long = PROMPT_TIMEOUT_MS

    /** Vigencia efectiva del token adelantado. `open` por la misma razón. */
    internal open val preauthTtlMs: Long = PREAUTH_TTL_MS

    /** Reloj MONOTÓNICO: mide tiempo transcurrido, inmune a que el reloj salte. */
    internal open val nowNanos: () -> Long = System::nanoTime

    private val queue = Mutex()

    /**
     * La espera viva, en UN solo objeto.
     *
     * 🔴 El deferred, el permiso y la identidad del teclado se leen JUNTOS o no
     * se leen. Cuando vivían en campos separados (`pending` + `_prompt`),
     * `submitPin` podía capturar el deferred de A y, una línea después, el
     * permiso de B —porque el timeout de A y el `awaitToken` de B corren en
     * otro hilo—: se pedía un token para B y se completaba la espera de A, que
     * ya no tenía a nadie escuchando, mientras B seguía esperando para siempre
     * aunque la UI dijera "autorizado".
     */
    private data class Waiting(
        val id: Long,
        val permission: String,
        val deferred: CompletableDeferred<String?>,
    )

    @Volatile
    private var pending: Waiting? = null

    /** Sube con cada teclado presentado; identifica QUÉ espera está viva. */
    private var nextId: Long = 0

    /**
     * Devuelve el token, o null si el usuario canceló / caducó / no se pudo.
     *
     * 🔴 La espera ESTÁ ACOTADA. Sin tope, un teclado que nadie contesta —la
     * tablet quedó en el mostrador, o la sesión expiró y el host que lo pinta se
     * desmontó sin cancelar— dejaba el hilo de red bloqueado para siempre CON el
     * Mutex tomado: a partir de ahí todo 403 overridable de la app se encolaba
     * detrás de un candado que ya no abría, y el PIN dejaba de funcionar hasta
     * matar la app. Al vencer se devuelve null, que el interceptor ya trata como
     * "canceló": la acción falla con su mensaje de siempre.
     */
    open fun awaitToken(permission: String): String? = runBlocking {
        // Si la UI ya pidió el PIN al TOCAR el botón, ese token se usa aquí y
        // nadie vuelve a ver un teclado. Sin esto, adelantar el PIN lo pediría
        // DOS veces por la misma acción.
        takePreauthorized(permission) ?: promptForToken(permission)
    }

    /**
     * Pide el PIN POR ADELANTADO, desde la UI, ANTES de abrir un formulario.
     *
     * 🔴 Existe porque el teclado llegaba DEMASIADO TARDE: sólo aparecía cuando
     * el server rechazaba, o sea después de que el cajero llenó importe, motivo
     * y propina. Si el encargado no estaba cerca, todo ese trabajo se perdía.
     * Cuando la app YA SABE que la acción está bloqueada (el candado), preguntar
     * primero cuesta un toque y no cuesta un formulario.
     *
     * @return true si alguien autorizó; false si canceló, venció o falló.
     */
    open suspend fun preauthorize(permission: String): Boolean {
        // ¿Ya hay uno vivo? Tocar dos veces el mismo botón no puede costar dos
        // PINes — y el token NO se consume aquí: lo consume la petición real.
        if (peekPreauthorized(permission) != null) return true
        val token = promptForToken(permission) ?: return false
        stashPreauthorized(permission, token)
        return true
    }

    /** El teclado de verdad: una espera a la vez, acotada, y limpia al salir. */
    private suspend fun promptForToken(permission: String): String? = queue.withLock {
        val deferred = CompletableDeferred<String?>()
        val id = ++nextId
        pending = Waiting(id, permission, deferred)
        _prompt.value = Prompt(id, permission, PermissionLabels.of(permission))
        try {
            withTimeoutOrNull(promptTimeoutMs) { deferred.await() }
        } finally {
            _prompt.value = null
            pending = null
        }
    }

    // MARK: - El token adelantado
    //
    // 🔴 Se guarda con el PERMISO al que pertenece y con su edad. Un token de
    // `payments:refund` NUNCA puede saldar un 403 de otra cosa: en el server es
    // de un solo uso Y de un solo permiso, así que reusarlo cruzado sólo lograría
    // quemarlo y dejar la acción real sin autorización.

    private data class Preauthorized(val permission: String, val token: String, val atNanos: Long)

    @Volatile
    private var preauthorized: Preauthorized? = null

    private fun vigente(entry: Preauthorized?, permission: String): Preauthorized? {
        if (entry == null || entry.permission != permission) return null
        val edadMs = (nowNanos() - entry.atNanos) / 1_000_000
        return if (edadMs < preauthTtlMs) entry else null
    }

    @Synchronized
    private fun stashPreauthorized(permission: String, token: String) {
        preauthorized = Preauthorized(permission, token, nowNanos())
    }

    @Synchronized
    private fun peekPreauthorized(permission: String): String? = vigente(preauthorized, permission)?.token

    /** Un solo uso también de este lado: al entregarlo, se borra. */
    @Synchronized
    private fun takePreauthorized(permission: String): String? {
        val vivo = vigente(preauthorized, permission) ?: return null
        preauthorized = null
        return vivo.token
    }

    /** La UI llama esto al teclear el código. Sólo `Granted` cierra el diálogo. */
    open suspend fun submitPin(venueId: String, pin: String): OverrideResult {
        // 🔴 UNA sola lectura: deferred, permiso e identidad salen del MISMO
        // objeto, así que no pueden pertenecer a esperas distintas.
        //
        // Antes eran dos lecturas (`pending` y luego `_prompt.value.permission`)
        // y el timeout de A más el `awaitToken` de B corren en otro hilo: se
        // podía pedir un token para el permiso de B y completar la espera de A,
        // que ya no tenía a nadie escuchando, dejando a B esperando para siempre
        // aunque la UI dijera "autorizado".
        //
        // Si esta espera ya se resolvió (cancelación, timeout), `complete` no
        // hace nada y el token simplemente se descarta.
        val awaiting = pending
            ?: return OverrideResult.Failed("La acción ya no está esperando autorización.")
        if (venueId.isBlank()) {
            return OverrideResult.Failed("No hay una sucursal activa. Vuelve a entrar.")
        }
        val result = repository.requestToken(venueId, pin, awaiting.permission)
        if (result is OverrideResult.Granted) {
            Log.d("🔐 Override", "Autorizado por ${result.authorizedByName} para ${awaiting.permission}")
            awaiting.deferred.complete(result.token)
        }
        return result
    }

    /**
     * El usuario cerró el teclado: la acción falla como fallaba antes.
     *
     * @param promptId id del teclado que se estaba viendo. Si ya no es el
     *   vigente, la cancelación llega TARDE —ese teclado se cerró solo al
     *   autorizar o al vencer, y otra acción de la fila ya instaló el suyo— y se
     *   ignora en vez de tumbar la espera de la siguiente. Espejo de iOS.
     */
    open fun cancel(promptId: Long? = null) {
        val awaiting = pending ?: return
        if (promptId != null && awaiting.id != promptId) return
        awaiting.deferred.complete(null)
    }
}
