package com.avoqado.pos.giftcards.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import com.avoqado.pos.customerdisplay.QrCode
import com.avoqado.pos.designsystem.components.*
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.giftcards.domain.TextosDeTarjetas
import kotlinx.coroutines.launch

@Composable
fun ResultadoDeVentaScreen(llave: String?, orderId: String, onSalir: () -> Unit,
    viewModel: ResultadoDeVentaViewModel = hiltViewModel(key = "resultado-tarjetas-$orderId"),
) {
    BarreraDeAtajos()
    val sobres by viewModel.sobres.collectAsState()
    val aviso by viewModel.aviso.collectAsState()
    val ocupado by viewModel.ocupado.collectAsState()
    val codigo by viewModel.codigo.collectAsState()
    val exito by viewModel.exito.collectAsState()
    val fijado by viewModel.dialogoFijado.collectAsState()
    val conectado by viewModel.conectado.collectAsState()
    val servidor by viewModel.servidor.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var dialogo by remember { mutableStateOf<Pair<String,String>?>(null) }
    var canal by remember { mutableStateOf("WHATSAPP") }
    var telefono by remember { mutableStateOf("") }
    var correo by remember { mutableStateOf("") }
    var copiada by remember { mutableStateOf(false) }
    var falloCompartir by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(viewModel, llave, orderId) {
        viewModel.abrir(llave,orderId)
        viewModel.vigilar()
    }
    DisposableEffect(viewModel) { onDispose { viewModel.cerrar() } }
    LaunchedEffect(exito) { if (cerrarDialogoDeEntrega(exito != null)) { dialogo=null;viewModel.cerrarDialogo();viewModel.vigilar() } }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        AvoqadoFullscreenHeader(title=TextosDeTarjetas.NOMBRE_EN_CUENTAS,onNav=onSalir,
            primaryActionText="Listo",onPrimaryAction=onSalir,showDivider=true)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(AvoqadoTheme.spacing.lg)) {
            GestionDeCuentaDeTarjetas(orderId,onActualizada={scope.launch {viewModel.vigilar()}})
            if (!conectado || !servidor) Text(TextosDeTarjetas.SIN_RED) else aviso?.let { Text(it) }
            if (ocupado) AvoqadoBrandLoader()
            if (mostrarReconsultaDeTarjetas(aviso, conectado, servidor)) TextButton(enabled=controlDeResultadoDeTarjeta(conectado,servidor,!ocupado),onClick={scope.launch {viewModel.vigilar()}}) { Text(TextosDeTarjetas.VOLVER_A_CONSULTAR_TARJETAS) }
            sobres.forEach { fila -> fila.sobre?.let { s ->
                TarjetaDeLaCuentaRow(s,activando=fila.activando,textoDeEntrega=fila.textoDeEntrega,habilitada=!ocupado && conectado && servidor,
                    sinLiga=viewModel.sinLiga(s.id),impresa=viewModel.impresa(s.id),onAccion={accion ->
                        viewModel.abrirDialogo(s.id,accion);dialogo=s.id to accion;telefono="";correo="";falloCompartir=null
                        if (accion=="SHOW_QR") scope.launch {viewModel.mostrarQr(s.id)}
                    })
                HorizontalDivider()
            } }
        }
    }
    dialogo?.let { (id,accion) ->
        val titulo=when(accion) { "REPRINT"->TextosDeTarjetas.IMPRIMIR;"SHOW_QR"->TextosDeTarjetas.MOSTRAR_QR;"RESEND"->TextosDeTarjetas.REENVIAR;else->TextosDeTarjetas.CAMBIAR_DESTINO }
        val qr=sobres.firstOrNull {it.sobre?.id==id}?.qr
        AvoqadoDialog(title=titulo,onDismiss={dialogo=null;viewModel.cerrarDialogo()},actionButton={
            if (accion!="SHOW_QR" || qr==null) PrimaryButton(text=if(aviso==null) titulo else TextosDeTarjetas.REINTENTAR,enabled=!ocupado && conectado && servidor,
                onClick={scope.launch {when(accion) {
                    "REPRINT"->viewModel.imprimir(id)
                    "SHOW_QR"->viewModel.mostrarQr(id)
                    "RESEND"->viewModel.reenviar(id)
                    else->viewModel.cambiarDestino(id,canal,telefono,correo)
                }}})
        }) {
            Column(Modifier.verticalScroll(rememberScrollState()).imePadding(),verticalArrangement=Arrangement.spacedBy(AvoqadoTheme.spacing.md)) {
                if (!conectado || !servidor) Text(TextosDeTarjetas.SIN_RED) else aviso?.let {Text(it)}
                if(ocupado) AvoqadoBrandLoader()
                if(accion=="SHOW_QR" && qr!=null) {
                    QrCode(qr)
                    codigo?.let {Text(it)}
                    TextButton(enabled=conectado && servidor && !ocupado,onClick={try { compartirLiga(context,qr) {copiada=true} } catch (_:Exception) {falloCompartir="No se pudo compartir la liga. Inténtalo de nuevo."}}) {Text(TextosDeTarjetas.COMPARTIR_LIGA)}
                    falloCompartir?.let {Text(it)}
                }
                if(accion=="CHANGE_DESTINATION") {
                    if(fijado) Text("Para corregir el destino, cierra esta ventana y vuelve a abrirla.")
                    Row {
                        RadioButton(selected=canal=="WHATSAPP",enabled=!fijado,onClick={canal="WHATSAPP"});Text(TextosDeTarjetas.WHATSAPP)
                        RadioButton(selected=canal=="EMAIL",enabled=!fijado,onClick={canal="EMAIL"});Text(TextosDeTarjetas.CORREO)
                    }
                    if(canal=="WHATSAPP") AvoqadoPillTextField(value=telefono,enabled=!fijado && !ocupado,onValueChange={if(!fijado) telefono=it},placeholder="+52 · 10 dígitos",keyboardType=KeyboardType.Phone)
                    else AvoqadoPillTextField(value=correo,enabled=!fijado && !ocupado,onValueChange={if(!fijado) correo=it},placeholder=TextosDeTarjetas.CORREO_DE_QUIEN_LA_RECIBE,keyboardType=KeyboardType.Email)
                }
            }
        }
    }
    exito?.let { AvoqadoSuccessToast(message=it,onDismiss=viewModel::consumirExito) }
    if(copiada) AvoqadoSuccessToast(message=TextosDeTarjetas.LIGA_COPIADA,onDismiss={copiada=false})
}

internal fun controlDeResultadoDeTarjeta(conectado: Boolean, servidor: Boolean, disponible: Boolean): Boolean = conectado && servidor && disponible

internal fun cerrarDialogoDeEntrega(exito: Boolean): Boolean = exito

internal fun mostrarReconsultaDeTarjetas(aviso: String?, conectado: Boolean, servidor: Boolean): Boolean =
    conectado && servidor && !aviso.isNullOrBlank()
