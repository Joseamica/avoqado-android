package com.avoqado.pos.giftcards.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.pos.data.LectorHidBus
import com.avoqado.pos.giftcards.data.*
import com.avoqado.pos.giftcards.domain.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import javax.inject.Inject

sealed interface EstadoDeIdentificacion {
    data object Capturando: EstadoDeIdentificacion
    data object Consultando: EstadoDeIdentificacion
    data object Desconocido: EstadoDeIdentificacion
    data object CodigoPedido: EstadoDeIdentificacion
    data class Saldo(val consulta: ConsultaDeSaldo, val prueba: PruebaDeTarjeta?): EstadoDeIdentificacion
    data class Eligiendo(val resultado: VerificacionPorTelefono): EstadoDeIdentificacion
    data class Error(val mensaje: String): EstadoDeIdentificacion
}

@HiltViewModel
class IdentificarTarjetaViewModel @Inject constructor(
    private val repo: GiftCardsRepository, private val almacen: SecureStorage,
    private val red: ConnectivityMonitor, private val lector: LectorHidBus, private val roles: RoleManager,
): ViewModel() {
    private val mutableEstado = MutableStateFlow<EstadoDeIdentificacion>(EstadoDeIdentificacion.Capturando)
    val estado = mutableEstado.asStateFlow()
    private val mutableIdentificada = MutableStateFlow<TarjetaIdentificada?>(null)
    val identificada = mutableIdentificada.asStateFlow()
    private val bloqueo = MutableStateFlow<Long?>(null)
    val bloqueadoHasta = bloqueo.asStateFlow()
    val conexion = red.isConnected
    val servidor = red.isServerReachable
    val cambiosDeAjustes = repo.cambiosDeAjustes
    fun ajustesVigentes(venueId: String): AjustesDeTarjetas? = repo.ajustesParaEntrada(venueId)
    private val entrada = MutableStateFlow("")
    val codigo = entrada.asStateFlow()
    fun cambiarCodigo(texto: String) { entrada.value = texto; ultimaPrueba = null }
    fun cambiarTelefono(telefonoDigitos: String) {
        if (ocupada) return
        telefonoActual = telefonoMx(telefonoDigitos); telefonoVerificado = null
        pruebaVence = null; caducidad?.cancel(); caducidad = null
        mutableIdentificada.value = null; ultimaPrueba = null
        mutableEstado.value = EstadoDeIdentificacion.Capturando
    }
    suspend fun enviarCodigo() = porCodigo(entrada.value)
    fun procesarTecla(evento: android.view.KeyEvent): Boolean = lector.procesar(evento)
    val puedeReintentar: Boolean get() = ultimaPrueba != null
    suspend fun reintentar() { ultimaPrueba?.let { consultar(it) } }
    internal var ahora: () -> Long = System::currentTimeMillis
    private var venue: String? = null
    private var usuario: String? = null
    private var ajustes: AjustesDeTarjetas? = null
    private var generacion = 0L
    private var ocupada = false
    private var escucha: Job? = null
    private var temporizador: Job? = null
    private var caducidad: Job? = null
    private var pruebaVence: Long? = null
    private var telefonoVerificado: String? = null
    private var telefonoActual: String? = null
    private var ultimaPrueba: PruebaDeTarjeta? = null

    fun abrir(venueId: String, ajustes: AjustesDeTarjetas?) {
        cerrar()
        if (almacen.venueId != venueId || almacen.userId == null) return
        venue = venueId; usuario = almacen.userId; this.ajustes = ajustes
        escucha = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            lector.codigos.collect { crudo -> if (LectorParaTarjetas.abierta.value) launch { porEscaneo(crudo) } }
        }
        LectorParaTarjetas.abrir()
    }
    fun cerrar() {
        generacion++; ocupada = false; escucha?.cancel(); escucha = null
        temporizador?.cancel(); temporizador = null; bloqueo.value = null
        caducidad?.cancel(); caducidad = null
        pruebaVence = null; telefonoVerificado = null; telefonoActual = null; mutableIdentificada.value = null
        entrada.value = ""; ultimaPrueba = null
        mutableEstado.value = EstadoDeIdentificacion.Capturando
        venue = null; usuario = null; ajustes = null
        LectorParaTarjetas.cerrar()
    }
    fun contextoVigente(): Boolean {
        if (venue == null) return false
        val actuales = repo.ajustesParaEntrada(venue!!)
        if (venue != almacen.venueId || usuario != almacen.userId || actuales == null) { cerrar(); return false }
        ajustes = actuales.copy(capacidades = actuales.capacidades.intersect(ajustes?.capacidades.orEmpty()))
        return true
    }
    private fun comenzar(): Long? {
        if (!contextoVigente() || ocupada || (bloqueo.value ?: 0) > ahora()) return null
        if (!roles.canReadGiftCards) { mutableEstado.value = EstadoDeIdentificacion.Error(TextosDeTarjetas.SIN_PERMISO_CONSULTAR); return null }
        if (!red.isConnected.value || !red.isServerReachable.value) { mutableEstado.value = EstadoDeIdentificacion.Error(TextosDeTarjetas.SIN_RED); return null }
        ocupada = true; mutableIdentificada.value = null; pruebaVence = null
        caducidad?.cancel(); caducidad = null
        mutableEstado.value = EstadoDeIdentificacion.Consultando
        return generacion
    }
    private suspend fun <T> pedir(accion: suspend (String) -> Respuesta<T>, exito: (T) -> Unit) {
        val g = comenzar() ?: return
        try {
            val respuesta = accion(venue!!)
            if (g != generacion || !contextoVigente()) return
            when (respuesta) {
                is Respuesta.Ok -> exito(respuesta.valor)
                is Respuesta.Rechazo -> {
                    val f = respuesta.fallo
                    mutableEstado.value = EstadoDeIdentificacion.Error(when {
                        f.http == 401 -> TextosDeTarjetas.SIN_RED
                        f.http == 403 && f.code == null -> TextosDeTarjetas.SIN_PERMISO_CONSULTAR
                        else -> f.message ?: TextosDeTarjetas.REINTENTAR
                    })
                    if (f.http == 429 && f.code != null && (f.retryAfterSeg ?: 0) > 0) {
                        val ms = f.retryAfterSeg!!.toLong() * 1000
                        bloqueo.value = ahora() + ms
                        temporizador?.cancel()
                        temporizador = viewModelScope.launch { delay(ms); if (g == generacion) bloqueo.value = null }
                    }
                }
                Respuesta.SinRespuesta -> mutableEstado.value = EstadoDeIdentificacion.Error(TextosDeTarjetas.SIN_RED)
            }
        } catch (e: CancellationException) {
            if (g == generacion) mutableEstado.value = EstadoDeIdentificacion.Capturando
            throw e
        } finally { if (g == generacion) ocupada = false }
    }
    suspend fun porEscaneo(crudo: String) {
        if (!contextoVigente() || ocupada) return
        entrada.value = crudo; ultimaPrueba = null
        val p = ReconocedorDeTarjeta.reconocer(crudo)
        if (p is PruebaDeTarjeta.PageToken && ajustes?.porLiga != true) { mutableEstado.value = EstadoDeIdentificacion.Desconocido; return }
        if (p == null) porCodigo(crudo) else consultar(p)
    }
    suspend fun porPrueba(prueba: PruebaDeTarjeta) {
        if (!contextoVigente() || ocupada) return
        entrada.value = when (prueba) {
            is PruebaDeTarjeta.Codigo -> prueba.code
            is PruebaDeTarjeta.PageToken -> prueba.pageToken
            is PruebaDeTarjeta.Telefono -> return
        }
        ultimaPrueba = null
        if (prueba is PruebaDeTarjeta.PageToken && ajustes?.porLiga != true) { mutableEstado.value = EstadoDeIdentificacion.Desconocido; return }
        if (prueba !is PruebaDeTarjeta.Telefono) consultar(prueba)
    }
    suspend fun porCodigo(texto: String) {
        if (!contextoVigente() || ocupada) return
        entrada.value = texto; ultimaPrueba = null
        val code = texto.trim()
        if (code.length !in 4..40) { mutableEstado.value = EstadoDeIdentificacion.Error("Escribe entre 4 y 40 caracteres"); return }
        consultar(PruebaDeTarjeta.Codigo(code))
    }
    private suspend fun consultar(prueba: PruebaDeTarjeta) {
        if (!contextoVigente() || ocupada) return
        ultimaPrueba = prueba
        pedir({ repo.consultar(it, prueba) }) { consulta ->
            mutableEstado.value = EstadoDeIdentificacion.Saldo(consulta, prueba)
            consulta.cards.singleOrNull()?.let { mutableIdentificada.value = TarjetaIdentificada(prueba, it) }
        }
    }
    suspend fun pedirCodigo(telefonoDigitos: String) {
        if (!contextoVigente() || ocupada || ajustes?.porTelefono != true) return
        val phone = telefonoMx(telefonoDigitos) ?: run { mutableEstado.value = EstadoDeIdentificacion.Error("Escribe 10 dígitos de México (+52)"); return }
        telefonoActual = phone; telefonoVerificado = null; ultimaPrueba = null
        pedir({ repo.pedirCodigo(it, phone) }) { telefonoVerificado = phone; mutableEstado.value = EstadoDeIdentificacion.CodigoPedido }
    }
    suspend fun verificar(telefonoDigitos: String, codigo: String) {
        if (!contextoVigente() || ocupada || ajustes?.porTelefono != true) return
        val phone = telefonoMx(telefonoDigitos) ?: return
        if (!Regex("[0-9]{6}").matches(codigo)) { mutableEstado.value = EstadoDeIdentificacion.Error(TextosDeTarjetas.CODIGO_DE_6_DIGITOS); return }
        // Una edición del teléfono invalida el reto anterior, incluso antes de tocar la red.
        if (telefonoVerificado != null && telefonoVerificado != phone) { mutableEstado.value = EstadoDeIdentificacion.Capturando; telefonoVerificado = null; return }
        telefonoActual = phone; ultimaPrueba = null
        pedir({ repo.verificarCodigo(it, phone, codigo) }) { result ->
            telefonoVerificado = phone
            if (result.cards.isEmpty()) mutableEstado.value = EstadoDeIdentificacion.Error(TextosDeTarjetas.TELEFONO_SIN_TARJETAS)
            else {
                pruebaVence = minOf(ahora() + 15 * 60 * 1000, runCatching { Instant.parse(result.expiresAt).toEpochMilli() }.getOrDefault(ahora()))
                mutableEstado.value = EstadoDeIdentificacion.Eligiendo(result)
                val g = generacion
                caducidad = viewModelScope.launch {
                    delay(((pruebaVence ?: ahora()) - ahora()).coerceAtLeast(0))
                    if (g == generacion) {
                        pruebaVence = null; mutableIdentificada.value = null
                        mutableEstado.value = EstadoDeIdentificacion.Capturando
                    }
                }
            }
        }
    }
    fun elegir(giftCardId: String) {
        if (!contextoVigente()) return
        if (telefonoVerificado == null || telefonoVerificado != telefonoActual) return
        if ((pruebaVence ?: 0) <= ahora()) { mutableIdentificada.value = null; mutableEstado.value = EstadoDeIdentificacion.Capturando; return }
        val resultado = (mutableEstado.value as? EstadoDeIdentificacion.Eligiendo)?.resultado ?: return
        val card = resultado.cards.firstOrNull { it.giftCardId == giftCardId } ?: return
        mutableIdentificada.value = TarjetaIdentificada(PruebaDeTarjeta.Telefono(resultado.proofToken, card.giftCardId), card)
    }
    fun puedeCobrar(hayVenta: Boolean, destinoConectado: Boolean): Boolean =
        red.isConnected.value && red.isServerReachable.value && destinoConectado && hayVenta && contextoVigente() && mutableIdentificada.value?.tarjeta?.usable == true &&
            (mutableIdentificada.value?.prueba !is PruebaDeTarjeta.Telefono || (pruebaVence ?: 0) > ahora())
    override fun onCleared() { cerrar() }
}
