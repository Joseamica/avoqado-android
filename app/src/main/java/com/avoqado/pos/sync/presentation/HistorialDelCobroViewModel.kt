package com.avoqado.pos.sync.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.core.data.local.database.RevisionDelCobroRecord
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.tables.data.TableSession
import com.avoqado.pos.payment.data.ConciliacionDelCobro
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

/** Existing payment rows only. A review records who saw a confirmed result; it never replays or deletes money. */
@HiltViewModel
class HistorialDelCobroViewModel @Inject constructor(
    private val dao: PendingPaymentDao,private val storage: SecureStorage,
    private val roles: RoleManager,private val session: TableSession,
    private val staff: com.avoqado.pos.pos.data.StaffRepository,
):ViewModel() {
    // «Revisado por» guarda el id; aquí se pinta el nombre (lista de personal, con su copia sin red). Nunca el id.
    private val _nombres=MutableStateFlow<Map<String,String>>(emptyMap());val nombres=_nombres.asStateFlow()
    private var nombresPedidosPara:Contexto?=null;private var nombresPedidosEn=0L
    fun revisor(row:RevisionDelCobroRecord,nombres:Map<String,String> = _nombres.value):String = row.q15ReviewedBy?.let { nombres[it] } ?: "otro usuario"
    /** Los primeros 8 caracteres tal cual: la búsqueda es `instr(id, texto)`, así que lo que se ve se encuentra. */
    // ponytail: 8 caracteres de un UUID v4 = 32 bits al azar; con N filas en revisión la probabilidad de que dos coincidan
    // es ~N²/2³³ (100 filas ≈ 1 en 860 mil). Si pasa, la búsqueda trae las dos y la cuenta y el importe las distinguen.
    fun folio(row:RevisionDelCobroRecord):String = row.id.take(8)
    /** Recorrido en la D3 (19-oct): en mostrador la cuenta ES el folio y salía otra vez el código largo. La cuenta va sólo si
     *  es otra cosa, y corta igual que el folio (la búsqueda `instr` también la encuentra). Igual que iOS. */
    fun referencia(row:RevisionDelCobroRecord):String =
        "Folio ${folio(row)}" + (row.capturedOrderRef?.takeIf { it!=row.id }?.let { " · cuenta ${it.take(8)}" } ?: "")
    private data class Contexto(val venue:String?,val staff:String?,val epoch:Long)
    private fun contextoActual()=Contexto(storage.venueId,storage.userId,session.contextoActual())
    private var contexto=contextoActual()
    private var generation=0L
    private val _filas=MutableStateFlow<List<RevisionDelCobroRecord>>(emptyList());val filas=_filas.asStateFlow()
    private val _total=MutableStateFlow(0);val total=_total.asStateFlow()
    private val _cargando=MutableStateFlow(false);val cargando=_cargando.asStateFlow()
    private val _revisadas=MutableStateFlow(false);val revisadas=_revisadas.asStateFlow()
    private val _busqueda=MutableStateFlow("");val busqueda=_busqueda.asStateFlow()
    private val _mensaje=MutableStateFlow<String?>(null);val mensaje=_mensaje.asStateFlow()
    val canRead:Boolean get()=roles.canResolveQuarantine && vigente()
    private fun vigente(snapshot:Contexto=contexto)=snapshot.venue!=null && contexto==snapshot && contextoActual()==snapshot
    private fun limpiar() { _filas.value=emptyList();_total.value=0 }
    private fun continuar(snapshot:Contexto,version:Long):Boolean {
        if(version!=generation) return false
        if(!vigente(snapshot) || !roles.canResolveQuarantine) { limpiar();return false }
        return true
    }
    fun cambiarFiltro(revisadas:Boolean,busqueda:String) { _revisadas.value=revisadas;_busqueda.value=busqueda;cargar() }
    fun cargar() {
        val actual=contextoActual()
        if(actual!=contexto) {
            contexto=actual;limpiar();_mensaje.value=null;_cargando.value=false
            _revisadas.value=false;_busqueda.value="";_nombres.value=emptyMap()
        }
        // Una vez por negocio/sesión (no en cada letra de la búsqueda); sin nombres, a lo más un reintento por minuto.
        // Sólo se publica si sigue siendo el mismo contexto.
        val ahora=System.currentTimeMillis()
        if(canRead && (nombresPedidosPara!=contexto || (_nombres.value.isEmpty() && ahora-nombresPedidosEn>60_000))) {
            val snapshot=contexto;nombresPedidosPara=snapshot;nombresPedidosEn=ahora
            viewModelScope.launch {
                try {
                    val lista=staff.getActiveStaff(deFondo=true).getOrNull() ?: return@launch
                    if(vigente(snapshot)) _nombres.value=lista.associate { it.id to it.fullName }
                } catch(e:kotlinx.coroutines.CancellationException) { throw e }
                catch(e:Exception) { /* sin lista: «otro usuario»; nunca tumba la pantalla de revisión */ }
            }
        }
        pagina(false)
    }
    fun cargarMas() { if(!_cargando.value && _filas.value.size<_total.value) pagina(true) }
    private fun pagina(append:Boolean) {
        val version=++generation
        if(!canRead) { limpiar();_cargando.value=false;_mensaje.value="Un gerente debe revisar este pago.";return }
        val snapshot=contexto
        val venue=requireNotNull(snapshot.venue);val reviewed=_revisadas.value;val search=_busqueda.value
        val previous=if(append) _filas.value else emptyList()
        val cursor=previous.lastOrNull()
        _cargando.value=true
        viewModelScope.launch {
            if(!continuar(snapshot,version)) return@launch
            try {
                val count=dao.contarRevisionesQ15(venue,reviewed,search)
                if(!continuar(snapshot,version)) return@launch
                val page=dao.paginaRevisionesQ15(venue,reviewed,search,cursor?.q15DetectedAt,cursor?.origen,cursor?.id)
                if(!continuar(snapshot,version)) return@launch
                _total.value=count;_filas.value=(previous+page).distinctBy { it.origen+":"+it.id }
            } catch(e:kotlinx.coroutines.CancellationException) { throw e }
            catch(e:Exception) { if(continuar(snapshot,version)) _mensaje.value="No se pudo leer el historial. El pago sigue conservado." }
            finally { if(version==generation) _cargando.value=false }
        }
    }
    fun puedeMarcar(row:RevisionDelCobroRecord)=canRead && row.venueId==contexto.venue && row.q15ReviewedAt==null &&
        row.estado in setOf("SYNCED","ACKED") && row.q15Reason !in setOf(ConciliacionDelCobro.PRICE_PROOF_PENDING,ConciliacionDelCobro.CAPTURA_SIN_EVIDENCIA)
    fun marcarRevisado(row:RevisionDelCobroRecord) {
        if(!puedeMarcar(row) || _filas.value.none { it.origen==row.origen && it.id==row.id }) {
            _mensaje.value="El pago se conserva hasta comprobar su resultado. No vuelvas a cobrar.";return
        }
        val snapshot=contexto;val version=generation
        val actor=snapshot.staff?.takeIf { it.isNotBlank() } ?: return
        val role=roles.role;val venue=requireNotNull(snapshot.venue)
        viewModelScope.launch {
            if(!continuar(snapshot,version)) return@launch
            try {
                val changed=if(row.origen=="PAYMENT") dao.revisarPagoQ15(venue,row.id,actor,role,System.currentTimeMillis())
                    else dao.revisarIntentQ15(venue,row.id,actor,role,System.currentTimeMillis())
                if(!continuar(snapshot,version)) return@launch
                _mensaje.value=if(changed==1) "Revisión guardada en el historial." else "El pago se conserva hasta comprobar su resultado. No vuelvas a cobrar."
                cargar()
            } catch(e:kotlinx.coroutines.CancellationException) { throw e }
            catch(e:Exception) { if(continuar(snapshot,version)) _mensaje.value="No se pudo guardar la revisión. El pago sigue conservado." }
        }
    }
    /** «Efectivo · cuenta completa»: el método con la traducción oficial (igual que iOS) y sólo los datos que existen. */
    // Un tipo de pago personalizado viaja SIN método a propósito: ahí el tipo es el método, no un «Sin confirmar».
    private fun metodoLegible(method:String?,splitType:String?,tenderTypeId:String?):String = listOfNotNull(
        method?.let { com.avoqado.pos.transactions.data.model.PaymentMethodDisplay.label(it) }
            ?: if(tenderTypeId!=null) "Tipo de pago personalizado" else "Sin confirmar",
        when(splitType) {
            null->null
            "FULLPAYMENT"->"cuenta completa"
            "PERPRODUCT"->"por producto"
            "EQUALPARTS"->"partes iguales"
            "CUSTOMAMOUNT"->"monto personalizado"
            else->splitType.lowercase().replace('_',' ')
        },
        tenderTypeId?.takeIf { method!=null }?.let { "tipo de pago personalizado" },
    ).joinToString(" · ")
    fun motivo(row:RevisionDelCobroRecord)=when(row.q15Reason) {
        ConciliacionDelCobro.DIFERENCIA->"El registro del pago cambió al sincronizar."
        ConciliacionDelCobro.CAPTURA_SIN_EVIDENCIA->"Falta la evidencia original de este pago."
        ConciliacionDelCobro.PRICE_PROOF_PENDING->"El pago espera comprobar el precio original."
        else->"Todavía no podemos comprobar el resultado de este pago."
    }
    fun detalle(row:RevisionDelCobroRecord):List<String> {
        val capture=ConciliacionDelCobro.leerCaptura(row.q15CaptureJson,row.venueId,row.id)
        fun money(value:Int?)=value?.let {java.lang.String.format(java.util.Locale.US,"$%.2f",it/100.0)} ?: "Sin confirmar"
        val lines=mutableListOf(if(capture==null) "Captura original: sin evidencia" else
            "Captura original: importe ${money(capture.amountCents)} · propina ${money(capture.tipCents)}")
        capture?.let { c -> lines.add("Método original: ${metodoLegible(c.method,c.splitType,c.tenderTypeId)}") }
        fun result(label:String,raw:String?) {
            val obj=raw?.let {runCatching {Json.parseToJsonElement(it) as? JsonObject}.getOrNull()}
            fun cents(key:String)=(obj?.get(key) as? JsonPrimitive)?.takeUnless {it.isString}?.intOrNull?.takeIf {it>=0}
            fun text(key:String)=(obj?.get(key) as? JsonPrimitive)?.takeIf {it.isString}?.content
            lines.add("$label: importe ${money(cents("amountCents"))} · propina ${money(cents("tipCents"))}")
            text("paymentId")?.let {lines.add("Folio del servidor: $it")}
            if(obj!=null) {
                lines.add("Método registrado: ${metodoLegible(text("method"),text("splitType"),text("tenderTypeId"))}")
                val labels=mapOf("amountCents" to "importe","tipCents" to "propina","method" to "método","splitType" to "reparto","tenderTypeId" to "tipo de pago","tenderRevision" to "versión del tipo de pago","totalDeLaOrden" to "total de la orden","saldoDeLaMesa" to "saldo de la mesa")
                for((key,title) in listOf("camposDiferentes" to "Campos distintos","camposNoComprobados" to "Sin confirmar")) {
                    val fields=(obj[key] as? JsonArray)?.mapNotNull {(it as? JsonPrimitive)?.content?.let(labels::get)} ?: emptyList()
                    if(fields.isNotEmpty()) lines.add("$title: ${fields.joinToString()}")
                }
                // Q16: la venta sin red cuyo precio cambió en el servidor (mismo texto en iOS).
                val alCobrar=cents("totalAlCobrarCents");val enServidor=cents("totalEnElServidorCents")
                if(alCobrar!=null && enServidor!=null) lines.add("Total al cobrar ${money(alCobrar)} · total en el servidor ${money(enServidor)}")
                // Mesa cobrada sin red (19-oct): el servidor la valoró con su precio de hoy (mismo texto en iOS).
                val saldoEsperado=cents("saldoEsperadoCents");val saldoServidor=cents("saldoEnElServidorCents")
                if(saldoEsperado!=null && saldoServidor!=null) lines.add("Saldo esperado ${money(saldoEsperado)} · saldo en el servidor ${money(saldoServidor)}")
                cents("cobradoDeMasCents")?.let { lines.add("Se cobró de más: ${money(it)}") }
            }
        }
        result("Primer resultado",row.q15InitialResultJson)
        if(row.q15ResultJson!=row.q15InitialResultJson) result("Último resultado",row.q15ResultJson)
        return lines
    }
}
