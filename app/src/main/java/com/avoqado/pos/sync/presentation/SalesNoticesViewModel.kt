package com.avoqado.pos.sync.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.tables.data.TableSession
import com.avoqado.pos.sync.data.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.serialization.json.*
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.payment.data.PaymentSyncService

@HiltViewModel
class SalesNoticesViewModel @Inject constructor(
    private val store: SalesNoticeStore, private val storage: SecureStorage,
    private val roles: RoleManager, private val session: TableSession,
    private val payments: PaymentSyncService, private val connectivity: ConnectivityMonitor,
): ViewModel() {
    private data class Context(val venue:String?,val actor:String?,val role:String,val epoch:Long)
    private fun current()=Context(storage.venueId,storage.userId,roles.role,session.contextoActual())
    private var context=current()
    private var generation=0L
    private var acknowledgementGeneration=0L
    private var cursor:SalesNoticeCursor?=null
    private var readFailed=false
    private var observationJob:Job?=null
    private var observationGeneration=0L
    private var observedContext:Context?=null
    private var observedSummaryOnly:Boolean?=null
    private var requestedSummaryOnly:Boolean?=null
    private val _rows=MutableStateFlow<List<SalesNoticeSnapshot>>(emptyList()); val rows=_rows.asStateFlow()
    private val _total=MutableStateFlow(0); val total=_total.asStateFlow()
    private val _summary=MutableStateFlow<SalesNoticeSummary?>(null); val summary=_summary.asStateFlow()
    private val _globalPendingCount=MutableStateFlow(0); val globalPendingCount=_globalPendingCount.asStateFlow()
    private val _selected=MutableStateFlow<SalesNoticeSnapshot?>(null); val selected=_selected.asStateFlow()
    private val _query=MutableStateFlow(""); val query=_query.asStateFlow()
    private val _message=MutableStateFlow<String?>(null); val message=_message.asStateFlow()
    private val _acknowledging=MutableStateFlow(false); val acknowledging=_acknowledging.asStateFlow()
    private val _loading=MutableStateFlow(false); val loading=_loading.asStateFlow()
    val canRead get()=context.venue!=null && current()==context && roles.canResolveQuarantine
    val opensPendingSales get()=roles.canAccessTransactions && canRead
    val bannerActionLabel get()=salesNoticeBannerActionLabel(opensPendingSales)
    private val _courtesy=MutableStateFlow(CourtesyReviewUi());val courtesy=_courtesy.asStateFlow()
    val courtesyOnline=combine(connectivity.isConnected,connectivity.isServerReachable) { connected,reachable -> connected && reachable }
        .stateIn(viewModelScope,SharingStarted.WhileSubscribed(),connectivity.isFullyConnected)
    private var courtesyProposal:PaymentSyncService.PropuestaRevisionCortesia?=null
    private var cancelledProposal:PaymentSyncService.PropuestaRevisionCancelada?=null
    private var courtesyJob:Job?=null
    private var courtesyGeneration=0L
    private var courtesySourceVersion:SalesNoticeVersion?=null
    private var directedCourtesyId:String?=null
    fun openCourtesyPayment(id:String) {
        adoptContext();if(!canRead || _courtesy.value.busy) return
        directedCourtesyId=id;_query.value=id;page(false)
    }
    val canPreviewCourtesy get()=canRead && courtesyPermission("orders:read")
    val canConfirmCourtesy get()=canPreviewCourtesy && courtesyPermission("orders:comp")
    val canConfirmCancelledReview get()=canPreviewCourtesy && cancelledProposal!=null
    private fun courtesyPermission(permission:String)=if(storage.venuePermissions.isEmpty()) roles.canResolveQuarantine
        else roles.hasVenuePermission(permission)
    private fun discardCourtesy() {
        courtesyGeneration++;courtesyJob?.cancel();courtesyJob=null;courtesyProposal=null;cancelledProposal=null
        courtesySourceVersion=null;_courtesy.value=CourtesyReviewUi()
    }
    fun closeCourtesy() { if(!_courtesy.value.confirming) discardCourtesy() }
    fun leaveCourtesyView() { discardCourtesy() } // Sólo UI; no borra ni revierte fuente/CAS/POST.
    private fun courtesyContextCurrent(snapshot:Context,token:Long)=
        token==courtesyGeneration && snapshot==context && current()==snapshot && canPreviewCourtesy
    private fun courtesyCurrent(snapshot:Context,token:Long,key:SalesNoticeKey):Boolean {
        if(token!=courtesyGeneration) return false
        if(!courtesyContextCurrent(snapshot,token) ||
            (_courtesy.value.confirming && _courtesy.value.kind==CourtesyReviewKind.FINANCIAL && !canConfirmCourtesy) ||
            _selected.value?.let { it.key==key && it.version==courtesySourceVersion }!=true) {
            discardCourtesy();return false
        }
        return true
    }
    fun reviewSelectedCourtesy() {
        adoptContext();val row=_selected.value ?: return
        if(_courtesy.value.busy || !canPreviewCourtesy || !courtesyReviewCandidate(row)) return
        if(!connectivity.isFullyConnected) return
        val snapshot=context;val key=row.key;val token=++courtesyGeneration
        courtesyProposal=null;cancelledProposal=null;courtesySourceVersion=row.version
        _courtesy.value=CourtesyReviewUi(key=key,busy=true)
        courtesyJob=viewModelScope.launch {
            try {
                if(!courtesyCurrent(snapshot,token,key)) return@launch
                val cancelled=payments.prepararRevisionCancelada(row.id)
                if(!courtesyCurrent(snapshot,token,key)) return@launch
                if(cancelled!=null) {
                    cancelledProposal=cancelled
                    _courtesy.value=CourtesyReviewUi(key=key,ready=true,busy=true,kind=CourtesyReviewKind.CANCELLED_ZERO)
                    return@launch
                }
                val proposal=payments.prepararRevisionCortesia(row.id)
                if(!courtesyCurrent(snapshot,token,key)) return@launch
                if(proposal==null) {
                    _courtesy.value=_courtesy.value.copy(error="No se pudo comprobar esta cortesía. El intento sigue guardado.")
                    return@launch
                }
                // Sólo proyección visual por externalId; la identidad financiera y la fuente completa las valida el servicio.
                val items=(Json.parseToJsonElement(requireNotNull(proposal.original.orderRequestJson)).jsonObject["items"] as JsonArray)
                    .map { it.jsonObject }
                val reasons=items.associate { requireNotNull(it["externalId"]?.jsonPrimitive?.contentOrNull) to
                    requireNotNull(it["cortesiaReason"]?.jsonPrimitive?.contentOrNull) }
                val names=items.mapNotNull { item ->
                    val id=item["externalId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val label=(item["productName"] ?: item["name"])?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                    label?.let { id to it }
                }.toMap()
                courtesyProposal=proposal
                _courtesy.value=CourtesyReviewUi(key=key,snapshot=proposal.expected,
                    originalAmountCents=proposal.original.amountCents,reasons=reasons,names=names,ready=true,busy=true)
            } catch(e:CancellationException) { throw e }
            catch(_:Exception) {
                if(courtesyCurrent(snapshot,token,key)) {
                    courtesyProposal=null
                    _courtesy.value=_courtesy.value.copy(ready=false,error="No se pudo consultar la cuenta. El intento sigue guardado.")
                }
            } finally {
                if(token==courtesyGeneration) { _courtesy.value=_courtesy.value.copy(busy=false,confirming=false);courtesyJob=null }
            }
        }
    }
    fun confirmSelectedCancelledReview() {
        val proposal=cancelledProposal ?: return;val key=_courtesy.value.key ?: return
        val snapshot=context;val token=courtesyGeneration
        if(_courtesy.value.kind!=CourtesyReviewKind.CANCELLED_ZERO || _courtesy.value.busy || !_courtesy.value.ready ||
            !canConfirmCancelledReview || !connectivity.isFullyConnected || !courtesyCurrent(snapshot,token,key)) return
        _courtesy.value=_courtesy.value.copy(busy=true,confirming=true,error=null)
        courtesyJob=viewModelScope.launch {
            try {
                if(!courtesyCurrent(snapshot,token,key)) return@launch
                val saved=payments.confirmarRevisionCancelada(proposal)
                // La notificación del CAS propio puede haber retirado esta selección de Pendientes.
                if(!courtesyContextCurrent(snapshot,token)) { if(token==courtesyGeneration) discardCourtesy();return@launch }
                if(saved) {
                    cancelledProposal=null;courtesySourceVersion=null;_courtesy.value=CourtesyReviewUi()
                    _message.value="Revisión guardada en el historial."
                    refresh()
                } else if(courtesyCurrent(snapshot,token,key)) {
                    cancelledProposal=null
                    _courtesy.value=_courtesy.value.copy(ready=false,
                        error="No se pudo comprobar la revisión. El intento sigue conservado; no vuelvas a cobrar.")
                }
            } catch(e:CancellationException) { throw e }
            catch(_:Exception) {
                if(courtesyCurrent(snapshot,token,key)) {
                    cancelledProposal=null
                    _courtesy.value=_courtesy.value.copy(ready=false,
                        error="No se pudo comprobar la revisión. El intento sigue conservado; no vuelvas a cobrar.")
                }
            } finally {
                if(token==courtesyGeneration) { _courtesy.value=_courtesy.value.copy(busy=false,confirming=false);courtesyJob=null }
            }
        }
    }
    fun confirmSelectedCourtesy() {
        val proposal=courtesyProposal ?: return;val key=_courtesy.value.key ?: return
        val snapshot=context;val token=courtesyGeneration
        if(_courtesy.value.busy || !_courtesy.value.ready || !canConfirmCourtesy || !connectivity.isFullyConnected || !courtesyCurrent(snapshot,token,key)) return
        _courtesy.value=_courtesy.value.copy(busy=true,confirming=true,error=null)
        courtesyJob=viewModelScope.launch {
            try {
                if(!courtesyCurrent(snapshot,token,key)) return@launch
                val enabled=payments.confirmarRevisionCortesia(proposal)
                if(!courtesyCurrent(snapshot,token,key)) return@launch
                if(enabled) {
                    // Habilitada no significa cobrada. El drenado/conciliación existente tiene la última palabra.
                    courtesyProposal=null;courtesySourceVersion=null;_courtesy.value=CourtesyReviewUi()
                    _message.value="Cortesía aplicada. Comprobando el intento original…"
                    refresh()
                } else {
                    courtesyProposal=null
                    _courtesy.value=_courtesy.value.copy(ready=false,error="La revisión cambió o no pudo completarse. Vuelve a abrirla; el intento sigue guardado.")
                }
            } catch(e:CancellationException) { throw e }
            catch(_:Exception) {
                if(courtesyCurrent(snapshot,token,key)) {
                    courtesyProposal=null
                    _courtesy.value=_courtesy.value.copy(ready=false,error="No se pudo comprobar la respuesta. El intento sigue guardado; vuelve a revisar la cuenta.")
                }
            } finally {
                if(token==courtesyGeneration) { _courtesy.value=_courtesy.value.copy(busy=false,confirming=false);courtesyJob=null }
            }
        }
    }
    fun changes()=store.changes()
    suspend fun observeChanges() { requestedSummaryOnly=false;adoptContext();startObservation() }
    suspend fun observeAlertChanges() { requestedSummaryOnly=true;adoptContext();startObservation() }
    suspend fun observeContextChanges() {
        session.contextEpoch.collect { emitted ->
            yield() // Read after the producer has finished changing its context.
            val actual=current()
            if(actual.epoch==emitted && actual!=context) {
                when(requestedSummaryOnly) { true->refreshAlertSummary();false->refresh();null->Unit }
            }
        }
    }
    fun presentation(isTablet:Boolean)=SalesNoticePresentation(
        _message.value,
        if(readFailed) "De este aparato" else "De este aparato · ${_total.value} ${if(_total.value==1) "operación" else "operaciones"}",
        if(readFailed || _rows.value.isNotEmpty()) null else if(_loading.value) "Leyendo pendientes…" else "No hay operaciones en esta vista.",
        if(_summary.value==null && _globalPendingCount.value==0) "Pendientes" else "Pendientes · ${_globalPendingCount.value}",
    )
    private fun clear() {
        directedCourtesyId=null;discardCourtesy()
        _rows.value=emptyList();_total.value=0;_summary.value=null;_globalPendingCount.value=0
        _selected.value=null;cursor=null;readFailed=false
    }
    private fun valid(snapshot:Context,version:Long,requiresReader:Boolean=true):Boolean {
        if(version!=generation) return false
        if(snapshot!=context || current()!=snapshot || (requiresReader && !roles.canResolveQuarantine)) { clear();return false }
        return true
    }
    private fun failRead() { readFailed=true;_summary.value=null;_message.value=SALES_NOTICE_READ_ERROR }
    private fun readSucceeded() {
        if(readFailed && _message.value==SALES_NOTICE_READ_ERROR) _message.value=null
        readFailed=false
    }
    private fun stopObservation() {
        observationGeneration++;observationJob?.cancel();observationJob=null;observedContext=null;observedSummaryOnly=null
    }
    private fun observationCurrent(snapshot:Context,token:Long,summaryOnly:Boolean)=
        token==observationGeneration && context==snapshot && current()==snapshot && (summaryOnly || roles.canResolveQuarantine)
    private fun startObservation() {
        val summaryOnly=requestedSummaryOnly ?: return
        val allowed=if(summaryOnly) context.venue!=null && !context.actor.isNullOrBlank() && current()==context else canRead
        if(!allowed) { stopObservation();return }
        if(observationJob?.isActive==true && observedContext==context && observedSummaryOnly==summaryOnly) return
        stopObservation();val snapshot=context;val token=++observationGeneration
        observedContext=snapshot;observedSummaryOnly=summaryOnly
        val job=viewModelScope.launch(start=CoroutineStart.LAZY) {
            try {
                changes().collect {
                    if(observationCurrent(snapshot,token,summaryOnly)) {
                        if(summaryOnly) refreshAlertSummary() else refresh()
                    } else if(token==observationGeneration) { stopObservation();clear() }
                }
            } catch(e:CancellationException) { throw e }
            catch(_:Exception) {
                if(observationCurrent(snapshot,token,summaryOnly)) {
                    observationGeneration++;observationJob=null;observedContext=null;observedSummaryOnly=null
                    generation++;_loading.value=false;failRead()
                }
            }
        }
        observationJob=job;job.start()
    }
    private fun adoptContext() {
        val now=current()
        if(now!=context) {
            stopObservation();context=now;clear();_query.value="";_message.value=null
            acknowledgementGeneration++;_acknowledging.value=false
        }
    }
    fun refresh() {
        adoptContext()
        if(requestedSummaryOnly!=null) { requestedSummaryOnly=false;startObservation() }
        page(false)
    }
    /** Same local aggregate already used by the notice; never fetches rows or grants review access. */
    fun refreshAlertSummary() {
        adoptContext()
        if(requestedSummaryOnly!=null) { requestedSummaryOnly=true;startObservation() }
        val version=++generation;val snapshot=context
        val venue=snapshot.venue?.takeIf { !snapshot.actor.isNullOrBlank() && current()==snapshot } ?: run { clear();return }
        viewModelScope.launch {
            try {
                if(!valid(snapshot,version,false)) return@launch
                val summary=store.summary(venue)
                if(!valid(snapshot,version,false)) return@launch
                readSucceeded();_summary.value=summary;_globalPendingCount.value=summary.pendingCount
            } catch(e:CancellationException) { throw e }
            catch(_:Exception) { if(valid(snapshot,version,false)) failRead() }
        }
    }
    fun loadMore() { if(!_loading.value && cursor!=null && _rows.value.size<_total.value) page(true) }
    fun search(value:String) { _query.value=value;page(false) }
    fun select(row:SalesNoticeSnapshot?) {
        if(row?.key!=_selected.value?.key || row?.version!=_selected.value?.version) discardCourtesy()
        _selected.value=if(canRead) row?.let { r->_rows.value.firstOrNull { it.key==r.key && it.version==r.version } } else null
    }
    private fun page(append:Boolean) {
        val version=++generation
        if(!canRead) { clear();_loading.value=false;_message.value="Un gerente debe revisar este pago.";return }
        val snapshot=context;val venue=requireNotNull(snapshot.venue);val search=_query.value
        val previous=if(append) _rows.value else emptyList();val after=if(append) cursor else null
        _loading.value=true
        viewModelScope.launch {
            try {
                if(!valid(snapshot,version)) return@launch
                val page=store.page(venue,search,after)
                if(!valid(snapshot,version)) return@launch
                val summary=store.summary(venue)
                if(!valid(snapshot,version)) return@launch
                val selectedKey=_selected.value?.key
                _rows.value=(previous+page.rows).distinctBy { it.key };_total.value=page.total
                cursor=page.nextCursor;readSucceeded();_summary.value=summary;_globalPendingCount.value=summary.pendingCount
                val directed=directedCourtesyId
                _selected.value=if(directed==null) _rows.value.firstOrNull { it.key==selectedKey }
                    else _rows.value.singleOrNull { it.origin=="PAYMENT" && it.id==directed && it.venueId==venue }
                if(directed!=null) {
                    directedCourtesyId=null
                    if(_selected.value==null) _message.value="No se encontró este intento en Pendientes. Sus datos siguen guardados."
                    else if(canPreviewCourtesy && connectivity.isFullyConnected && courtesyReviewCandidate(requireNotNull(_selected.value))) reviewSelectedCourtesy()
                }
                // El propio CAS puede notificar PENDING antes de que confirmar llegue al despertar existente.
                // Durante ese await el servicio conserva su dueño; al regresar se descarta cualquier resultado visual obsoleto.
                if(!_courtesy.value.confirming && _courtesy.value.key!=null &&
                    _selected.value?.version!=courtesySourceVersion) discardCourtesy()
            } catch(e:CancellationException) { throw e }
            catch(_:Exception) { if(valid(snapshot,version)) failRead() }
            finally { if(version==generation) _loading.value=false }
        }
    }
    fun acknowledgeSelected() {
        val row=_selected.value ?: return
        if(_acknowledging.value || !canRead || !row.eligible() || row.acknowledgement!=null ||
            _rows.value.none { it.key==row.key && it.version==row.version }) return
        val snapshot=context;val version=generation
        val actor=snapshot.actor?.takeIf { it.isNotBlank() } ?: return
        val acknowledgement=++acknowledgementGeneration
        _acknowledging.value=true
        viewModelScope.launch {
            try {
                if(!valid(snapshot,version)) return@launch
                val changed=store.acknowledge(row,actor,System.currentTimeMillis())
                if(!valid(snapshot,version)) return@launch
                _message.value=if(changed) {
                    if(row.registeredResult()!=null) "Aviso entendido. Los datos del pago se conservan en Ventas."
                    else "Aviso entendido. La operación sigue pendiente en Ventas."
                } else
                    "La incidencia cambió. El aviso sigue pendiente; vuelve a abrir su detalle."
                if(changed) refresh()
            } catch(e:CancellationException) { throw e }
            catch(_:Exception) { if(valid(snapshot,version)) _message.value="No se pudo guardar Entendido. El aviso sigue pendiente." }
            finally { if(acknowledgement==acknowledgementGeneration) _acknowledging.value=false }
        }
    }
}

/** Presentation count only. The financial reconciliation count is never changed. */
fun salesNoticeBannerCount(summary:SalesNoticeSummary?, reservations:Int?, fallback:Int, payLaterCount:Int=0):Int {
    if(summary==null || reservations==null) return fallback
    return summary.alertContributions.entries.sumOf { (group,count)->
        (count-(summary.acknowledgedContributions[group] ?: 0)).coerceAtLeast(0)
    }+summary.untouchedQ15+summary.untouchedRejected+reservations+payLaterCount
}

/** The banner's existing route, shared by the tablet and phone layouts. */
fun openSalesNoticeBanner(opensPendingSales:Boolean, transactionsRegistered:Boolean, payLaterCount:Int=0,
    onPendingSales:()->Unit, onQuarantine:()->Unit) {
    if(payLaterCount==0 && opensPendingSales && transactionsRegistered) onPendingSales() else onQuarantine()
}

fun salesNoticeBannerActionLabel(opensPendingSales:Boolean, payLaterCount:Int=0):String =
    if(payLaterCount==0 && opensPendingSales) "Ver ventas" else "Revisar"

/** Text actually rendered by the list; keeps its error/empty decisions testable without a device. */
data class SalesNoticePresentation(val message:String?,val countLabel:String,val emptyLabel:String?,val pendingLabel:String)

/** Unknown after a failed local read; the lifecycle owner explicitly starts the next subscription. */
suspend fun collectSalesNoticeReservations(source:kotlinx.coroutines.flow.Flow<Int>,publish:(Int?)->Unit) {
    try { source.collect { publish(it) } }
    catch(e:CancellationException) { throw e }
    catch(_:Exception) { publish(null) }
}

private const val SALES_NOTICE_READ_ERROR="No se pudieron leer los pendientes. Sus datos siguen guardados."

/** Sólo estado efímero de revisión; nunca representa un pago conciliado ni se persiste. */
data class CourtesyReviewUi(
    val key:SalesNoticeKey?=null,val snapshot:JsonObject?=null,val originalAmountCents:Int?=null,
    val reasons:Map<String,String> = emptyMap(),val names:Map<String,String> = emptyMap(),
    val ready:Boolean=false,val busy:Boolean=false,val confirming:Boolean=false,val error:String?=null,
    val kind:CourtesyReviewKind=CourtesyReviewKind.FINANCIAL,
)
enum class CourtesyReviewKind { FINANCIAL, CANCELLED_ZERO }
/** Prefiltro visual. preparar/confirmar releen y validan la fuente íntegra; esto jamás autoriza una escritura. */
internal fun courtesyReviewCandidate(row:SalesNoticeSnapshot)=row.origin=="PAYMENT" && row.type=="ORDER" &&
    row.state=="FAILED" && row.reason==null && row.originalAmountCents==0 && row.originalTipCents==0 &&
    row.originalMethod=="CASH" && row.tenderTypeId==null && row.tenderRevision==null &&
    !row.capture.isNullOrBlank() && !row.body.isNullOrBlank()
