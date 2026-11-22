package com.avoqado.pos.maquina.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.maquina.BovedaDeLaMaquina
import com.avoqado.pos.maquina.CobroEnMaquinaDao
import com.avoqado.pos.maquina.EmparejamientoDeLaMaquina
import com.avoqado.pos.maquina.MaquinaDeEfectivoPrefs
import com.avoqado.pos.maquina.TEXTO_IP_INVALIDA
import com.avoqado.pos.maquina.TEXTO_PENDIENTES_DE_LA_MAQUINA
import com.avoqado.pos.maquina.TEXTO_SIN_RED_DESEMPAREJAR
import com.avoqado.pos.maquina.TEXTO_SIN_RED_EMPAREJAR
import com.avoqado.pos.maquina.esIpv4
import com.avoqado.pos.maquina.hayPendientesDeLaMaquina
import com.avoqado.pos.maquina.probarConexion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException
import javax.inject.Inject

enum class PruebaDeConexion { PROBANDO, CONECTADA, NO_RESPONDE }

/** C7 (R5#9, R3#11): un cobro de la máquina que le debe dinero a un cliente, con su comprobante reimprimible. */
data class ComprobantePorImprimir(
    val referenceCC: String,
    /** Codex código POS r5 #3: null = lo que dijo la máquina contradice la resolución: «Revisar en el panel», sin comprobante. */
    val debeCents: Int?,
)

/** Lo que pinta la hoja «Máquina de efectivo». Las reglas de los botones viven aquí, para la hoja y para el ViewModel. */
data class EstadoDeLaMaquina(
    val cargado: Boolean = false,
    /** Lo que está escrito en el campo. */
    val ip: String = "",
    val ipGuardada: String? = null,
    val activa: Boolean = false,
    val conCredencial: Boolean = false,
    /** R5#2: filas de `cobros_en_maquina` sin subir o en duda (de cualquier sucursal). */
    val hayPendientes: Boolean = false,
    val prueba: PruebaDeConexion? = null,
    val ocupado: Boolean = false,
    val aviso: String? = null,
    /** El texto del toast verde; null cuando ya se vio. */
    val listo: String? = null,
    /** C7: lo que la máquina les debe a clientes (diferencia de precio o dinero no devuelto), los más nuevos primero. */
    val comprobantes: List<ComprobantePorImprimir> = emptyList(),
    /** Codex código r1 #13: cuántos cobros le deben algo a clientes en la sucursal (la lista enseña los más nuevos; «Ver más»). */
    val comprobantesTotal: Int = 0,
    /** La sesión trae `cash-machine:configure`: sin él la IP, probar y emparejar quedan en gris; el SERVIDOR lo vuelve a exigir. */
    val puedeConfigurar: Boolean = true,
    /** Codex código r1 #10: la sucursal con que está emparejada esta PC cuando la sesión está en OTRA (la máquina no cobra aquí). */
    val emparejadaConOtra: String? = null,
    /** Codex código r2 #12: los cobros pagados de la sucursal cuyo papel no salió; aquí se llega aunque la máquina esté apagada. */
    val ticketsSinImprimir: Int = 0,
) {
    val emparejada: Boolean get() = activa && conCredencial

    /** R5#3: la casilla prendida sin credencial que se pueda leer (perfil copiado de otra PC): se empareja otra vez. */
    val credencialPerdida: Boolean get() = activa && !conCredencial

    /** R5#2: con cobros de la máquina sin subir o en duda, la IP de una máquina activa no se cambia. */
    val ipBloqueada: Boolean get() = activa && hayPendientes

    private val ipNueva: Boolean get() = ip.trim() != ipGuardada

    val puedeEmparejar: Boolean
        get() = puedeConfigurar && cargado && !ocupado && esIpv4(ip) && !(emparejada && !ipNueva && emparejadaConOtra == null) &&
            !(ipBloqueada && (ipNueva || emparejadaConOtra != null))

    val puedeDesemparejar: Boolean get() = puedeConfigurar && cargado && !ocupado && activa && !hayPendientes
}

/**
 * Ajustes «Máquina de efectivo» de ESTA PC (C6). Sólo se abre con `cash-machine:configure` en la sesión (R5#10, la fila de
 * Ajustes); el SERVIDOR lo vuelve a exigir. Las dudas se resuelven en el panel (recorte R2): aquí sólo los tickets y los comprobantes.
 * Emparejar y desemparejar son ONLINE-ONLY a propósito: sin internet se dice y no
 * se toca nada. El freno de R5#2 se cuenta al TOCAR, no con lo que había al abrir la hoja.
 */
@HiltViewModel
class MaquinaDeEfectivoViewModel @Inject constructor(
    private val prefs: MaquinaDeEfectivoPrefs,
    private val boveda: BovedaDeLaMaquina,
    private val emparejamiento: EmparejamientoDeLaMaquina,
    private val cobros: CobroEnMaquinaDao,
    /** C7: los comprobantes de lo que la máquina debe. Default por el patrón de las pruebas de JVM; Hilt y Guice lo inyectan. */
    private val maquinaDeEfectivo: com.avoqado.pos.maquina.MaquinaDeEfectivo? = null,
) : ViewModel() {

    private val _estado = MutableStateFlow(EstadoDeLaMaquina())
    val estado: StateFlow<EstadoDeLaMaquina> = _estado.asStateFlow()

    /** La red local NO usa el cliente de la API: a la máquina no se le manda la sesión. */
    private val okHttpDeLaRedLocal by lazy { OkHttpClient() }

    /** Codex código r1 #13: cuántos comprobantes se enseñan; «Ver más» lo sube. */
    private var limiteDeComprobantes = COMPROBANTES_POR_PAGINA

    /** Codex código r1 #13: la siguiente página de lo que la máquina les debe a clientes. */
    fun verMasComprobantes() {
        limiteDeComprobantes += COMPROBANTES_POR_PAGINA
        viewModelScope.launch { refrescar() }
    }

    /** Seam de pruebas. */
    internal var probador: suspend (String) -> Boolean = { ip -> probarConexion(ip, okHttpDeLaRedLocal) }

    /** Cada vez que se abre la hoja: lo guardado y el freno, al día. El campo arranca con la IP guardada. */
    fun abrir() {
        _estado.update { it.copy(ocupado = true, prueba = null, aviso = null, listo = null) }
        viewModelScope.launch { refrescar(campo = true) }
    }

    fun cambiarIp(texto: String) = _estado.update { it.copy(ip = texto, prueba = null, aviso = null) }

    fun probar() {
        val actual = _estado.value
        if (actual.prueba == PruebaDeConexion.PROBANDO) return
        val ip = actual.ip.trim()
        if (!esIpv4(ip)) return _estado.update { it.copy(aviso = TEXTO_IP_INVALIDA) }
        _estado.update { it.copy(prueba = PruebaDeConexion.PROBANDO, aviso = null) }
        viewModelScope.launch {
            val responde = try {
                probador(ip)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            _estado.update { it.copy(prueba = if (responde) PruebaDeConexion.CONECTADA else PruebaDeConexion.NO_RESPONDE) }
        }
    }

    fun emparejar() {
        val actual = _estado.value
        if (actual.ocupado || !actual.puedeConfigurar) return
        val ip = actual.ip.trim()
        if (!esIpv4(ip)) return _estado.update { it.copy(aviso = TEXTO_IP_INVALIDA) }
        // Ya emparejada con esta IP en ESTA sucursal: nada que hacer. Emparejada en otra (Codex código r1 #10) sí se puede emparejar aquí.
        if (actual.emparejada && ip == actual.ipGuardada && actual.emparejadaConOtra == null) return
        _estado.update { it.copy(ocupado = true, aviso = null, listo = null) }
        viewModelScope.launch {
            var reemplazo: String? = null
            val aviso = intentar {
                // R5#2 y Codex código r1 #10: cambiar la IP o la sucursal de una máquina activa con cobros sin subir o en duda los
                // dejaría sin dueño (la credencial nueva no sube lo de la otra sucursal).
                val otraSucursal = prefs.venueId != emparejamiento.sucursalDeLaSesion
                if (prefs.activa && (ip != prefs.ip || otraSucursal) && cobros.hayPendientesDeLaMaquina()) return@intentar TEXTO_PENDIENTES_DE_LA_MAQUINA
                val emparejado = emparejamiento.emparejar(ip).getOrElse { return@intentar textoDeFalla(it, TEXTO_SIN_RED_EMPAREJAR) }
                boveda.guardar(emparejado.credencial)          // primero la credencial: la casilla nunca se prende sin ella
                prefs.guardar(
                    ip, activa = true, venueId = emparejado.venueId, nombreDeLaSucursal = emparejado.nombreDeLaSucursal,
                    bindingId = emparejado.bindingId,
                )
                // Rediseño del SERVIDOR (D3): emparejar dio de baja a la PC anterior; la máquina le sigue obedeciendo si no se desconecta.
                reemplazo = com.avoqado.pos.maquina.textoDePcReemplazada(emparejado.reemplazadas)
                maquinaDeEfectivo?.alEmparejar()   // E1 (obs. 1): «no está emparejada» se quita ya, no en la primera subida
                null
            }
            refrescar(aviso = aviso ?: reemplazo, listo = "¡Máquina emparejada!".takeIf { aviso == null })
        }
    }

    fun desemparejar() {
        if (_estado.value.ocupado || !_estado.value.puedeConfigurar) return
        _estado.update { it.copy(ocupado = true, aviso = null, listo = null) }
        viewModelScope.launch {
            val aviso = intentar {
                if (cobros.hayPendientesDeLaMaquina()) return@intentar TEXTO_PENDIENTES_DE_LA_MAQUINA
                emparejamiento.desemparejar().getOrElse { return@intentar textoDeFalla(it, TEXTO_SIN_RED_DESEMPAREJAR) }
                // Codex código POS r4 #4: si mientras se daba de baja llegó evidencia que sólo está aquí, la credencial se queda para
                // entregarla (el SERVIDOR la guarda como incidencia con su dinero).
                if (!cobros.hayPendientesDeLaMaquina()) boveda.borrar()
                prefs.guardar(prefs.ip, activa = false)   // la IP se queda para volver a emparejar
                null
            }
            refrescar(aviso = aviso, listo = "Máquina desemparejada".takeIf { aviso == null })
        }
    }

    fun toastVisto() = _estado.update { it.copy(listo = null) }

    /** Codex código r2 #12: imprime los papeles pendientes (reclamados uno por uno; con incidencia, el comprobante del dinero). */
    fun imprimirTicketsPendientes() {
        val maquina = maquinaDeEfectivo ?: return
        viewModelScope.launch {
            val salieron = try {
                maquina.imprimirTicketsPendientes()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                0
            }
            if (salieron > 0) refrescar(listo = if (salieron == 1) "¡Ticket impreso!" else "¡$salieron tickets impresos!")
            else refrescar(aviso = "No salió ningún ticket: revisa la impresora")
        }
    }

    /** C7: (re)imprime el comprobante de [referenceCC] con el mismo folio; el resultado real de la impresora se dice. */
    fun imprimirComprobante(referenceCC: String) {
        val maquina = maquinaDeEfectivo ?: return
        viewModelScope.launch {
            val r = try {
                maquina.imprimirComprobante(referenceCC)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                com.avoqado.pos.printing.data.PrinterService.PrintOutcome.Failed(e.message ?: "Error al imprimir")
            }
            _estado.update {
                when (r) {
                    is com.avoqado.pos.printing.data.PrinterService.PrintOutcome.Printed -> it.copy(listo = "¡Comprobante impreso!", aviso = null)
                    com.avoqado.pos.printing.data.PrinterService.PrintOutcome.OutOfPaper -> it.copy(aviso = "La impresora no tiene papel")
                    com.avoqado.pos.printing.data.PrinterService.PrintOutcome.NoPrinter -> it.copy(aviso = "No hay impresora de recibos en esta PC")
                    is com.avoqado.pos.printing.data.PrinterService.PrintOutcome.Failed -> it.copy(aviso = "No se pudo imprimir: ${r.reason}")
                    null -> it.copy(aviso = "Ese cobro ya no le debe nada al cliente")
                }
            }
        }
    }

    /** En disco y red: null = salió bien; si no, el texto para la hoja. */
    private suspend fun intentar(accion: suspend () -> String?): String? = try {
        withContext(Dispatchers.IO) { accion() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "No se pudo guardar el ajuste de la máquina", e)
        "No se pudo guardar en esta PC: ${e.message}"
    }

    private suspend fun refrescar(campo: Boolean = false, aviso: String? = null, listo: String? = null) {
        val (ip, activa, conCredencial, pendientes) = withContext(Dispatchers.IO) {
            val credencial = try {
                boveda.leer()
            } catch (e: Exception) {
                Log.w(TAG, "La credencial de la máquina no se pudo leer", e)
                null
            }
            Lectura(prefs.ip, prefs.activa, credencial != null, cobros.hayPendientesDeLaMaquina())
        }
        val comprobantes = runCatching { maquinaDeEfectivo?.comprobantes(limiteDeComprobantes).orEmpty() }.getOrDefault(emptyList())
            .map { (fila, debe) -> ComprobantePorImprimir(fila.referenceCC, debe) }
        val comprobantesTotal = runCatching { maquinaDeEfectivo?.cuantosComprobantes() ?: 0 }.getOrDefault(comprobantes.size)
        val ticketsSinImprimir = runCatching { maquinaDeEfectivo?.cuantosTicketsSinImprimir() ?: 0 }.getOrDefault(0)
        val emparejadaConOtra = (prefs.nombreDeLaSucursal ?: "otra sucursal")
            .takeIf { activa && prefs.venueId != emparejamiento.sucursalDeLaSesion }
        _estado.update {
            it.copy(
                cargado = true, ocupado = false, ip = if (campo) ip.orEmpty() else it.ip, ipGuardada = ip, activa = activa,
                conCredencial = conCredencial, hayPendientes = pendientes, aviso = aviso, listo = listo, comprobantes = comprobantes,
                emparejadaConOtra = emparejadaConOtra,
                // Sin la máquina inyectada (las pruebas de JVM del emparejamiento) no hay sesión que revisar: el SERVIDOR lo exige igual.
                puedeConfigurar = runCatching { maquinaDeEfectivo?.puedeConfigurar() }.getOrNull() ?: true,
                comprobantesTotal = maxOf(comprobantesTotal, comprobantes.size), ticketsSinImprimir = ticketsSinImprimir,
            )
        }
    }

    private data class Lectura(val ip: String?, val activa: Boolean, val conCredencial: Boolean, val pendientes: Boolean)

    private fun textoDeFalla(e: Throwable, sinRed: String): String =
        if (e is IOException) sinRed else e.message ?: "No se pudo. Inténtalo de nuevo."

    private companion object {
        const val TAG = "💵Maquina"
        const val COMPROBANTES_POR_PAGINA = 50
    }
}
