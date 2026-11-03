package com.avoqado.pos.giftcards.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.avoqado.pos.core.util.formatMoney
import com.avoqado.pos.designsystem.components.*
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.giftcards.domain.TextosDeTarjetas
import com.avoqado.pos.transactions.data.model.Transaction
import com.avoqado.pos.transactions.presentation.IssueRefundSheet
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun CuentaDeTarjetasScreen(orderId: String, onSalir: () -> Unit) {
    ResultadoDeVentaScreen(llave=null,orderId=orderId,onSalir=onSalir)
}

@Composable
internal fun GestionDeCuentaDeTarjetas(orderId: String, onActualizada: () -> Unit,
    viewModel: CuentaDeTarjetasViewModel = hiltViewModel(key="cuenta-tarjetas-$orderId"),
) {
    val cuenta by viewModel.cuenta.collectAsState()
    val pagos by viewModel.pagosPorDevolver.collectAsState()
    val aviso by viewModel.aviso.collectAsState()
    val ocupado by viewModel.ocupado.collectAsState()
    val conectado by viewModel.conectado.collectAsState()
    val servidor by viewModel.servidor.collectAsState()
    val exito by viewModel.exito.collectAsState()
    val pinActivo by viewModel.ajustes.managerPinOverrideEnabled.collectAsState()
    val scope=rememberCoroutineScope()
    val montada=remember(orderId) { AtomicBoolean(true) }
    var dialogo by remember(orderId) { mutableStateOf<Boolean?>(null) }
    var comprobado by remember(orderId) { mutableStateOf(false) }
    var devolucion by remember(orderId) { mutableStateOf<Transaction?>(null) }
    var devuelto by remember(orderId) { mutableStateOf(false) }
    fun vigente()=montada.get() && viewModel.vigente()
    LaunchedEffect(viewModel,orderId) { viewModel.abrir(orderId).join() }
    DisposableEffect(viewModel,orderId) { onDispose {montada.set(false);viewModel.cerrar()} }
    fun releer(siguiente: Boolean) {
        devolucion=null
        scope.launch {
            viewModel.refrescar().join()
            if (!vigente()) return@launch
            onActualizada()
            if (siguiente) devolucion=viewModel.pagosPorDevolver.value.firstOrNull()
        }
    }
    Column(verticalArrangement=Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
        cuenta?.let {
            Text("Venta #${it.orderNumber}",style=MaterialTheme.typography.titleLarge)
            Text(formatMoney(it.total),style=MaterialTheme.typography.headlineSmall)
            it.cancel.message?.let { mensaje -> Text(mensaje) }
        }
        if (!conectado || !servidor) Text(TextosDeTarjetas.SIN_RED)
        else if (aviso != cuenta?.cancel?.message) aviso?.let { Text(it) }
        if (ocupado) AvoqadoBrandLoader()
        if (viewModel.puedeCancelar) TextButton(enabled=!ocupado && conectado && servidor,onClick={
            viewModel.abrirDialogo();dialogo=false;comprobado=false
        }) { Text(TextosDeTarjetas.CANCELAR_VENTA) }
        if (viewModel.puedeLiberar) {
            pagos.forEach { pago ->
                TextButton(enabled=!ocupado && conectado && servidor && viewModel.roles.canIssueRefund,onClick={
                    if (vigente()) devolucion=pago
                }) { Text("Devolver completo · ${pago.methodDescription} · ${formatMoney(pago.remainingRefundable)}") }
            }
            if (!viewModel.roles.canIssueRefund) Text("No tienes permiso para reembolsar. Pídeselo a tu gerente.")
            TextButton(enabled=!ocupado && conectado && servidor,onClick={
                viewModel.abrirDialogo(liberar=true);dialogo=true;comprobado=false
            }) { Text(TextosDeTarjetas.LIBERAR) }
        }
        if (mostrarReconsultaDeTarjetas(aviso ?: cuenta?.cancel?.message, conectado, servidor)) TextButton(enabled=!ocupado && conectado && servidor,onClick={releer(false)}) { Text(TextosDeTarjetas.VOLVER_A_CONSULTAR_CUENTA) }
        HorizontalDivider()
    }
    dialogo?.let { liberar ->
        val titulo=if (liberar) TextosDeTarjetas.LIBERAR else TextosDeTarjetas.CANCELAR_VENTA
        AvoqadoDialog(title=titulo,onDismiss={dialogo=null;viewModel.cerrarDialogo()},actionButton={
            PrimaryButton(text=titulo,enabled=!ocupado && conectado && servidor && (!liberar || comprobado),onClick={
                scope.launch {
                    if (liberar) viewModel.liberar(comprobado)?.join() else viewModel.confirmarCancelacion()?.join()
                    if (!vigente()) return@launch
                    if (liberar && viewModel.exito.value != null || !liberar && viewModel.paso.value == "DEVUELVE_COMPLETO") {
                        dialogo=null;viewModel.cerrarDialogo();onActualizada()
                        if (!liberar && viewModel.roles.canIssueRefund) devolucion=viewModel.pagosPorDevolver.value.firstOrNull()
                    }
                }
            })
        }) {
            Column(verticalArrangement=Arrangement.spacedBy(AvoqadoTheme.spacing.md)) {
                if (liberar) Row {
                    Checkbox(checked=comprobado,enabled=!ocupado,onCheckedChange={comprobado=it})
                    Text(TextosDeTarjetas.COMPROBE_NO_DEVUELTO)
                } else viewModel.confirmacion?.let { Text(it) }
                if (!conectado || !servidor) Text(TextosDeTarjetas.SIN_RED) else aviso?.let {Text(it)}
                if (ocupado) AvoqadoBrandLoader()
            }
        }
    }
    devolucion?.let { pago ->
        if (vigente()) IssueRefundSheet(transaction=pago,maxRefundable=pago.remainingRefundable,
            refundRepository=viewModel.refundRepository,terminalPaymentService=viewModel.terminalPaymentService,
            puedeDevolverEnEfectivo=viewModel.roles.canRefundToCash,codigoDeEncargadoActivo=pinActivo,soloCompleto=true,
            onDismiss={releer(false)},onRefunded={if (vigente()) {devuelto=true;releer(true)}})
    }
    exito?.let { AvoqadoSuccessToast(message=it,onDismiss=viewModel::consumirExito) }
    if (devuelto) AvoqadoSuccessToast(message="¡Reembolso registrado!",onDismiss={devuelto=false})
}
