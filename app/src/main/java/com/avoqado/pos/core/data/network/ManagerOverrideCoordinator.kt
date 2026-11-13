package com.avoqado.pos.core.data.network

import android.util.Log
import com.avoqado.pos.core.domain.PermissionLabels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
 * La fila garantiza UN teclado a la vez: dos acciones bloqueadas al mismo
 * tiempo hacen fila en vez de apilar dos diálogos.
 *
 * D24 (4-nov-2026): las consultas de una misma RÁFAGA (la marca `X-Avoqado-Rafaga`, que hoy pone Informes en sus 4
 * consultas) y del mismo permiso comparten UN teclado. Con el PIN se pide un token de un solo uso para cada una, y cada
 * una lo recibe en cuanto sale; al cancelar o vencer, se sueltan todas y la ráfaga queda anotada para que una respuesta
 * tardía no vuelva a preguntar. Sin marca, cada petición pide su PIN como siempre.
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
    data class Prompt(
        val id: Long,
        val permission: String,
        val actionLabel: String,
        /** Renglón extra para el gerente («Descuento de Cecilia −$10.00»); lo pinta el anfitrión del teclado. `null` = el de siempre. */
        val contexto: String? = null,
    )

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

        /** D24: cuántas ráfagas soltadas se recuerdan (una por carga de Informes; sobra). */
        private const val RAFAGAS_RECORDADAS = 32

        private const val YA_NO_ESPERA = "La acción ya no está esperando autorización."

        /** Cada cuánto mira una petición en espera si su llamada sigue en pie. */
        private const val VIGILANCIA_MS = 250L
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

    /**
     * Donde corre el reloj de cada teclado (su vencimiento). D24: el teclado ya no vive en el hilo de quien lo abrió,
     * porque esa consulta tiene que poder irse con su token en cuanto sale (Codex r1 #3: retenerlo mientras se reparten
     * los demás podía dejarlo vencido, 60 s en el servidor) sin que se cierre el teclado de las demás.
     */
    private val alcance = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Una petición que espera su token. [vivo] dice si su llamada sigue en pie (D22 corta la que deja de verse). */
    private class Espera(val vivo: () -> Boolean) {
        val token = CompletableDeferred<String?>()
    }

    /**
     * Las peticiones que comparten UN teclado: la que lo abrió y, si traían marca de ráfaga, las de esa misma ráfaga y
     * permiso que llegaron mientras estaba en fila o a la vista. Todo su estado mutable se toca con [candado].
     */
    private class Grupo(
        val rafaga: String?,
        val permission: String,
        abrio: Espera,
        /** Si viene, el token del servidor sólo sirve para esa operación. */
        val operationKey: String? = null,
        val contexto: String? = null,
    ) {
        /** Por orden de llegada; la primera es la que abrió. Sale de aquí al recibir su token o al soltarse. */
        val pendientes = ArrayDeque<Espera>().apply { addLast(abrio) }
        var unidas = 0
        /** Ya nadie se une: una consulta que llega después abre su propio teclado (o recibe null si se canceló). */
        var cerrado = false
        /** El PIN ya sacó al menos un token: desde aquí un fallo suelta a las que faltan en vez de dejar el teclado. */
        var autorizado = false
        /** Terminó de repartir a TODAS. Sólo así no se anota la ráfaga como soltada (Codex código r1 #2). */
        var exito = false
        /** El reloj de su teclado: al vencer, se suelta el grupo. Se apaga al retirar el teclado. */
        var vence: Job? = null
        /**
         * Un envío a la vez POR TECLADO: dos toques casi juntos en «Autorizar» no reparten dos veces al mismo grupo. Es de
         * cada grupo y no de toda la app (Codex código r2): una autorización vieja atorada en la red no detiene el PIN del
         * teclado siguiente.
         */
        val envio = Mutex()
    }

    /**
     * La espera viva, en UN solo objeto.
     *
     * 🔴 El grupo, el permiso y la identidad del teclado se leen JUNTOS o no
     * se leen. Cuando vivían en campos separados (`pending` + `_prompt`),
     * `submitPin` podía capturar el deferred de A y, una línea después, el
     * permiso de B —porque el timeout de A y el `awaitToken` de B corren en
     * otro hilo—: se pedía un token para B y se completaba la espera de A, que
     * ya no tenía a nadie escuchando, mientras B seguía esperando para siempre
     * aunque la UI dijera "autorizado".
     */
    private data class Waiting(val id: Long, val grupo: Grupo)

    @Volatile
    private var pending: Waiting? = null

    /** Sube con cada teclado presentado; identifica QUÉ espera está viva. */
    private var nextId: Long = 0

    private val candado = Any()

    /** El grupo cuyo teclado está a la vista, y los que esperan su turno, en orden de llegada. Bajo [candado]. */
    private var vigente: Grupo? = null
    private val fila = ArrayDeque<Grupo>()

    /** D24: los grupos de ráfaga a los que todavía se puede unir alguien, por (ráfaga, permiso). */
    private val gruposAbiertos = HashMap<Pair<String, String>, Grupo>()

    /** D24: ráfagas que se cancelaron o vencieron; su respuesta tardía recibe null sin teclado. Sólo las últimas 32. */
    private val rafagasSoltadas = LinkedHashSet<Pair<String, String>>()

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
     *
     * @param rafaga D24: la marca de las consultas que salieron juntas (Informes). Con ella, las de la misma ráfaga y
     *   permiso comparten el teclado. `null` = un teclado por petición, como siempre.
     * @param vivo si la llamada sigue en pie. Al repartir, a una cancelada no se le pide token (no gasta un intento).
     */
    open fun awaitToken(permission: String, rafaga: String? = null, vivo: () -> Boolean = { true }): String? = runBlocking {
        // Si la UI ya pidió el PIN al TOCAR el botón, ese token se usa aquí y
        // nadie vuelve a ver un teclado. Sin esto, adelantar el PIN lo pediría
        // DOS veces por la misma acción.
        takePreauthorized(permission, null)?.let { return@runBlocking it }

        // Una llamada que ya se cortó no pide teclado (D26, Codex código r5: otra fecha antes de que llegue el 403).
        if (!vivo()) return@runBlocking null
        val espera = Espera(vivo)
        val clave = rafaga?.let { it to permission }
        var suGrupo: Grupo? = null
        val nuevo: Grupo? = synchronized(candado) {
            // Codex r1 #1: la misma carga ya se canceló o venció. No se vuelve a preguntar.
            if (clave != null && clave in rafagasSoltadas) {
                espera.token.complete(null)
                return@synchronized null
            }
            val abierto = clave?.let { gruposAbiertos[it] }
            if (abierto != null && !abierto.cerrado) {
                abierto.pendientes.addLast(espera)
                abierto.unidas++
                suGrupo = abierto
                null
            } else {
                Grupo(rafaga, permission, espera).also {
                    if (clave != null) gruposAbiertos[clave] = it
                    suGrupo = it
                }
            }
        }
        if (nuevo != null) presentar(nuevo)
        val grupo = suGrupo ?: return@runBlocking espera.token.await()
        // Se espera vigilando la llamada: si se corta (la pantalla dejó de verse, otra fecha, venció su plazo), se va sin
        // token, y si ya no queda nadie esperando el teclado, el teclado se retira (D26, Codex código r4–r5: la familia
        // «una carga cancelada sostiene su PIN», arreglada de raíz y no por cada camino que cancela).
        while (true) {
            withTimeoutOrNull(VIGILANCIA_MS) { espera.token.await() }
            if (espera.token.isCompleted) return@runBlocking espera.token.await()
            if (!espera.vivo()) {
                abandonar(grupo, espera)
                return@runBlocking espera.token.await()
            }
        }
        null
    }

    /**
     * La llamada de [espera] se cortó: sale de su grupo sin token. Si era la última que esperaba ese teclado (y nadie lo
     * está repartiendo), el grupo se suelta: el teclado se retira, o sale de la fila sin asomarse.
     */
    private fun abandonar(grupo: Grupo, espera: Espera) {
        val soltarGrupo = synchronized(candado) {
            // Si ya no estaba en la fila, alguien más decidió por ella (le entregaron su token o se soltó el grupo).
            if (!grupo.pendientes.remove(espera)) return@synchronized false
            grupo.pendientes.isEmpty() && !grupo.cerrado
        }
        espera.token.complete(null)
        if (soltarGrupo) soltar(grupo)
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
    open suspend fun preauthorize(permission: String, operationKey: String? = null, contexto: String? = null): Boolean {
        // ¿Ya hay uno vivo? Tocar dos veces el mismo botón no puede costar dos
        // PINes — y el token NO se consume aquí: lo consume la petición real.
        if (peekPreauthorized(permission, operationKey) != null) return true
        val espera = Espera { true }
        val grupo = Grupo(null, permission, espera, operationKey, contexto)
        presentar(grupo)
        val token = try {
            espera.token.await()
        } catch (e: CancellationException) {
            // Quien lo pidió ya no espera (salió de la pantalla): el teclado no se queda abierto para nadie.
            soltar(grupo)
            throw e
        } ?: return false
        stashPreauthorized(permission, operationKey, token)
        return true
    }

    /**
     * El teclado de verdad: UNO a la vez y en orden de llegada (la fila), acotado por su reloj, y limpio al salir.
     *
     * 🔴 Sin fila se publica YA, en el hilo de quien lo pide, como antes de D24: quien llama a [preauthorize] encuentra el
     * teclado puesto al volver, y al terminar uno el siguiente de la fila se publica en el acto ([retirar]). Todo bajo
     * [candado]: el estado de la fila no depende de cuándo despierte otro hilo.
     */
    private fun presentar(grupo: Grupo) {
        synchronized(candado) {
            if (vigente == null) publicar(grupo) else fila.addLast(grupo)
        }
    }

    /** Bajo [candado]: pone el teclado de [grupo] a la vista y arranca su reloj. */
    private fun publicar(grupo: Grupo) {
        vigente = grupo
        val id = ++nextId
        tecladosPublicados++
        pending = Waiting(id, grupo)
        _prompt.value = Prompt(id, grupo.permission, PermissionLabels.of(grupo.permission), grupo.contexto)
        // Vencer = cancelar: se suelta a quien esperaba y pasa el siguiente.
        grupo.vence = alcance.launch {
            delay(promptTimeoutMs)
            soltar(grupo)
        }
    }

    /**
     * Bajo [candado]: el teclado de [grupo] ya no hace falta (repartió, se canceló o venció). Si estaba a la vista se
     * quita y se publica el siguiente de la fila que siga abierto; si hacía fila, sale de ella sin asomarse nunca (Codex
     * código r1 #3).
     */
    private fun retirar(grupo: Grupo) {
        if (vigente !== grupo) {
            fila.remove(grupo)
            return
        }
        grupo.vence?.cancel()
        vigente = null
        if (pending?.grupo === grupo) pending = null
        _prompt.value = null
        while (vigente == null && fila.isNotEmpty()) {
            val siguiente = fila.removeFirst()
            if (!siguiente.cerrado) publicar(siguiente)
        }
    }

    /**
     * Cierra el grupo, quita su teclado y suelta con null a las que no alcanzaron token. Si no terminó de repartir a
     * todas (canceló, venció, se fue la pantalla, faltó un token), su ráfaga queda anotada aunque alguna ya tuviera token:
     * una respuesta tardía de esa misma carga no vuelve a preguntar (Codex código r1 #2).
     *
     * 🔴 Quién se queda con cada espera se decide bajo [candado] (aquí se sacan de la fila; [enviar] saca la suya antes de
     * entregarle el token): «soltar» y «entregar» nunca reclaman a la misma (Codex código r1 #1). Se completan fuera del
     * candado, para que nadie que despierte en este mismo hilo vuelva a entrar a medio cambio.
     */
    private fun soltar(grupo: Grupo) {
        val sueltas = synchronized(candado) {
            val clave = grupo.rafaga?.let { it to grupo.permission }
            if (clave != null && !grupo.exito) {
                rafagasSoltadas.add(clave)
                while (rafagasSoltadas.size > RAFAGAS_RECORDADAS) rafagasSoltadas.remove(rafagasSoltadas.first())
            }
            cerrarAUniones(grupo)
            retirar(grupo)
            grupo.pendientes.toList().also { grupo.pendientes.clear() }
        }
        sueltas.forEach { it.token.complete(null) }
    }

    /** Pruebas (D24): cuántos teclados se han publicado en total. */
    @Volatile
    internal var tecladosPublicados = 0
        private set

    /** Pruebas (D24): cuántas peticiones se unieron al grupo abierto de [rafaga] y [permission] (sin la que lo abrió). */
    internal fun unidasAlGrupo(rafaga: String, permission: String): Int =
        synchronized(candado) { gruposAbiertos[rafaga to permission]?.unidas ?: 0 }

    // MARK: - El token adelantado
    //
    // 🔴 Se guarda con el PERMISO al que pertenece y con su edad. Un token de
    // `payments:refund` NUNCA puede saldar un 403 de otra cosa: en el server es
    // de un solo uso Y de un solo permiso, así que reusarlo cruzado sólo lograría
    // quemarlo y dejar la acción real sin autorización.

    private data class Preauthorized(val permission: String, val operationKey: String?, val token: String, val atNanos: Long)

    @Volatile
    private var preauthorized: Preauthorized? = null

    private fun vigente(entry: Preauthorized?, permission: String, operationKey: String? = null): Preauthorized? {
        if (entry == null || entry.permission != permission || entry.operationKey != operationKey) return null
        val edadMs = (nowNanos() - entry.atNanos) / 1_000_000
        return if (edadMs < preauthTtlMs) entry else null
    }

    @Synchronized
    private fun stashPreauthorized(permission: String, operationKey: String?, token: String) {
        preauthorized = Preauthorized(permission, operationKey, token, nowNanos())
    }

    @Synchronized
    private fun peekPreauthorized(permission: String, operationKey: String?): String? =
        vigente(preauthorized, permission, operationKey)?.token

    /**
     * Tira el token adelantado de [permission] sin usarlo. Lo llama quien lo pidió cuando la acción se abandona
     * (cerrar «Editar cliente»): si no, durante sus 45 s autorizaría otra acción igual sin que nadie tecleara el PIN.
     */
    @Synchronized
    open fun descartarPreautorizado(permission: String) {
        if (preauthorized?.permission == permission) preauthorized = null
    }

    /** Un solo uso también de este lado: al entregarlo, se borra. */
    @Synchronized
    private fun takePreauthorized(permission: String, operationKey: String?): String? {
        val vivo = vigente(preauthorized, permission, operationKey) ?: return null
        preauthorized = null
        return vivo.token
    }

    /**
     * hz:e0b12f00:D2: el token adelantado de [permission], para la llamada que lo pone ELLA en su header. La ruta del dinero es
     * `FAIL_FAST`: ahí el interceptor no abre el teclado ni toma el token, así que la venta lo lleva a mano. Un solo uso.
     */
    open fun tomarPreautorizado(permission: String, operationKey: String? = null): String? =
        takePreauthorized(permission, operationKey)

    @Synchronized
    open fun discardPreauthorized(permission: String) {
        if (preauthorized?.permission == permission) preauthorized = null
    }

    /**
     * La UI llama esto al teclear el código. Sólo `Granted` cierra el diálogo.
     *
     * @param promptId el teclado en el que se tecleó. Si ya no es el vivo (Codex D24 r1 #2: A venció y la acción B de la
     *   fila instaló el suyo antes de que la pantalla se redibujara), no se pide NINGÚN token: el PIN que el encargado
     *   tecleó para A no autoriza a B. `null` = el vivo (pruebas y llamadas viejas).
     */
    open suspend fun submitPin(venueId: String, pin: String, promptId: Long? = null): OverrideResult {
        // 🔴 UNA sola lectura: grupo, permiso e identidad salen del MISMO
        // objeto, así que no pueden pertenecer a esperas distintas.
        val awaiting = pending
            ?: return OverrideResult.Failed(YA_NO_ESPERA)
        if (promptId != null && awaiting.id != promptId) return OverrideResult.Failed(YA_NO_ESPERA)
        if (venueId.isBlank()) {
            return OverrideResult.Failed("No hay una sucursal activa. Vuelve a entrar.")
        }
        return awaiting.grupo.envio.withLock {
            // Mientras esperaba su turno, el envío anterior pudo terminar de repartir o el teclado cerrarse.
            if (pending !== awaiting) OverrideResult.Failed(YA_NO_ESPERA) else enviar(awaiting, venueId, pin)
        }
    }

    private suspend fun enviar(awaiting: Waiting, venueId: String, pin: String): OverrideResult {
        val grupo = awaiting.grupo
        var autorizacion: OverrideResult.Granted? = null
        try {
            while (true) {
                val turno = siguienteTurno(grupo) ?: break
                // El PIN viaja una vez por token: el servidor lo valida y lo audita cada vez. Nunca se guarda.
                val result = repository.requestToken(venueId, pin, grupo.permission, grupo.operationKey)
                if (result is OverrideResult.Granted) {
                    // Entregar y soltar se deciden bajo el MISMO candado (Codex código r1 #1): si mientras viajaba este
                    // token se canceló o venció el teclado, ya se soltó a todas y el token se tira sin usarse.
                    val entrega = synchronized(candado) {
                        if (grupo.cerrado) return@synchronized Entrega.DESCARTADA
                        grupo.autorizado = true
                        // Su llamada se cortó mientras salía el token: éste se tira y se sigue con las demás.
                        if (!grupo.pendientes.remove(turno)) return@synchronized Entrega.OMITIDA
                        // Ya no queda nadie: se cierra a nuevas uniones (una tardía abre su propio teclado).
                        val ultima = grupo.pendientes.isEmpty()
                        if (ultima) {
                            cerrarAUniones(grupo)
                            grupo.exito = true
                            // La última sale con el teclado ya cerrado, como siempre: la acción que sigue nunca ve el
                            // anterior.
                            retirar(grupo)
                        }
                        if (ultima) Entrega.ULTIMA else Entrega.SIGUE
                    }
                    // Se entrega en cuanto sale (ya es sólo suya: salió de la fila bajo el candado): nadie retiene un
                    // token mientras se reparten los demás.
                    if (entrega == Entrega.SIGUE || entrega == Entrega.ULTIMA) turno.token.complete(result.token)
                    if (entrega == Entrega.DESCARTADA) {
                        Log.w("🔐 Override", "El teclado se cerró mientras salía un token de ${grupo.permission}; se tira")
                        break
                    }
                    if (autorizacion == null) {
                        Log.d("🔐 Override", "Autorizado por ${result.authorizedByName} para ${grupo.permission}")
                    }
                    autorizacion = autorizacion ?: result
                    if (entrega == Entrega.ULTIMA) break
                    // OMITIDA: el turno siguiente lo decide `siguienteTurno` (si ya no queda nadie, cierra el grupo).
                } else if (autorizacion == null) {
                    // El PIN no sirvió (o no se pudo validar): el teclado sigue y nadie se suelta, como siempre.
                    return result
                } else {
                    // Ya se autorizó, pero este token no salió (429, sin red): ésta y las que faltan reciben null.
                    Log.w("🔐 Override", "Un token de la ráfaga no salió ($result); se sueltan las que faltan")
                    soltar(grupo)
                    break
                }
            }
        } catch (e: CancellationException) {
            // El teclado salió de la pantalla a media repartición: nadie se queda esperando.
            soltar(grupo)
            throw e
        }
        if (autorizacion == null) {
            // Ninguna petición del grupo seguía en pie (no se mandó el PIN, Codex r1 #4) o el teclado se cerró mientras
            // salía el primer token: el teclado se cierra.
            soltar(grupo)
            return OverrideResult.Failed(YA_NO_ESPERA)
        }
        return autorizacion
    }

    private enum class Entrega { SIGUE, ULTIMA, OMITIDA, DESCARTADA }

    /** Bajo [candado]: desde aquí nadie más se une a [grupo]. */
    private fun cerrarAUniones(grupo: Grupo) {
        grupo.cerrado = true
        grupo.rafaga?.let { r -> if (gruposAbiertos[r to grupo.permission] === grupo) gruposAbiertos.remove(r to grupo.permission) }
    }

    /**
     * A quién le toca el siguiente token: la primera de la fila cuya llamada siga en pie. A las canceladas se les suelta
     * sin pedir nada. Si ya no queda nadie y el PIN ya autorizó, el grupo se cierra aquí mismo (bajo el candado): una
     * consulta que llegue después abre su propio teclado en vez de quedarse sin respuesta.
     */
    private fun siguienteTurno(grupo: Grupo): Espera? {
        val muertas = mutableListOf<Espera>()
        val turno = synchronized(candado) {
            var elegida: Espera? = null
            while (!grupo.cerrado && grupo.pendientes.isNotEmpty()) {
                val primera = grupo.pendientes.first()
                if (primera.vivo()) {
                    elegida = primera
                    break
                }
                grupo.pendientes.removeFirst()
                muertas += primera
            }
            if (elegida == null && grupo.autorizado && !grupo.cerrado) {
                cerrarAUniones(grupo)
                grupo.exito = true
                retirar(grupo)
            }
            elegida
        }
        muertas.forEach { it.token.complete(null) }
        return turno
    }

    /**
     * El usuario cerró el teclado: la acción falla como fallaba antes. D24: con ella, toda su ráfaga.
     *
     * @param promptId id del teclado que se estaba viendo. Si ya no es el
     *   vigente, la cancelación llega TARDE —ese teclado se cerró solo al
     *   autorizar o al vencer, y otra acción de la fila ya instaló el suyo— y se
     *   ignora en vez de tumbar la espera de la siguiente. Espejo de iOS.
     */
    open fun cancel(promptId: Long? = null) {
        val awaiting = pending ?: return
        if (promptId != null && awaiting.id != promptId) return
        soltar(awaiting.grupo)
    }
}
