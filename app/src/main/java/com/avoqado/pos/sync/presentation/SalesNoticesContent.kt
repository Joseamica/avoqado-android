package com.avoqado.pos.sync.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.avoqado.pos.designsystem.components.AvoqadoFullscreenHeader
import com.avoqado.pos.designsystem.components.SearchPillField
import com.avoqado.pos.designsystem.components.AvoqadoDialog
import com.avoqado.pos.designsystem.components.PrimaryButton
import kotlinx.serialization.json.*
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.sync.data.SalesNoticeSnapshot
import com.avoqado.pos.payment.data.ConciliacionDelCobro
import java.util.Locale

@Composable
fun SalesNoticesContent(
    isTablet:Boolean,
    onOpenLegacyReview:()->Unit,
    viewModel:SalesNoticesViewModel=hiltViewModel(),
) {
    val rows by viewModel.rows.collectAsState();val total by viewModel.total.collectAsState()
    val selected by viewModel.selected.collectAsState();val query by viewModel.query.collectAsState()
    val loading by viewModel.loading.collectAsState();val message by viewModel.message.collectAsState()
    val acknowledging by viewModel.acknowledging.collectAsState()
    val summary by viewModel.summary.collectAsState()
    val courtesy by viewModel.courtesy.collectAsState();val online by viewModel.courtesyOnline.collectAsState()
    val presentation=remember(summary,message,total,selected,loading,rows,isTablet) { viewModel.presentation(isTablet) }
    LifecycleResumeEffect(viewModel) { viewModel.refresh();onPauseOrDispose { viewModel.leaveCourtesyView() } }
    val permitted=viewModel.canRead
    val visible=if(permitted) rows else emptyList()
    val selection=if(permitted) selected else null
    Column(Modifier.fillMaxSize()) {
        if(permitted) presentation.message?.let { Text(it,Modifier.padding(AvoqadoTheme.spacing.lg),style=MaterialTheme.typography.bodyMedium) }
        if(!permitted) Text("Un gerente debe revisar este pago.",Modifier.padding(AvoqadoTheme.spacing.lg))
        else if(isTablet) Row(Modifier.weight(1f)) {
            SalesNoticesList(visible,total,query,loading,presentation,onOpenLegacyReview,viewModel::search,viewModel::select,viewModel::loadMore,
                Modifier.weight(0.4f).fillMaxHeight())
            VerticalDivider()
            SalesNoticeDetail(selection,acknowledging,false,{ viewModel.select(null) },viewModel::acknowledgeSelected,
                onOpenLegacyReview,viewModel.canPreviewCourtesy,online,courtesy.busy,viewModel::reviewSelectedCourtesy,Modifier.weight(0.6f).fillMaxHeight())
        } else if(selection!=null) {
            SalesNoticeDetail(selection,acknowledging,true,{ viewModel.select(null) },viewModel::acknowledgeSelected,
                onOpenLegacyReview,viewModel.canPreviewCourtesy,online,courtesy.busy,viewModel::reviewSelectedCourtesy,Modifier.weight(1f))
        } else {
            SalesNoticesList(visible,total,query,loading,presentation,onOpenLegacyReview,viewModel::search,viewModel::select,viewModel::loadMore,Modifier.weight(1f))
        }
    }
    if(permitted && courtesy.key!=null && courtesy.key==selection?.key) {
        CourtesyReviewDialog(courtesy,online,viewModel.canConfirmCourtesy,viewModel::closeCourtesy,viewModel::confirmSelectedCourtesy)
    }
}

@Composable
private fun SalesNoticesList(rows:List<SalesNoticeSnapshot>,total:Int,query:String,loading:Boolean,presentation:SalesNoticePresentation,
    onOpenLegacyReview:()->Unit,onSearch:(String)->Unit,onSelect:(SalesNoticeSnapshot)->Unit,onMore:()->Unit,modifier:Modifier) {
    Column(modifier) {
        SearchPillField(query=query,onQueryChange=onSearch,placeholder="Buscar folio o cuenta",height=48.dp,
            modifier=Modifier.fillMaxWidth().padding(horizontal=AvoqadoTheme.spacing.lg,vertical=AvoqadoTheme.spacing.sm))
        Text(presentation.countLabel,
            Modifier.padding(horizontal=AvoqadoTheme.spacing.lg,vertical=AvoqadoTheme.spacing.sm),
            style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(rows,key={it.origin+":"+it.id}) { row ->
                Column(Modifier.fillMaxWidth().clickable { onSelect(row) }.heightIn(min=48.dp)
                    .padding(AvoqadoTheme.spacing.lg),verticalArrangement=Arrangement.spacedBy(AvoqadoTheme.spacing.xs)) {
                    Text(salesNoticeTitle(row),style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold)
                    Text(salesNoticeMoney(row.originalAmountCents),style=MaterialTheme.typography.titleLarge)
                    Text("${salesNoticeDate(row.originalCreatedAt)} · ${row.id.take(8)}",style=MaterialTheme.typography.bodySmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(salesNoticeStatus(row),style=MaterialTheme.typography.bodyMedium)
                    if(row.acknowledgement!=null) Text("Aviso entendido · sigue pendiente",style=MaterialTheme.typography.bodySmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
            presentation.emptyLabel?.let { label -> item { Text(label,Modifier.padding(AvoqadoTheme.spacing.xxl)) } }
            if(rows.size<total) item {
                Text("Mostrando ${rows.size} de $total",Modifier.padding(AvoqadoTheme.spacing.lg),style=MaterialTheme.typography.bodySmall)
                TextButton(onClick=onMore,enabled=!loading,modifier=Modifier.heightIn(min=48.dp)) { Text("Cargar más") }
            }
            item { TextButton(onClick=onOpenLegacyReview,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text("Otras operaciones y revisiones anteriores") } }
        }
    }
}

@Composable
private fun SalesNoticeDetail(row:SalesNoticeSnapshot?,acknowledging:Boolean,mobile:Boolean,
    onClose:()->Unit,onAcknowledge:()->Unit,onOpenLegacyReview:()->Unit,
    canPreviewCourtesy:Boolean,online:Boolean,courtesyBusy:Boolean,onReviewCourtesy:()->Unit,modifier:Modifier) {
    val clipboard=LocalClipboardManager.current
    Column(modifier) {
        if(mobile) AvoqadoFullscreenHeader(title="Detalle",onNav=onClose,showDivider=true)
        if(row==null) Text("Selecciona una operación para ver su detalle.",Modifier.padding(AvoqadoTheme.spacing.xxl))
        else Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(AvoqadoTheme.spacing.xxl),
            verticalArrangement=Arrangement.spacedBy(AvoqadoTheme.spacing.lg)) {
            Text(salesNoticeTitle(row),style=MaterialTheme.typography.headlineSmall)
            Text(salesNoticeMoney(row.originalAmountCents),style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.SemiBold)
            Text("Captura original del aparato",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(salesNoticeStatus(row),style=MaterialTheme.typography.titleSmall)
            Text(salesNoticeReason(row),style=MaterialTheme.typography.bodyLarge)
            HorizontalDivider()
            Text("Folio ${row.id}",style=MaterialTheme.typography.bodyMedium)
            row.reference?.takeIf { it!=row.id }?.let { Text("Cuenta $it",style=MaterialTheme.typography.bodyMedium) }
            Text(salesNoticeDate(row.originalCreatedAt),style=MaterialTheme.typography.bodyMedium)
            Text("Importe del servidor: Sin confirmar",style=MaterialTheme.typography.bodyMedium)
            if(row.acknowledgement!=null) Text("Aviso entendido · sigue pendiente",style=MaterialTheme.typography.bodyMedium)
            if(salesNoticeCourtesy(row) && row.origin=="PAYMENT" && row.type=="ORDER" && row.state=="FAILED") {
                PrimaryButton(text="Revisar cortesía",onClick=onReviewCourtesy,fullWidth=true,
                    enabled=canPreviewCourtesy && online && !courtesyBusy && courtesyReviewCandidate(row))
                val unavailable=when {
                    !online -> COURTESY_NEEDS_CONNECTION
                    !canPreviewCourtesy -> "Un gerente con permiso de cortesía debe revisar esta cuenta."
                    !courtesyReviewCandidate(row) -> "La captura original está incompleta. El intento sigue guardado."
                    else -> null
                }
                unavailable?.let { Text(it,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if(row.eligible() && row.acknowledgement==null) {
                Button(onClick=onAcknowledge,enabled=!acknowledging,shape=RoundedCornerShape(50),modifier=Modifier.heightIn(min=48.dp)) {
                    Text(if(acknowledging) "Guardando…" else "Entendido")
                }
                Text("Entendido quita este aviso de Inicio en este aparato. La operación sigue pendiente en Ventas.",
                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick={clipboard.setText(AnnotatedString(row.id))},shape=RoundedCornerShape(50),modifier=Modifier.heightIn(min=48.dp)) { Text("Copiar folio") }
            if(!salesNoticeCourtesy(row) && salesNoticeStatus(row)!="Por sincronizar") {
                OutlinedButton(onClick=onOpenLegacyReview,shape=RoundedCornerShape(50),modifier=Modifier.heightIn(min=48.dp)) { Text("Abrir revisión") }
            }
        }
    }
}

private fun salesNoticeCourtesy(row:SalesNoticeSnapshot)=row.originalAmountCents==0 && row.originalMethod=="CASH"
private fun salesNoticeTitle(row:SalesNoticeSnapshot)=if(salesNoticeCourtesy(row)) "Intento de cortesía" else
    row.originalMethod?.let { com.avoqado.pos.transactions.data.model.PaymentMethodDisplay.label(it) } ?: "Cobro sin confirmar"
private fun salesNoticeStatus(row:SalesNoticeSnapshot)=when {
    row.state in setOf("PENDING","SYNCING","HELD") && row.reason==null && row.originalMethod=="CASH" &&
        row.tenderTypeId==null && row.tenderRevision==null -> "Por sincronizar"
    salesNoticeCourtesy(row) -> "Cortesía no registrada"
    else -> "Sin confirmar"
}
private fun salesNoticeReason(row:SalesNoticeSnapshot)=when {
    salesNoticeStatus(row)=="Por sincronizar" -> "El efectivo está guardado en este aparato. Se registrará al recuperar la conexión."
    salesNoticeCourtesy(row) -> "El servidor no confirmó la cortesía original. El intento se conserva para revisión; no vuelvas a cobrar."
    row.reason==ConciliacionDelCobro.DIFERENCIA -> "El registro del pago cambió al sincronizar."
    row.reason==ConciliacionDelCobro.CAPTURA_SIN_EVIDENCIA -> "Falta la evidencia original de este pago."
    row.reason==ConciliacionDelCobro.PRICE_PROOF_PENDING -> "El pago espera comprobar el precio original."
    else -> "Todavía no podemos comprobar el resultado de este pago. Sus datos siguen guardados."
}
private fun salesNoticeMoney(cents:Int?)=cents?.let { com.avoqado.pos.core.util.formatMoneyFromCents(it) } ?: "Sin confirmar"
private fun salesNoticeDate(epoch:Long)=com.avoqado.pos.core.util.VenueTimeZone.at(java.time.Instant.ofEpochMilli(epoch))
    .format(java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm",Locale("es","MX")))


private const val COURTESY_NEEDS_CONNECTION="Necesitas conexión para corregir esta cuenta. El intento está guardado"

@Composable
private fun CourtesyReviewDialog(ui:CourtesyReviewUi,online:Boolean,authorized:Boolean,onBack:()->Unit,onConfirm:()->Unit) {
    AvoqadoDialog(title="Revisar cortesía",onDismiss=onBack,dismissOnClickOutside=!ui.confirming,
        actionButton={
            Column(verticalArrangement=Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                OutlinedButton(onClick=onBack,enabled=!ui.confirming,shape=RoundedCornerShape(50),
                    modifier=Modifier.fillMaxWidth().height(AvoqadoTheme.dimensions.buttonLarge)) { Text("Volver") }
                PrimaryButton(text="Aplicar cortesía y reintentar",onClick=onConfirm,fullWidth=true,destructive=true,
                    enabled=ui.ready && online && authorized && !ui.busy,isLoading=ui.busy)
            }
        },
    ) {
        // Único scroll del diálogo: el encabezado/X y el grupo de acciones quedan fuera.
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement=Arrangement.spacedBy(AvoqadoTheme.spacing.lg)) {
            Text("Captura original · ${salesNoticeMoney(ui.originalAmountCents)}",style=MaterialTheme.typography.titleMedium)
            ui.snapshot?.let { snapshot ->
                Text("Cuenta ${snapshot.text("orderNumber") ?: "Sin confirmar"}",style=MaterialTheme.typography.titleMedium)
                Text("Importe actual · ${courtesyMoney(snapshot["total"])}",style=MaterialTheme.typography.bodyLarge)
                HorizontalDivider()
                (snapshot["lines"] as? JsonArray)?.forEachIndexed { index,value ->
                    val line=value.jsonObject;val externalId=line.text("externalId").orEmpty()
                    Column(verticalArrangement=Arrangement.spacedBy(AvoqadoTheme.spacing.xs)) {
                        Text("${line["quantity"]?.jsonPrimitive?.intOrNull ?: "—"} × ${ui.names[externalId] ?: "Artículo original ${index+1}"}",
                            style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold)
                        Text("Precio unitario · ${courtesyMoney(line["unitPrice"])}",style=MaterialTheme.typography.bodyMedium)
                        (line["modifiers"] as? JsonArray)?.forEach { modifier ->
                            val extra=modifier.jsonObject
                            Text("Extra ×${extra["quantity"]?.jsonPrimitive?.intOrNull ?: "—"} · ${courtesyMoney(extra["price"])} c/u",
                                style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("Motivo original · ${ui.reasons[externalId] ?: "Sin confirmar"}",style=MaterialTheme.typography.bodyMedium)
                        Text("Importe actual · ${courtesyMoney(line["total"])}",style=MaterialTheme.typography.bodyMedium)
                    }
                    HorizontalDivider()
                }
                Text("Resultado previsto · ${com.avoqado.pos.core.util.formatMoney(0.0)}",style=MaterialTheme.typography.titleMedium)
                Text("Se corregirá esta cuenta y se comprobará el mismo intento original. No vuelvas a cobrar.",
                    style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(ui.busy) Text(if(ui.snapshot==null) "Consultando cuenta…" else "Aplicando cortesía…",style=MaterialTheme.typography.bodyMedium)
            if(!online) Text(COURTESY_NEEDS_CONNECTION,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(!authorized) Text("Un gerente con permiso de cortesía debe revisar esta cuenta.",style=MaterialTheme.typography.bodyMedium,
                color=MaterialTheme.colorScheme.error)
            ui.error?.let { Text(it,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.error) }
        }
    }
}
private fun JsonObject.text(key:String)=(get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun courtesyMoney(value:JsonElement?)=(value as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
    ?.takeIf { it.isFinite() }?.let { com.avoqado.pos.core.util.formatMoney(it) } ?: "Sin confirmar"
