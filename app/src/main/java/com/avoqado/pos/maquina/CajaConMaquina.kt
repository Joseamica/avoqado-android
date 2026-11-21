package com.avoqado.pos.maquina

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
    /**
     * Codex código POS r7 #1 (regla pegajosa): esta venta mandó alguna vez un cobro con tarjeta a la terminal
     * ([com.avoqado.pos.pos.presentation.cart.CartState.intentoConTarjeta]). La máquina nunca la cobra: esa tarjeta pudo aprobarse tarde.
     */
    val intentoConTarjeta: Boolean = false,
    /** Decisión A: la trajo «Restaurar» (o volvió ligada a su copia guardada): la máquina no la cobra. */
    val restaurada: Boolean = false,
    /** Decisión B: va ligada a una reserva o a una clase: la máquina no la cobra, con o sin internet. */
    val conReserva: Boolean = false,
) {
    /** Codex código POS r8 #2 (F3): por qué esta venta nunca va a la máquina ([motivoSinMaquina], LA regla); null = puede. */
    val motivoSinMaquina: String? get() = com.avoqado.pos.maquina.motivoSinMaquina(ventaNueva, restaurada, intentoConTarjeta, conReserva)
}

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
    /** Recorte R2: un gerente lo resolvió en el panel como CANCELADO o GUARDADO; la pantalla lo dice y deja salir. */
    val resuelto: Desenlace?,
    /** Codex código r1 #11: el gerente declaró PAGADO y el SERVIDOR lo guardó como incidencia: no hay venta. */
    val incidencia: String? = null,
    /** Codex código r1 #12: ventas pagadas de la sucursal cuyo ticket no salió. */
    val ticketsSinImprimir: Int = 0,
)

/**
 * La máquina vista desde UN cobro de la caja (C5): la fila de la selección, la pantalla del cobro de ESTA venta y lo que un
 * `PAGADO` le pide a la caja ([alPagar], una sola vez por folio). Vive con el ViewModel del cobro; la máquina es de la app.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CajaConMaquina(
    val maquina: MaquinaDeEfectivo,
    private val scope: CoroutineScope,
    private val alPagar: (CierreEnPantalla) -> Unit,
) {
    private data class Local(
        /**
         * Codex código r2 H5: cada «Cobrar» que se abre es una apertura nueva. Lo que llega tarde (la cotización, el `paycash`, la
         * resolución del panel) sólo se aplica si la pantalla sigue en SU apertura; si no, se queda en su fila (y en la lista de tickets),
         * nunca en la venta que se ve ahora.
         */
        val apertura: Long = 0,
        val venta: VentaEnPantalla? = null,
        /** Codex código r2 H1: la venta de este cobro aunque la máquina NO esté activa (apagada, desemparejada u otra sucursal). */
        val llave: String? = null,
        /** Codex código r2 H1: sin la máquina activa, esta venta tiene en disco un intento abierto, en duda o pagado. */
        val bloqueadaSinMaquina: Boolean = false,
        val miFolio: String? = null,
        val ventaEnDuda: Boolean = false,
        /** Codex código r1 #1: tocó «Efectivo en la máquina» y todavía cotiza: la venta ya no sale con tarjeta. */
        val apartada: Boolean = false,
        val aviso: String? = null,
        val resuelto: Desenlace? = null,
        /** Codex código r1 #7: la duda de esta venta leída del disco (tras reiniciar no hay cobro vivo que la muestre). */
        val guardada: EstadoDelCobro.Esperando? = null,
        val incidencia: String? = null,
        /** Codex código r1 #1: el toque de «Efectivo en la máquina» de ESTA apertura sigue en camino (cotizando o mandando). */
        val enVuelo: Boolean = false,
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
     * de estar a la vista (su papel sale de la fila).
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
    private val pagados = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val comprobantes = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Aplica [cambio] sólo si la pantalla sigue en la apertura [mia] (Codex código r2 H5). */
    private fun siSigue(mia: Long, cambio: (Local) -> Local) {
        local.update { if (it.apertura == mia) cambio(it) else it }
    }

    val vista: StateFlow<VistaDeLaMaquina?> =
        combine(
            local, maquina.ahora, maquina.cobro.estado, maquina.avisoDeSubida,
            maquina.ticketsSinImprimir(),
        ) { l, ahora, estado, avisoDeSubida, sinImprimir ->
            val v = l.venta ?: return@combine null
            // El cobro vivo de esta venta; si no lo hay (se reinició la app), su duda guardada (Codex código r1 #7).
            val mio = estado.takeIf { folioDe(it) != null && folioDe(it) == l.miFolio }
                ?: l.guardada?.takeIf { it.referenceCC == l.miFolio }
            VistaDeLaMaquina(
                fila = filaDeLaMaquina(
                    ahora, v.conProductos, v.ventaNueva, l.ventaEnDuda, cobroVivo = vivo(estado) || l.apartada,
                    intentoConTarjeta = v.intentoConTarjeta, restaurada = v.restaurada, conReserva = v.conReserva,
                ),
                // Lo que decidió el gerente (o su incidencia) se ve en lugar de la duda.
                cobro = mio.takeIf { l.resuelto == null && l.incidencia == null },
                ventaEnDuda = l.ventaEnDuda || l.apartada || mio != null,
                aviso = l.aviso,
                avisoDeSubida = avisoDeSubida,
                resuelto = l.resuelto,
                incidencia = l.incidencia,
                ticketsSinImprimir = sinImprimir,
            )
        }.stateIn(scope, SharingStarted.Eagerly, null)

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            maquina.cobro.estado.collect { estado -> (estado as? EstadoDelCobro.Cerrado)?.let { alCerrarEnPantalla(it) } }
        }
        // Recorte R2 (21-nov): la duda de la venta en pantalla la resuelve un gerente EN EL PANEL; la subida consulta el folio y deja en
        // su fila cómo terminó. La pantalla la sigue en vivo y enseña ese desenlace.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            local.map { it.apertura to it.miFolio }.distinctUntilChanged()
                .flatMapLatest { (apertura, folio) -> folio?.let { f -> maquina.filaEnVivo(f).map { apertura to it } } ?: flowOf(apertura to null) }
                .collect { (apertura, fila) -> if (fila?.estado == EstadoDelIntento.RESUELTO) alResolverEnElPanel(apertura, fila) }
        }
    }

    /**
     * El cierre de la máquina del folio de ESTA pantalla. C7 (R3#11): «Te debemos» de un CANCELADO que no devolvió todo, en papel con su
     * folio (por el mismo camino del papel, Codex código r3 #11); GUARDADO: nada. Un PAGADO cobra la venta en la caja ([alPagar]), una vez.
     */
    private suspend fun alCerrarEnPantalla(estado: EstadoDelCobro.Cerrado) {
        val l = local.value
        if (estado.referenceCC != l.miFolio) return
        if (estado.desenlace == Desenlace.CANCELADO && estado.creditCents > estado.changeDispensedCents && comprobantes.add(estado.referenceCC)) {
            scope.launch { maquina.imprimirPapelDelFolio(estado.referenceCC) }
        }
        if (estado.desenlace != Desenlace.PAGADO || estado.referenceCC in pagados) return
        val fila = maquina.fila(estado.referenceCC)
        // Codex código r2 H5: leer la fila suspende; si mientras tanto la pantalla cambió de venta, el PAGADO se queda en su fila y su papel
        // sale de ella (Codex código r3 #11: por el mismo reclamo, nunca dos originales).
        if (local.value.apertura != l.apertura || local.value.miFolio != estado.referenceCC) {
            scope.launch { maquina.imprimirPapelDelFolio(estado.referenceCC) }
            return
        }
        if (!pagados.add(estado.referenceCC)) return
        alPagar(
            CierreEnPantalla(estado.referenceCC, estado.dueCents, estado.creditCents, estado.changeDispensedCents,
                precioPorConfirmar = fila?.quoteId == null, renglones = renglonesDelTicket(fila?.renglonesJson)),
        )
    }

    /**
     * Recorte R2: un gerente resolvió en el panel la duda de la venta en pantalla. PAGADO cobra la venta en la caja, como el de la máquina
     * (su ticket sale por el mismo camino del papel). PAGADO que el SERVIDOR guardó como incidencia (Codex código r1 #11): no hay venta;
     * sólo el comprobante con folio si se le debe al cliente. CANCELADO o GUARDADO: se dice, y «Listo» vuelve a leer si la venta sigue frenada.
     */
    private fun alResolverEnElPanel(apertura: Long, fila: CobroEnMaquinaEntity) {
        val l = local.value
        if (l.apertura != apertura || l.miFolio != fila.referenceCC || l.resuelto != null || l.incidencia != null) return
        val desenlace = fila.desenlace ?: return
        when {
            desenlace == Desenlace.PAGADO && fila.conflicto != null -> {
                siSigue(apertura) { it.copy(incidencia = fila.conflicto) }
                if (fila.aDeberCents > 0 && comprobantes.add(fila.referenceCC)) scope.launch { maquina.imprimirPapelDelFolio(fila.referenceCC) }
            }
            desenlace == Desenlace.PAGADO -> if (pagados.add(fila.referenceCC)) {
                alPagar(
                    CierreEnPantalla(fila.referenceCC, fila.dueCents, fila.creditCents, fila.changeDispensedCents,
                        precioPorConfirmar = fila.quoteId == null, renglones = renglonesDelTicket(fila.renglonesJson)),
                )
            }
            else -> siSigue(apertura) { it.copy(resuelto = desenlace) }
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
                else l.copy(ventaEnDuda = true, miFolio = intento.referenceCC,
                    guardada = EstadoDelCobro.Esperando(intento.referenceCC, intento.dueCents))
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
            // Codex código POS r7 #1 y r8 #2: la regla de qué venta nunca va a la máquina ([VentaEnPantalla.motivoSinMaquina]) se lee aquí, al
            // tocar: la fila puede ir un cuadro atrás.
            if (l.enVuelo || l.miFolio != null || v.motivoSinMaquina != null) return false
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
                    // A la vista y como SU folio en el mismo paso, sin suspender (Codex código POS r4 #12): un PAGADO rápido ya encuentra su
                    // pantalla. Si aun así llegó antes, se atiende aquí (una vez por folio).
                    maquina.aLaVista(r.referenceCC, true)
                    siSigue(mia) { it.copy(miFolio = r.referenceCC, ventaEnDuda = true) }
                    if (local.value.apertura != mia) {
                        maquina.aLaVista(r.referenceCC, false)
                        return false
                    }
                    (maquina.cobro.estado.value as? EstadoDelCobro.Cerrado)?.let { alCerrarEnPantalla(it) }
                    true
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
        local.value = l.copy(miFolio = null, resuelto = null, guardada = null, incidencia = null)
        l.miFolio?.let { maquina.aLaVista(it, false) }
        val v = l.venta ?: return
        scope.launch {
            val enDuda = maquina.ventaBloqueada(v.llaveDeVenta)
            siSigue(l.apertura) { it.copy(ventaEnDuda = enDuda) }
        }
    }

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
