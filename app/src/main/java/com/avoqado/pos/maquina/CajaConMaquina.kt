package com.avoqado.pos.maquina

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
    val puedeResolver: Boolean,
    val resolviendo: Boolean,
    val avisoDeResolver: String?,
    /** El gerente decidió CANCELADO o GUARDADO: la pantalla lo dice y deja salir. */
    val resuelto: Desenlace?,
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
        val venta: VentaEnPantalla? = null,
        val miFolio: String? = null,
        val miCotizada: Boolean = false,
        val ventaEnDuda: Boolean = false,
        val aviso: String? = null,
        val resolviendo: Boolean = false,
        val avisoDeResolver: String? = null,
        val resuelto: Desenlace? = null,
    )

    private val local = MutableStateFlow(Local())

    /** ¿La máquina cobra en ESTE cobro? Síncrono (la [vista] puede ir un cuadro atrás): lo usan las guardas del punto común. */
    val activa: Boolean get() = local.value.venta != null
    private val pagados = mutableSetOf<String>()
    private val comprobantes = mutableSetOf<String>()
    private var enVuelo = false

    val vista: StateFlow<VistaDeLaMaquina?> =
        combine(local, maquina.ahora, maquina.cobro.estado, maquina.avisoDeSubida) { l, ahora, estado, avisoDeSubida ->
            val v = l.venta ?: return@combine null
            val mio = estado.takeIf { folioDe(it) != null && folioDe(it) == l.miFolio }
            VistaDeLaMaquina(
                fila = filaDeLaMaquina(ahora, v.conProductos, v.ventaNueva, l.ventaEnDuda, cobroVivo = vivo(estado)),
                cobro = mio,
                ventaEnDuda = l.ventaEnDuda || mio != null,
                aviso = l.aviso,
                avisoDeSubida = avisoDeSubida,
                puedeResolver = maquina.puedeResolver(),
                resolviendo = l.resolviendo,
                avisoDeResolver = l.avisoDeResolver,
                resuelto = l.resuelto,
            )
        }.stateIn(scope, SharingStarted.Eagerly, null)

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            maquina.cobro.estado.collect { estado ->
                val l = local.value
                if (estado !is EstadoDelCobro.Cerrado || estado.referenceCC != l.miFolio) return@collect
                // C7 (R3#11): «Te debemos» de un CANCELADO que no devolvió todo, en papel con su folio. GUARDADO: nada.
                if (estado.desenlace == Desenlace.CANCELADO && estado.creditCents > estado.changeDispensedCents &&
                    comprobantes.add(estado.referenceCC)
                ) {
                    scope.launch { maquina.imprimirComprobanteUnaVez(estado.referenceCC) }
                }
                if (estado.desenlace == Desenlace.PAGADO && pagados.add(estado.referenceCC)) {
                    alPagar(
                        CierreEnPantalla(estado.referenceCC, estado.dueCents, estado.creditCents, estado.changeDispensedCents,
                            precioPorConfirmar = !l.miCotizada),
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
        local.value = Local(venta = venta.takeIf { activa })
        if (!activa) return
        maquina.arrancar()
        scope.launch {
            val enDuda = maquina.ventaBloqueada(venta!!.llaveDeVenta)
            local.value.takeIf { it.venta == venta }?.let { local.value = it.copy(ventaEnDuda = enDuda) }
        }
    }

    /** R2#1: lee el disco, no la pantalla: la tarjeta no sale para una venta con un intento en la máquina. */
    suspend fun bloqueaLaTarjeta(): Boolean {
        val v = local.value.venta ?: return false
        return local.value.ventaEnDuda || maquina.ventaBloqueada(v.llaveDeVenta)
    }

    /** «Efectivo en la máquina». Devuelve si salió el `paycash`. */
    suspend fun cobrar(v: VentaPorCobrar): Boolean {
        val l = local.value
        if (enVuelo || l.venta == null || l.miFolio != null) return false
        enVuelo = true
        local.value = l.copy(aviso = null)
        try {
            return when (val r = maquina.cobrar(v)) {
                is InicioEnLaMaquina.Iniciado -> {
                    val fila = maquina.fila(r.referenceCC)
                    local.value = local.value.copy(miFolio = r.referenceCC, miCotizada = fila?.quoteId != null, ventaEnDuda = true)
                    true
                }
                is InicioEnLaMaquina.NoSeCobra -> {
                    local.value = local.value.copy(aviso = r.texto,
                        ventaEnDuda = local.value.ventaEnDuda || r.texto == TEXTO_VENTA_CON_INTENTO)
                    false
                }
            }
        } finally {
            enVuelo = false
        }
    }

    fun cancelarPorElCliente() = maquina.cobro.cancelarPorElCliente()

    fun sigoAqui() = maquina.cobro.sigoAqui()

    /**
     * Tras CANCELADO o GUARDADO (o lo que decidió el gerente), de vuelta a la selección: la venta puede pagarse con tarjeta…
     * salvo que siga bloqueada en disco (un gerente la resolvió: su desenlace lo sabe el SERVIDOR).
     */
    fun listo() {
        val l = local.value
        local.value = l.copy(miFolio = null, resuelto = null, avisoDeResolver = null)
        val v = l.venta ?: return
        scope.launch {
            val enDuda = maquina.ventaBloqueada(v.llaveDeVenta)
            local.value.takeIf { it.venta == v }?.let { local.value = it.copy(ventaEnDuda = enDuda) }
        }
    }

    /** El gerente resuelve la duda de ESTA venta (R4-4). Un `PAGADO` cobra la venta en la caja, como el de la máquina. */
    suspend fun resolver(r: ResolucionDelGerente) {
        val l = local.value
        val folio = l.miFolio ?: return
        if (l.resolviendo) return
        local.value = l.copy(resolviendo = true, avisoDeResolver = null)
        val resultado = maquina.resolver(folio, r)
        local.value = local.value.copy(resolviendo = false)
        when (resultado) {
            is ResultadoDeResolver.NoSe -> local.value = local.value.copy(avisoDeResolver = resultado.texto)
            is ResultadoDeResolver.Resuelto -> if (resultado.desenlace == Desenlace.PAGADO) {
                val fila = maquina.fila(folio)
                if (pagados.add(folio)) {
                    alPagar(
                        CierreEnPantalla(folio, fila?.dueCents ?: 0, resultado.creditCents, resultado.changeDispensedCents,
                            precioPorConfirmar = fila?.quoteId == null),
                    )
                }
            } else {
                local.value = local.value.copy(resuelto = resultado.desenlace)
            }
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
