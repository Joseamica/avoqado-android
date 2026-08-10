package com.avoqado.pos.customerdisplay

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Monta la pantalla del cliente en la pantalla SECUNDARIA cuando existe.
 *
 * API estándar de Android (DisplayManager + Presentation), NO el SDK de Sunmi:
 * así el mismo código sirve para Sunmi T3/D3, Elo, y cualquier POS de doble
 * pantalla — y si el equipo no tiene segunda pantalla, simplemente no pasa nada.
 *
 * Se conecta/desconecta en caliente: si desenchufan el monitor y lo vuelven a
 * enchufar a media venta, la pantalla se rehace sola con el estado vigente.
 */
/** Datos mínimos de una pantalla candidata; separado de [Display] para poder testear la decisión. */
internal data class CandidateDisplay(val displayId: Int, val ownerPackage: String?)

/**
 * Decisión PURA de cuál pantalla usar (sin Android): física primero, luego
 * virtual que no sea de captura/remoto, ante la duda ninguna. Top-level e
 * `internal` para tener test unitario del caso AnyDesk sin hardware.
 */
internal fun chooseCustomerDisplayId(
    candidates: List<CandidateDisplay>,
    remoteCaptureHints: List<String>,
): Int? {
    if (candidates.isEmpty()) return null
    // Física = sin dueño. Si hay, gana siempre (es la pantalla real del cliente).
    val physical = candidates.filter { it.ownerPackage == null }
    if (physical.isNotEmpty()) return physical.minByOrNull { it.displayId }?.displayId
    // Todas virtuales (T3 Pro): descartar las de captura/remoto por dueño.
    return candidates
        .filter { d ->
            val owner = d.ownerPackage?.lowercase().orEmpty()
            remoteCaptureHints.none { owner.contains(it) }
        }
        .minByOrNull { it.displayId }?.displayId
}

/**
 * ¿Este `detach` viene del anfitrión VIGENTE, o llega tarde de uno que ya fue
 * reemplazado?
 *
 * 🔴 Lo que no es obvio: cuando Android RECREA una Activity —exactamente lo que
 * provoca [CashierDisplayGuard] al relanzar la caja en la otra pantalla— el
 * `onStart()` de la instancia NUEVA corre ANTES del `onStop()` de la VIEJA. O
 * sea que el orden real es `attach(nueva)` → `detach(vieja)`. Un `detach` que
 * desmonte a ciegas mataría entonces la ventana del cliente que la instancia
 * nueva acaba de montar, y dejaría al manager sin anfitrión aunque haya una
 * Activity viva. MEDIDO en un D3: tras relanzar la caja, la pantalla del cliente
 * desapareció y NO volvió sola —el cliente se quedó viendo el launcher— y es
 * intermitente, o sea que en un local aparece "a veces".
 *
 * Se compara por IDENTIDAD (`===`) y no por igualdad: lo que importa es si es LA
 * MISMA instancia de Activity, no si dos instancias distintas se parecen.
 *
 * Top-level e `internal` para poder testear la decisión sin Android — esta
 * carrera depende de la temporización y nadie la va a reproducir a mano dos
 * veces.
 */
internal fun shouldTearDownOnDetach(currentHost: Any?, caller: Any): Boolean =
    currentHost === caller

@Singleton
class CustomerDisplayManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val state: CustomerDisplayState,
    private val secureStorage: com.avoqado.pos.core.data.local.SecureStorage,
    // Se inyecta para FORZAR su construcción: es quien carga el ajuste guardado
    // dentro del state. Sin esto el ajuste solo se aplicaría si alguien abre
    // la pantalla de Ajustes.
    @Suppress("unused") private val prefs: CustomerDisplayPrefs,
    private val displayModePrefs: DisplayModePrefs,
) {
    private val tag = "🖥️CustomerDisplay"

    private val handler = Handler(Looper.getMainLooper())

    // Scope propio del manager (singleton, vive más allá de un solo ciclo de
    // attach/detach). Lo que entra/sale con attach()/detach() es el JOB que
    // colecta el interruptor, no este scope.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var invertedObserverJob: Job? = null

    private var displayManager: DisplayManager? = null
    private var presentation: CustomerDisplayPresentation? = null
    private var hostActivity: Activity? = null

    /**
     * Pantalla en la que el manager QUIERE una ventana de cliente ahora mismo;
     * null cuando no quiere ninguna.
     *
     * Es un DESEO, no un apretón de manos: vale lo que dice mientras sea cierto.
     * Se pone al decidir montar al cliente en esa pantalla
     * ([showCustomerActivity]) y se limpia SOLO cuando el manager deja de
     * quererla: la pantalla se fue o la caja pasó a segundo plano ([dismiss]),
     * el guard anti-bucle dijo que no, o se cambió de modo (rama final de
     * [refresh] → [finishCustomerActivity]). Que la Activity confirme NO lo
     * consume.
     *
     * 🔴 Por qué existe: `startActivity()` hacia otra pantalla es ASÍNCRONO —
     * hay una ventana entre ESE `startActivity()` y el `onCreate()` real de la
     * Activity donde el `instance` del companion todavía es null. Si en esa
     * ventana un `refresh()` decide cerrar (el caso que importa: el guard
     * anti-bucle, cuando la pantalla destino resulta ser la de la caja),
     * `CustomerDisplayActivity.finishIfShowing()` es un no-op porque no hay
     * instancia que cerrar — y el lanzamiento en vuelo ATERRIZA de todos
     * modos, tapando la caja. Este campo es lo que le permite a la propia
     * Activity, en `onStart()`, preguntar "¿me sigues queriendo aquí?" en vez
     * de asumirlo por haber llegado a existir, y cerrarse sola si la respuesta
     * es no. La decisión sigue siendo del manager: la Activity solo pregunta.
     *
     * 🔴 Y por qué DESEO y no apretón de manos de un solo uso: la Activity
     * consulta esto en CADA `onStart()`. Un token que se consumiera al
     * confirmar dejaría la respuesta en `null` para todo lo que venga después
     * — el ciclo `onStop → onRestart → onStart` por causa ajena
     * (bloqueo/desbloqueo, gestión de energía del fabricante, un overlay del
     * sistema) y la recreación por cambio de configuración (el manifest declara
     * `resizeableActivity` y a propósito NO declara `configChanges`) — y el
     * letrero del cliente se autocerraría sin que el manager lo haya decidido,
     * sin nadie que lo repusiera: ningún listener de display se dispara y
     * `inverted` no cambió. Mientras el deseo siga puesto, esos casos
     * sobreviven; y cuando el manager de verdad deja de querer la ventana, la
     * cierra ÉL ([finishCustomerActivity]).
     */
    private var desiredCustomerDisplayId: Int? = null

    /**
     * Hay un montaje de la pantalla del cliente RECIÉN lanzado al que todavía le
     * debemos devolverle el foco de teclado a la caja (ver
     * [onCustomerDisplayPresented]).
     *
     * Se arma SOLO al lanzar de verdad la Activity del cliente y se consume en
     * cuanto esa ventana confirma presencia: así el re-frente ocurre UNA VEZ POR
     * MONTAJE y no en cada [refresh] ni en cada `onStart` repetido de la ventana
     * del cliente (bloqueo/desbloqueo, overlays del sistema). Repetirlo sería el
     * ingrediente de un bucle, que es justo lo que no puede pasarle a la caja.
     */
    private var pendingCashierRefront = false

    /** Ver [bringCashierToFront]. Como campo para poder retirarlo del handler. */
    private val refrontCashierRunnable = Runnable { bringCashierToFront() }

    /** true cuando hay una segunda pantalla activa (para UI de diagnóstico). */
    var isActive: Boolean = false
        private set

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refresh()
        override fun onDisplayRemoved(displayId: Int) = refresh()
        override fun onDisplayChanged(displayId: Int) = refresh()
    }

    /** Llamar desde MainActivity.onStart. */
    fun attach(activity: Activity) {
        // La marca en reposo es del NEGOCIO (logo si hay, si no el nombre).
        state.setVenueBranding(secureStorage.venueDisplayName, secureStorage.venueLogo)
        // 🔴 En una RECREACIÓN de la caja (lo que provoca CashierDisplayGuard al
        // moverla de pantalla) este attach() de la instancia NUEVA corre ANTES
        // del onStop() de la VIEJA — ver shouldTearDownOnDetach. Como el detach()
        // de la vieja va a llegar tarde y se ignorará por no ser ya la
        // anfitriona, es AQUÍ donde hay que soltar los enganches del anfitrión
        // anterior: si no, cada recreación dejaría una colecta huérfana y un
        // listener de displays de más. Va ANTES de reclamar el anfitrión y de
        // registrar lo nuevo, para no soltar de rebote lo que acabamos de atar.
        //
        // Lo que NO se toca aquí es la ventana del cliente: en una recreación el
        // letrero ya está montado y correcto, y apagarlo para volverlo a montar
        // es justo el parpadeo (o la desaparición) que estamos evitando.
        releaseHostBindings()
        hostActivity = activity
        val dm = activity.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        displayManager = dm
        dm.registerDisplayListener(displayListener, handler)
        refresh()
        // Reacciona al interruptor de Ajustes en caliente: sin esto, tocarlo
        // no hacía nada hasta desenchufar un monitor o mandar la app a
        // segundo plano. refresh() ya es idempotente, así que recibir aquí el
        // valor inicial del StateFlow no produce un doble montaje visible.
        invertedObserverJob = scope.launch {
            displayModePrefs.inverted.collect { refresh() }
        }
    }

    /**
     * Llamar desde MainActivity.onStop — sin esto la ventana se filtra.
     *
     * 🔴 Recibe QUIÉN llama porque el desmontaje tiene que ser consciente de la
     * instancia: en una recreación de la caja, el `onStart()` de la nueva corre
     * ANTES del `onStop()` de la vieja, así que este método lo invoca una
     * Activity que ya dejó de ser la anfitriona. Ese detach tardío NO puede
     * tocar nada del anfitrión vigente —ni la ventana del cliente que la nueva
     * acaba de montar, ni el listener, ni la colecta del interruptor—, o el
     * cliente se queda viendo el launcher el resto del turno. Ver
     * [shouldTearDownOnDetach].
     */
    fun detach(activity: Activity) {
        if (!shouldTearDownOnDetach(hostActivity, activity)) {
            Log.d(tag, "detach() de una instancia que ya no es la anfitriona: no se desmonta nada")
            return
        }
        handler.removeCallbacks(refrontCashierRunnable)
        releaseHostBindings()
        dismiss()
        hostActivity = null
    }

    /**
     * Suelta lo que está atado al anfitrión ACTUAL (la colecta del interruptor y
     * el listener de displays), sin tocar lo que se le muestra al cliente.
     *
     * Se cancela el JOB, no el scope: el scope es del manager (singleton) y vive
     * más allá de un solo ciclo de attach/detach; sin cancelar aquí, cada
     * onStart/onStop de la caja dejaría una colecta huérfana corriendo — una
     * corrutina filtrada por ciclo.
     */
    private fun releaseHostBindings() {
        invertedObserverJob?.cancel()
        invertedObserverJob = null
        displayManager?.unregisterDisplayListener(displayListener)
        displayManager = null
    }

    /**
     * Lo consulta [CustomerDisplayActivity.onStart] en cada arranque de
     * visibilidad: ¿el manager SIGUE queriendo una ventana de cliente en esta
     * pantalla? La decisión es del manager, no de la Activity — ella solo
     * pregunta, nunca adivina, y se cierra sola si la respuesta es no.
     */
    internal fun wantsCustomerDisplayOn(displayId: Int): Boolean =
        desiredCustomerDisplayId == displayId

    private fun refresh() {
        val activity = hostActivity ?: return
        // PRESENTATION = pantallas pensadas para mostrar contenido a terceros;
        // es justo la categoría en la que caen los displays de cliente.
        val displays = displayManager
            ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            ?.toList()
            .orEmpty()

        val roles = resolveDisplayRoles(
            defaultDisplayId = Display.DEFAULT_DISPLAY,
            candidates = displays.map { CandidateDisplay(it.displayId, displayOwnerPackage(it)) },
            remoteCaptureHints = REMOTE_CAPTURE_HINTS,
            inverted = displayModePrefs.inverted.value,
        )
        state.setInvertible(roles.invertible)

        val customerId = roles.customerDisplayId
        if (customerId == null) {
            if (presentation != null || CustomerDisplayActivity.isShowingOn(Display.DEFAULT_DISPLAY)) {
                Log.i(tag, "Segunda pantalla desconectada")
            }
            dismiss()
            return
        }

        if (customerId == Display.DEFAULT_DISPLAY) {
            // 🔴 GUARD ANTI-BUCLE: si la caja (MainActivity) TODAVÍA vive en la
            // pantalla DEFAULT —arranque en frío antes de que el guard de Task 4
            // la mueva, `setLaunchDisplayId` ignorado por el fabricante, o ese
            // guard ya se rindió (invertUnsupported)— montar aquí el letrero del
            // cliente TAPARÍA la caja. Eso dispara MainActivity.onStop →
            // detach() → dismiss() → finishIfShowing() → (la caja reaparece)
            // onStart → attach() → refresh() → vuelve a montar el letrero: un
            // bucle infinito de parpadeo con la caja inservible. La regla del
            // dominio es "degradar, nunca bloquear": la pantalla del cliente es
            // decoración, cobrar es el negocio — se queda sin letrero, nunca sin
            // caja usable.
            if (activity.currentDisplayId() == customerId) {
                Log.i(
                    tag,
                    "Modo invertido pero la caja sigue en la pantalla principal: no se monta el letrero del cliente para no taparla",
                )
                dismiss()
                return
            }
            // Modo invertido: el cliente va en la pantalla principal, y ahí
            // TYPE_PRESENTATION está prohibido → Activity.
            dismissPresentation()
            showCustomerActivity(activity, customerId)
            return
        }

        finishCustomerActivity()
        val target = displays.firstOrNull { it.displayId == customerId } ?: return
        showPresentation(activity, target)
    }

    /** Modo invertido: el cliente en una Activity sobre la pantalla principal. */
    private fun showCustomerActivity(activity: Activity, displayId: Int) {
        // El deseo se deja puesto ANTES de todo lo demás —incluido el
        // early-return de abajo—: llegar aquí YA significa que el manager quiere
        // una ventana de cliente en esta pantalla, la haya o no todavía. Antes
        // de `startActivity()` porque es asíncrono: si otro refresh() cambia de
        // opinión mientras el lanzamiento va en vuelo, la Activity leerá este
        // valor (ya limpiado) en su onStart() y se cerrará sola. Y también en el
        // camino "ya está montada", porque este es el valor que esa instancia va
        // a releer en CADA onStart() mientras siga viva.
        desiredCustomerDisplayId = displayId
        if (CustomerDisplayActivity.isShowingOn(displayId)) return
        runCatching {
            val opts = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
            activity.startActivity(
                Intent(activity, CustomerDisplayActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                opts.toBundle(),
            )
            isActive = true
            // Este montaje —y solo este— tiene derecho a un re-frente de la caja
            // cuando la ventana del cliente confirme presencia. Ver
            // onCustomerDisplayPresented().
            pendingCashierRefront = true
            // 🔴 NO `state.setPresenting(true)` aquí: que `startActivity` no haya
            // lanzado excepción solo dice que el sistema aceptó la intención, no
            // que la ventana llegó a aparecer. La señal fiable es el propio
            // ciclo de vida de CustomerDisplayActivity (ver su onStart/onStop).
            // La principal siempre es física y táctil: el cliente sí puede
            // elegir propina y calificación — solo en éxito, simétrico con
            // showPresentation.
            state.setTouchCapable(true)
            Log.i(tag, "Pantalla del cliente (Activity) montada en display $displayId")
        }.onFailure {
            // Nunca tumbar la caja por culpa de la pantalla del cliente.
            Log.e(tag, "No se pudo abrir la pantalla del cliente: ${it.message}")
            isActive = false
            state.setPresenting(false)
            // El intento se abortó: no hay —ni va a haber— ventana que responda
            // por este deseo, así que se limpia para que no valide más tarde una
            // instancia que nadie pidió. El siguiente refresh() lo vuelve a
            // poner si sigue haciendo falta.
            desiredCustomerDisplayId = null
            pendingCashierRefront = false
        }
    }

    /**
     * La ventana del cliente CONFIRMÓ que está en pantalla
     * ([CustomerDisplayActivity.onStart]). Es el momento de devolverle a la caja
     * el foco de teclado.
     *
     * 🔴 El problema, medido en un D3 físico: tras invertir, `mTopFocusedDisplayId`
     * se queda en la pantalla del CLIENTE —fue la última en activarse— y NINGUNA
     * ventana tiene foco de teclado hasta que el cajero toca su pantalla. Los
     * toques SÍ se entregan sin foco, así que el daño real es acotado: el primer
     * toque del turno no abre el teclado si cae justo en un campo de texto. Se
     * verificó con adb que volver a poner la caja al FRENTE sí devuelve el foco a
     * su pantalla.
     *
     * 🔴 Por qué [ActivityManager.moveTaskToFront] y no relanzar la Activity: un
     * `startActivity` hacia la caja la RECREA, y una recreación dispara justo la
     * carrera de [shouldTearDownOnDetach] (attach de la nueva → detach tardío de
     * la vieja) además de un nuevo `refresh()` que vuelve a montar al cliente →
     * que volvería a pedir el re-frente… un bucle de relanzamientos con la caja
     * inservible. `moveTaskToFront` solo reordena la tarea EXISTENTE en su
     * pantalla: no recrea nada, no dispara onStop/onStart y no puede realimentar
     * el ciclo. Necesita el permiso REORDER_TASKS (nivel normal: se concede al
     * instalar, sin diálogo).
     *
     * Se posterga un poco a propósito: cuando esto corre, la ventana del cliente
     * está en `onStart` pero todavía le falta resumir y que el servidor de
     * ventanas asiente. Pedir el frente en el mismo fotograma sería una carrera
     * contra el propio montaje que lo causó. Llegar tarde es inofensivo: las dos
     * pantallas ya están donde tienen que estar y el re-frente no mueve nada de
     * sitio.
     */
    internal fun onCustomerDisplayPresented() {
        if (!pendingCashierRefront) return
        pendingCashierRefront = false
        handler.removeCallbacks(refrontCashierRunnable)
        handler.postDelayed(refrontCashierRunnable, CASHIER_REFRONT_DELAY_MS)
    }

    /**
     * Vuelve a poner la tarea de la caja al frente de SU pantalla, para que el
     * servidor de ventanas le devuelva el foco de teclado. Ver
     * [onCustomerDisplayPresented].
     *
     * Todo es best-effort y nada de esto puede tumbar ni congelar la caja: si el
     * anfitrión ya murió, si el equipo no concede el permiso o si el fabricante
     * lo ignora, se registra y se sigue — el costo es el toque "despertador" que
     * ya existía, nunca una caja rota.
     */
    private fun bringCashierToFront() {
        val activity = hostActivity ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        // Solo en modo invertido: con la caja en la pantalla principal no hay
        // nada que corregir, y el camino normal (Presentation) no se toca.
        if (activity.currentDisplayId() == Display.DEFAULT_DISPLAY) return
        val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        runCatching {
            // NO_USER_ACTION: es un ajuste interno nuestro, no un cambio de app
            // pedido por una persona; sin esta bandera se le notificaría a lo que
            // esté en la otra pantalla que "el usuario se fue".
            am.moveTaskToFront(activity.taskId, ActivityManager.MOVE_TASK_NO_USER_ACTION)
            Log.i(tag, "Caja re-frenteada en display ${activity.currentDisplayId()} para recuperar el foco de teclado")
        }.onFailure {
            Log.w(tag, "No se pudo re-frentear la caja (el primer toque seguirá siendo un despertador): ${it.message}")
        }
    }

    /** Modo normal: el cliente en un Presentation sobre la secundaria. */
    private fun showPresentation(activity: Activity, target: Display) {
        if (presentation?.display?.displayId == target.displayId && presentation?.isShowing == true) return
        dismissPresentation()
        runCatching {
            CustomerDisplayPresentation(activity, target, state).also {
                it.show()
                presentation = it
                isActive = true
                state.setPresenting(true)
                // Detección automática por hardware: una pantalla FÍSICA (sin
                // dueño) sí entrega toques; una virtual de Sunmi (NP511 del T3
                // Pro) NO. De esto depende delegar propina/calificación.
                val touchCapable = displayOwnerPackage(target) == null
                state.setTouchCapable(touchCapable)
                Log.i(tag, "Pantalla del cliente montada en display ${target.displayId} (${target.name}), táctil=$touchCapable")
            }
        }.onFailure {
            Log.e(tag, "No se pudo montar la pantalla del cliente: ${it.message}")
            isActive = false
            state.setPresenting(false)
        }
    }

    /**
     * 🔴 Si había una Presentation viva y CONFIRMADA (isPresenting=true) y la
     * apagamos para pasar al camino Activity (modo invertido), ese `true` tiene
     * que apagarse CON ella. Si sobrevive, se combina con el
     * `touchCapable=true` optimista de [showCustomerActivity] y
     * `customerCapturesInput` se prende ANTES de que exista ventana real: un
     * lanzamiento aceptado que aterriza en otra pantalla, o que el modo kiosco
     * bloquea, mandaría propina/upsell a una pantalla que nadie ve, con el
     * cajero esperando un toque que nunca llega. El autocierre de
     * `CustomerDisplayActivity` NO puede corregir esto — nunca prendió lo que
     * está apagando, así que su guarda (`reportedPresence`) correctamente no
     * lo toca. `CustomerDisplayActivity.onStart()` es quien vuelve a prenderlo
     * cuando de verdad confirma presencia.
     */
    private fun dismissPresentation() {
        val hadPresentation = presentation != null
        runCatching { presentation?.dismiss() }
        presentation = null
        if (hadPresentation) state.setPresenting(false)
    }

    /**
     * El manager deja de querer la ventana del cliente: retira el deseo Y
     * cierra la Activity si está viva. Las dos cosas juntas, porque cada una
     * sola deja un agujero: un `finish()` a una instancia que TODAVÍA no existe
     * (ver [desiredCustomerDisplayId]) no cancela nada, y el lanzamiento en
     * vuelo aterrizaría igual; y retirar el deseo sin cerrar dejaría la ventana
     * en pantalla hasta su siguiente `onStart()`.
     */
    private fun finishCustomerActivity() {
        desiredCustomerDisplayId = null
        // Un montaje que el manager cancela ya no tiene por qué cobrar su
        // re-frente: si vuelve a hacer falta, el próximo lanzamiento lo arma otra
        // vez. Y si el temporizador ya iba en camino, se retira — cuando dispare
        // no habría ventana de cliente que lo justifique.
        pendingCashierRefront = false
        handler.removeCallbacks(refrontCashierRunnable)
        CustomerDisplayActivity.finishIfShowing()
    }

    private fun dismiss() {
        dismissPresentation()
        // Si la caja se va a segundo plano, el cliente NO puede quedarse viendo
        // un total congelado.
        finishCustomerActivity()
        isActive = false
        state.setPresenting(false)
    }

    private companion object {
        /**
         * Margen para que la ventana del cliente termine de aterrizar antes de
         * pedir el frente para la caja. No es un número mágico con garantía: es
         * "lo bastante después del montaje", y llegar tarde no rompe nada (ver
         * [onCustomerDisplayPresented]).
         */
        const val CASHIER_REFRONT_DELAY_MS = 500L
    }
}
