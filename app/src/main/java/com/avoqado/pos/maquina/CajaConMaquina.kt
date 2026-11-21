package com.avoqado.pos.maquina

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** La venta que está en «Cobrar», como la ve la máquina. */
data class VentaEnPantalla(
    val venueId: String,
    /** La identidad del carrito ([com.avoqado.pos.pos.presentation.cart.CartState.identidadDeVenta]). */
    val llaveDeVenta: String,
    val conProductos: Boolean,
    /** Sin orden en el SERVIDOR, sin mesa, sin dividir, del cobro de siempre: la máquina sólo cobra carritos nuevos (R4-1). */
    val ventaNueva: Boolean,
)

/** Un `PAGADO` de la máquina (o el que confirmó un gerente) que la caja convierte en venta cobrada. */
data class CierreEnPantalla(
    val referenceCC: String,
    val dueCents: Int,
    val creditCents: Int,
    val changeDispensedCents: Int,
    /** Sin cotización con red el precio se confirma al subir: el ticket dice «Precio por confirmar» (R5#9). */
    val precioPorConfirmar: Boolean,
    /** Codex código r1 #14: los renglones congelados al empezar (los de la cotización): el ticket sale de éstos, no del carrito. */
    val renglones: List<com.avoqado.pos.printing.data.model.ReceiptItem>? = null,
) {
    /** Lo que la máquina le quedó a deber de cambio (sin billetes ni monedas para darlo). */
    val aDeberCents: Int get() = (creditCents - dueCents - changeDispensedCents).coerceAtLeast(0)
}

/**
 * Codex código r1 #7: un cobro «Esperando a la máquina», para resolverlo con gerente desde Cobrar o Ajustes. Codex código r2 H7: con la
 * evidencia que se enseña ([evidencia]) y SU [version]: el gerente firma esa versión, nunca la que haya en disco al tocar.
 */
data class CobroEnEspera(
    val referenceCC: String,
    val dueCents: Int,
    val creadoEn: Long,
    val version: Int = 1,
    val evidencia: String? = null,
    /** Codex código r3 #9: lo más que entró (el campo del SERVIDOR, o lo de esta PC), en número: no depende de la cola del texto. */
    val creditCents: Int? = null,
    /**
     * Codex código r3 #8: un folio POR_TERMINAR: la máquina ya lo cerró ([outcome], lo que entró y el cambio) y relevaron su PC antes de
     * terminar la venta. El gerente lo TERMINA con su versión, sin teclear nada.
     */
    val porTerminar: Boolean = false,
    val outcome: String? = null,
    val changeDispensedCents: Int? = null,
)

/** La duda de esta PC tal como la tiene en disco (lo que subió, lo que entró y la versión que le contestó el SERVIDOR). */
fun CobroEnMaquinaEntity.comoDuda() =
    CobroEnEspera(referenceCC, dueCents, creadoEn, versionDeLaDuda ?: 1, textoDeLoQueVio(this), creditCents = creditCents)

/** Codex código r2 H6: una página de la bandeja del gerente: los cobros, cuántos hay en total y la marca de la siguiente. */
data class PaginaDeDudas(val dudas: List<CobroEnEspera>, val total: Int, val siguiente: String?)

/** Lee `{ data, total, nextCursor }` de `GET …/cash-machine/doubts` (ESPERANDO o POR_TERMINAR); null si no es una página. */
fun leerPaginaDeDudas(r: RespuestaDelServidor): PaginaDeDudas? {
    if (r.codigo != 200) return null
    val j = runCatching { org.json.JSONObject(r.cuerpo) }.getOrNull() ?: return null
    val data = j.optJSONArray("data") ?: return null
    fun org.json.JSONObject.centavos(campo: String) = (opt(campo) as? Number)?.toInt()
    fun org.json.JSONObject.instante(campo: String) = runCatching { java.time.Instant.parse(optString(campo)).toEpochMilli() }.getOrNull()
    val dudas = (0 until data.length()).mapNotNull { i ->
        val d = data.optJSONObject(i) ?: return@mapNotNull null
        val ref = d.optString("referenceCC").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        CobroEnEspera(
            referenceCC = ref,
            dueCents = d.centavos("dueCents") ?: 0,
            creadoEn = d.instante("startedAt") ?: d.instante("createdAt") ?: 0L,
            version = d.centavos("version") ?: 1,
            evidencia = d.opt("lastSeen")?.takeIf { it != org.json.JSONObject.NULL }?.toString(),
            creditCents = d.centavos("creditCents"),
            porTerminar = d.optString("kind") == MaquinaDeEfectivo.KIND_POR_TERMINAR,
            outcome = d.optString("outcome").takeIf { !d.isNull("outcome") && it.isNotBlank() },
            changeDispensedCents = d.centavos("changeDispensedCents"),
        )
    }
    val siguiente = j.optString("nextCursor").takeIf { !j.isNull("nextCursor") && it.isNotBlank() }
    return PaginaDeDudas(dudas, j.optInt("total", dudas.size), siguiente)
}

/** Lo que pinta «Cobrar» de la máquina. null en la caja = no hay máquina en esta PC: todo como hoy. */
data class VistaDeLaMaquina(
    val fila: FilaDeLaMaquina,
    /** El cobro de ESTA venta en la máquina, mientras se ve su pantalla; null = la selección de método. */
    val cobro: EstadoDelCobro?,
    /** Esta venta tiene un intento abierto, en duda o terminado: tampoco se cobra con tarjeta (R2#1). */
    val ventaEnDuda: Boolean,
    /** Por qué no se mandó a la máquina (centavos, descuento, premio…). */
    val aviso: String?,
    val avisoDeSubida: String?,
    val puedeResolver: Boolean,
    val resolviendo: Boolean,
    val avisoDeResolver: String?,
    /** El gerente decidió CANCELADO o GUARDADO: la pantalla lo dice y deja salir. */
    val resuelto: Desenlace?,
    /** Codex código r1 #7: las OTRAS dudas de esta PC en la sucursal (la de esta venta ya se ve en su pantalla). */
    val enEspera: List<CobroEnEspera> = emptyList(),
    /** Codex código r2 H7: la duda de ESTA venta como se la enseña al gerente (su evidencia y su versión), al abrir «Resolver». */
    val duda: CobroEnEspera? = null,
    /** Codex código r1 #11: el gerente declaró PAGADO y el SERVIDOR lo guardó como incidencia: no hay venta. */
    val incidencia: String? = null,
    /** Codex código r1 #12: ventas pagadas de la sucursal cuyo ticket no salió. */
    val ticketsSinImprimir: Int = 0,
    /** Cuándo empezó el cobro de esta venta (Codex código r1 #15: la hora del comprobante se lee desde ese día). */
    val inicioDelCobro: Long? = null,
)

/**
 * La máquina vista desde UN cobro de la caja (C5): la fila de la selección, la pantalla del cobro de ESTA venta y lo que un
 * `PAGADO` le pide a la caja ([alPagar], una sola vez por folio). Vive con el ViewModel del cobro; la máquina es de la app.
 */
class CajaConMaquina(
    val maquina: MaquinaDeEfectivo,
    private val scope: CoroutineScope,
    private val alPagar: (CierreEnPantalla) -> Unit,
) {
    private data class Local(
        /**
         * Codex código r2 H5: cada «Cobrar» que se abre es una apertura nueva. Lo que llega tarde (la cotización, el `paycash`, la
         * resolución del gerente) sólo se aplica si la pantalla sigue en SU apertura; si no, se queda en su fila (y en las listas de
         * dudas y de tickets), nunca en la venta que se ve ahora.
         */
        val apertura: Long = 0,
        val venta: VentaEnPantalla? = null,
        /** Codex código r2 H1: la venta de este cobro aunque la máquina NO esté activa (apagada, desemparejada u otra sucursal). */
        val llave: String? = null,
        /** Codex código r2 H1: sin la máquina activa, esta venta tiene en disco un intento abierto, en duda o pagado. */
        val bloqueadaSinMaquina: Boolean = false,
        val miFolio: String? = null,
        val miCotizada: Boolean = false,
        val ventaEnDuda: Boolean = false,
        /** Codex código r1 #1: tocó «Efectivo en la máquina» y todavía cotiza: la venta ya no sale con tarjeta. */
        val apartada: Boolean = false,
        val aviso: String? = null,
        val resolviendo: Boolean = false,
        val avisoDeResolver: String? = null,
        val resuelto: Desenlace? = null,
        /** Codex código r1 #7: la duda de esta venta leída del disco (tras reiniciar no hay cobro vivo que la muestre). */
        val guardada: EstadoDelCobro.Esperando? = null,
        val incidencia: String? = null,
        val inicio: Long? = null,
        /** Codex código r1 #1: el toque de «Efectivo en la máquina» de ESTA apertura sigue en camino (cotizando o mandando). */
        val enVuelo: Boolean = false,
        /** Codex código r2 H7: lo que el gerente tiene en pantalla al resolver la duda de esta venta. */
        val duda: CobroEnEspera? = null,
        /** Codex código r3 #5: la ficha con que ESTA apertura apartó su venta en la máquina; con ella se suelta al salir. */
        val ficha: Any? = null,
    )

    private val local = MutableStateFlow(Local())
    private var aperturas = 0L

    /**
     * Codex código r3 #5: EL candado de las aperturas. Cerrar una apertura (Esc, salir, abrir otra venta) y el `paycash` que la revisa
     * ([puertaDe]) van bajo él: o sale el cobro antes de cerrarla, o no sale.
     */
    private val candado = Any()

    /** La puerta de la apertura [mia]: deja salir el `paycash` sólo si la pantalla sigue en ella, en el mismo paso. */
    private fun puertaDe(mia: Long) = PuertaDelCobro { enviar -> synchronized(candado) { if (local.value.apertura == mia) enviar() else null } }

    /**
     * Pasa a una apertura nueva (bajo [candado]): lo de la anterior ya no se aplica a la pantalla, su apartado se suelta y su folio deja
     * de estar a la vista (su ticket y su comanda salen de la fila).
     */
    private fun nuevaApertura(siguiente: (Long) -> Local) = synchronized(candado) {
        val antes = local.value
        local.value = siguiente(++aperturas)
        antes.ficha?.let { f -> antes.llave?.let { maquina.soltar(it, f) } }
        antes.miFolio?.let { maquina.aLaVista(it, false) }
    }

    /** Codex código r3 #5: la caja salió de este cobro (Esc, cerrar, salir del flujo): nada de lo que regrese enciende la máquina. */
    fun cerrar() = nuevaApertura { Local(apertura = it) }

    /** ¿La máquina cobra en ESTE cobro? Síncrono (la [vista] puede ir un cuadro atrás): lo usan las guardas del punto común. */
    val activa: Boolean get() = local.value.venta != null
    private val pagados = mutableSetOf<String>()
    private val comprobantes = mutableSetOf<String>()

    /** Aplica [cambio] sólo si la pantalla sigue en la apertura [mia] (Codex código r2 H5). */
    private fun siSigue(mia: Long, cambio: (Local) -> Local) {
        local.update { if (it.apertura == mia) cambio(it) else it }
    }

    val vista: StateFlow<VistaDeLaMaquina?> =
        combine(
            local, maquina.ahora, maquina.cobro.estado, maquina.avisoDeSubida,
            combine(maquina.enEspera(), maquina.ticketsSinImprimir()) { d, t -> d to t },
        ) { l, ahora, estado, avisoDeSubida, (dudas, sinImprimir) ->
            val v = l.venta ?: return@combine null
            // El cobro vivo de esta venta; si no lo hay (se reinició la app), su duda guardada (Codex código r1 #7).
            val mio = estado.takeIf { folioDe(it) != null && folioDe(it) == l.miFolio }
                ?: l.guardada?.takeIf { it.referenceCC == l.miFolio }
            VistaDeLaMaquina(
                fila = filaDeLaMaquina(ahora, v.conProductos, v.ventaNueva, l.ventaEnDuda, cobroVivo = vivo(estado) || l.apartada),
                // Lo que decidió el gerente (o su incidencia) se ve en lugar de la duda.
                cobro = mio.takeIf { l.resuelto == null && l.incidencia == null },
                ventaEnDuda = l.ventaEnDuda || l.apartada || mio != null,
                aviso = l.aviso,
                avisoDeSubida = avisoDeSubida,
                puedeResolver = maquina.puedeResolver(),
                resolviendo = l.resolviendo,
                avisoDeResolver = l.avisoDeResolver,
                resuelto = l.resuelto,
                enEspera = dudas.filter { it.referenceCC != l.miFolio }.map { it.comoDuda() },
                duda = l.duda?.takeIf { it.referenceCC == l.miFolio },
                incidencia = l.incidencia,
                ticketsSinImprimir = sinImprimir,
                inicioDelCobro = l.inicio,
            )
        }.stateIn(scope, SharingStarted.Eagerly, null)

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            maquina.cobro.estado.collect { estado ->
                val l = local.value
                if (estado !is EstadoDelCobro.Cerrado || estado.referenceCC != l.miFolio) return@collect
                // C7 (R3#11): «Te debemos» de un CANCELADO que no devolvió todo, en papel con su folio (por el mismo camino del papel,
                // Codex código r3 #11). GUARDADO: nada.
                if (estado.desenlace == Desenlace.CANCELADO && estado.creditCents > estado.changeDispensedCents &&
                    comprobantes.add(estado.referenceCC)
                ) {
                    scope.launch { maquina.imprimirPapelDelFolio(estado.referenceCC) }
                }
                if (estado.desenlace == Desenlace.PAGADO && estado.referenceCC !in pagados) {
                    val renglones = renglonesDelTicket(maquina.fila(estado.referenceCC)?.renglonesJson)
                    // Codex código r2 H5: leer la fila suspende; si mientras tanto la pantalla cambió de venta, el PAGADO se queda en su fila
                    // y su papel sale de ella (Codex código r3 #11: por el mismo reclamo, nunca dos originales).
                    if (local.value.apertura != l.apertura || local.value.miFolio != estado.referenceCC) {
                        scope.launch { maquina.imprimirPapelDelFolio(estado.referenceCC) }
                        return@collect
                    }
                    if (!pagados.add(estado.referenceCC)) return@collect
                    alPagar(
                        CierreEnPantalla(estado.referenceCC, estado.dueCents, estado.creditCents, estado.changeDispensedCents,
                            precioPorConfirmar = !l.miCotizada, renglones = renglones),
                    )
                }
            }
        }
    }

    /**
     * Al abrir cada cobro. Sin la máquina activa en esta PC ([venta] null o [MaquinaDeEfectivo.activa] false) la vista es null y
     * la caja queda como hoy. Con ella: el enlace al día y si esta venta ya tiene un intento (de antes, o de otra pantalla).
     */
    fun abrir(venta: VentaEnPantalla?) {
        val activa = venta != null && maquina.activa()
        var mia = 0L
        nuevaApertura { n -> mia = n; Local(apertura = n, venta = venta.takeIf { activa }, llave = venta?.llaveDeVenta) }
        if (!activa) {
            // Codex código r2 H1: apagar o desemparejar la máquina no suelta una venta con su efectivo en duda.
            if (venta != null && maquina.tuvoMaquina()) scope.launch {
                val enDuda = maquina.ventaBloqueada(venta.llaveDeVenta)
                siSigue(mia) { it.copy(bloqueadaSinMaquina = enDuda) }
            }
            return
        }
        maquina.arrancar()
        scope.launch {
            val enDuda = maquina.ventaBloqueada(venta!!.llaveDeVenta)
            // Codex código r1 #7: si esta venta quedó «Esperando» (o abierta por un proceso que murió), su pantalla vuelve con su folio.
            val intento = maquina.intentoDeLaVenta(venta.llaveDeVenta)
            siSigue(mia) { l ->
                if (intento == null || l.miFolio != null) l.copy(ventaEnDuda = enDuda)
                else l.copy(ventaEnDuda = true, miFolio = intento.referenceCC, miCotizada = intento.quoteId != null,
                    guardada = EstadoDelCobro.Esperando(intento.referenceCC, intento.dueCents), inicio = intento.creadoEn)
            }
            if (local.value.apertura == mia) local.value.miFolio?.let { maquina.aLaVista(it, true) }
        }
    }

    /** R2#1: lee el disco, no la pantalla: la tarjeta no sale para una venta con un intento en la máquina. */
    suspend fun bloqueaLaTarjeta(): Boolean {
        val v = local.value.venta ?: return false
        return local.value.ventaEnDuda || maquina.ventaBloqueada(v.llaveDeVenta)
    }

    /**
     * Codex código r2 H1: sin la máquina activa (apagada, desemparejada, la sesión en otra sucursal), ¿esta PC tuvo máquina? Entonces
     * la venta se revisa en disco antes de cobrarla por cualquier medio. Una tableta que nunca la tuvo sigue como hoy, sin leer nada.
     */
    val revisarSinMaquina: Boolean get() = !activa && local.value.llave != null && maquina.tuvoMaquina()

    /** Codex código r2 H1: lee el disco (nunca la pantalla ni la casilla): la venta tiene un intento abierto, en duda o pagado. */
    suspend fun ventaBloqueadaEnDisco(): Boolean = local.value.llave?.let { maquina.ventaBloqueada(it) } == true

    /** Codex código r2 H1: lo que «Cobrar» dice sin la máquina activa: la venta espera a la máquina (todos los métodos en gris). */
    val bloqueadaSinMaquina: StateFlow<Boolean> =
        local.map { it.bloqueadaSinMaquina }.stateIn(scope, SharingStarted.Eagerly, false)

    /** Codex código r1 #1: síncrono. La tarjeta lo vuelve a preguntar justo antes de salir (tras leer el disco). */
    fun apartadaAhora(): Boolean = local.value.venta?.let { local.value.apartada || maquina.apartada(it.llaveDeVenta) } == true

    /**
     * Codex código r1 #1: «Efectivo en la máquina» aparta la venta en el MISMO toque, antes de cualquier red (turno, cotización). false =
     * no hay venta, ya está apartada o ya tiene su cobro en pantalla.
     */
    fun apartar(): Boolean {
        synchronized(candado) {
            val l = local.value
            val v = l.venta ?: return false
            if (l.enVuelo || l.miFolio != null) return false
            val ficha = maquina.apartar(v.llaveDeVenta) ?: return false
            local.value = l.copy(aviso = null, apartada = true, enVuelo = true, ficha = ficha)
            return true
        }
    }

    /**
     * «Efectivo en la máquina» (tras [apartar]). Devuelve si salió el `paycash`. Codex código r2 H5: todo va atado a la apertura en que se
     * tocó: si mientras cotizaba se salió de este cobro, el `paycash` ya no sale, y nada de lo que regrese se escribe en otra venta.
     */
    suspend fun cobrar(v: VentaPorCobrar): Boolean {
        if (!local.value.enVuelo && !apartar()) return false
        val l = local.value
        val mia = l.apertura
        try {
            return when (val r = maquina.cobrar(v, puertaDe(mia), apartado = l.ficha)) {
                is InicioEnLaMaquina.Iniciado -> {
                    // A la vista ANTES de leer la fila: su PAGADO puede llegar mientras tanto (Codex código r3 #11).
                    maquina.aLaVista(r.referenceCC, true)
                    val fila = maquina.fila(r.referenceCC)
                    siSigue(mia) {
                        it.copy(miFolio = r.referenceCC, miCotizada = fila?.quoteId != null, ventaEnDuda = true, inicio = fila?.creadoEn)
                    }
                    if (local.value.apertura != mia) maquina.aLaVista(r.referenceCC, false)
                    local.value.apertura == mia
                }
                is InicioEnLaMaquina.NoSeCobra -> {
                    siSigue(mia) { it.copy(aviso = r.texto, ventaEnDuda = it.ventaEnDuda || r.texto == TEXTO_VENTA_CON_INTENTO) }
                    false
                }
            }
        } finally {
            siSigue(mia) { it.copy(apartada = false, enVuelo = false, ficha = null) }
        }
    }

    fun cancelarPorElCliente() = maquina.cobro.cancelarPorElCliente()

    /** Codex código r1 #12: «Imprimir» los tickets que no salieron (nunca solos). */
    fun imprimirTicketsPendientes() {
        scope.launch { maquina.imprimirTicketsPendientes() }
    }

    fun sigoAqui() = maquina.cobro.sigoAqui()

    /**
     * Tras CANCELADO o GUARDADO (o lo que decidió el gerente), de vuelta a la selección: la venta puede pagarse con tarjeta…
     * salvo que siga bloqueada en disco (un gerente la resolvió: su desenlace lo sabe el SERVIDOR).
     */
    fun listo() {
        val l = local.value
        local.value = l.copy(miFolio = null, resuelto = null, avisoDeResolver = null, guardada = null, incidencia = null, inicio = null)
        l.miFolio?.let { maquina.aLaVista(it, false) }
        val v = l.venta ?: return
        scope.launch {
            val enDuda = maquina.ventaBloqueada(v.llaveDeVenta)
            siSigue(l.apertura) { it.copy(ventaEnDuda = enDuda) }
        }
    }

    /**
     * Codex código r2 H7: al tocar «Resolver con gerente» se lee del disco la evidencia de la duda de esta venta y su versión; eso es
     * lo que se enseña y lo que se firma (sólo un 409 de versión la cambia, con la del SERVIDOR).
     */
    suspend fun abrirResolucion() {
        val l = local.value
        val folio = l.miFolio ?: return
        val duda = maquina.fila(folio)?.comoDuda() ?: return
        siSigue(l.apertura) { if (it.miFolio == folio) it.copy(duda = duda, avisoDeResolver = null) else it }
    }

    /** El gerente resuelve la duda de ESTA venta (R4-4). Un `PAGADO` cobra la venta en la caja, como el de la máquina. */
    suspend fun resolver(r: ResolucionDelGerente) {
        val l = local.value
        val folio = l.miFolio ?: return
        if (l.resolviendo) return
        local.value = l.copy(resolviendo = true, avisoDeResolver = null)
        val resultado = maquina.resolver(folio, r)
        // Codex código r2 H5: si la pantalla ya es otra, lo decidido se queda en su fila (su ticket, en «tickets sin imprimir»).
        if (local.value.apertura != l.apertura || local.value.miFolio != folio) return
        local.value = local.value.copy(resolviendo = false)
        when (resultado) {
            is ResultadoDeResolver.NoSe -> local.value = local.value.copy(avisoDeResolver = resultado.texto)
            // Codex código r2 H7: se enseña la evidencia nueva y su versión; el gerente vuelve a confirmar.
            is ResultadoDeResolver.EvidenciaNueva -> local.value = local.value.copy(avisoDeResolver = resultado.texto, duda = resultado.duda)
            is ResultadoDeResolver.Resuelto -> if (resultado.desenlace == Desenlace.PAGADO) {
                val fila = maquina.fila(folio)
                if (local.value.apertura == l.apertura && local.value.miFolio == folio && pagados.add(folio)) {
                    alPagar(
                        CierreEnPantalla(folio, fila?.dueCents ?: 0, resultado.creditCents, resultado.changeDispensedCents,
                            precioPorConfirmar = fila?.quoteId == null, renglones = renglonesDelTicket(fila?.renglonesJson)),
                    )
                }
            } else {
                local.value = local.value.copy(resuelto = resultado.desenlace)
            }
            // Codex código r1 #11: sin venta no hay ticket ni comanda; sólo el comprobante con folio si se le debe al cliente.
            is ResultadoDeResolver.Incidencia -> {
                local.value = local.value.copy(incidencia = resultado.texto)
                if (resultado.debeCents > 0 && comprobantes.add(folio)) scope.launch { maquina.imprimirPapelDelFolio(folio) }
            }
        }
    }

    /**
     * Codex código r1 #7: el gerente resuelve OTRA duda de esta PC desde Cobrar (no la de esta venta). Nunca cobra la venta en pantalla:
     * si fue un `PAGADO`, su ticket sale por «Tickets sin imprimir».
     */
    suspend fun resolverOtra(referenceCC: String, r: ResolucionDelGerente): ResultadoDeResolver =
        if (referenceCC == local.value.miFolio) ResultadoDeResolver.NoSe(TEXTO_ES_LA_DE_ESTA_VENTA) else maquina.resolver(referenceCC, r)

    private fun folioDe(e: EstadoDelCobro): String? = when (e) {
        EstadoDelCobro.Libre -> null
        is EstadoDelCobro.Iniciando -> e.referenceCC
        is EstadoDelCobro.Cobrando -> e.referenceCC
        is EstadoDelCobro.SiguesAhi -> e.referenceCC
        is EstadoDelCobro.Cancelando -> e.referenceCC
        is EstadoDelCobro.Reconectando -> e.referenceCC
        is EstadoDelCobro.Cerrado -> e.referenceCC
        is EstadoDelCobro.Esperando -> e.referenceCC
    }

    private fun vivo(e: EstadoDelCobro) = when (e) {
        EstadoDelCobro.Libre, is EstadoDelCobro.Cerrado, is EstadoDelCobro.Esperando -> false
        else -> true
    }
}
