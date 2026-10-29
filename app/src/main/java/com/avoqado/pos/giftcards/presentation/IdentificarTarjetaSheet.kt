package com.avoqado.pos.giftcards.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.hilt.navigation.compose.hiltViewModel
import com.avoqado.pos.designsystem.components.*
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.giftcards.data.*
import com.avoqado.pos.giftcards.domain.*
import com.avoqado.pos.pos.presentation.scanner.BarcodeScannerView
import kotlinx.coroutines.launch

@Composable
fun IdentificarTarjetaSheet(
    venueId: String, ajustes: AjustesDeTarjetas?,
    onIdentificada: (TarjetaIdentificada) -> Unit, onCerrar: () -> Unit,
    prueba: PruebaDeTarjeta? = null,
    viewModel: IdentificarTarjetaViewModel = hiltViewModel(),
    saldo: (@Composable (ConsultaDeSaldo, Boolean) -> Unit)? = null,
) {
    val cambioDeAjustes by viewModel.cambiosDeAjustes.collectAsState()
    val ajustesVigentes = cambioDeAjustes.let { viewModel.ajustesVigentes(venueId) }
    if (ajustesVigentes == null) {
        LaunchedEffect(viewModel, venueId) { viewModel.cerrar(); onCerrar() }
        return
    }
    val capacidadesDeApertura = remember(viewModel, venueId) {
        (ajustes ?: ajustesVigentes).capacidades.intersect(ajustesVigentes.capacidades)
    }
    val ajustesEfectivos = ajustesVigentes.copy(
        capacidades = capacidadesDeApertura.intersect(ajustesVigentes.capacidades))
    BarreraDeAtajos()
    LectorHidDeLaVentana(viewModel::procesarTecla)
    val estado by viewModel.estado.collectAsState()
    val identificada by viewModel.identificada.collectAsState()
    val conectado by viewModel.conexion.collectAsState()
    val alcanzable by viewModel.servidor.collectAsState()
    val bloqueo by viewModel.bloqueadoHasta.collectAsState()
    val scope = rememberCoroutineScope()
    var modo by remember { mutableStateOf(TextosDeTarjetas.ESCANEAR) }
    val codigo by viewModel.codigo.collectAsState()
    var telefono by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var pidioCodigo by remember { mutableStateOf(false) }
    var camara by remember { mutableStateOf(false) }
    DisposableEffect(viewModel, venueId) {
        viewModel.abrir(venueId, ajustesEfectivos)
        onDispose { viewModel.cerrar() }
    }
    LaunchedEffect(viewModel, prueba) { prueba?.let { viewModel.porPrueba(it) } }
    LaunchedEffect(identificada) { identificada?.let(onIdentificada) }
    LaunchedEffect(estado) { if (estado != EstadoDeIdentificacion.Capturando) camara = false }
    val ocupado = estado == EstadoDeIdentificacion.Consultando
    val habilitado = !ocupado && bloqueo == null && conectado && alcanzable
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        AvoqadoFullscreenHeader(title = TextosDeTarjetas.CONSULTAR, onNav = onCerrar, showDivider = true,
            primaryActionText = if (modo == TextosDeTarjetas.TELEFONO && !pidioCodigo) TextosDeTarjetas.PEDIR_CODIGO else TextosDeTarjetas.VERIFICAR,
            primaryActionEnabled = habilitado && if (modo == TextosDeTarjetas.TELEFONO) telefono.length == 10 && (!pidioCodigo || otp.length == 6) else codigo.trim().length in 4..40,
            onPrimaryAction = { scope.launch {
                if (modo != TextosDeTarjetas.TELEFONO) viewModel.enviarCodigo()
                else if (pidioCodigo) viewModel.verificar(telefono, otp)
                else { viewModel.pedirCodigo(telefono); if (viewModel.estado.value == EstadoDeIdentificacion.CodigoPedido) pidioCodigo = true }
            } })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding().padding(AvoqadoTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.lg)) {
            if (!conectado || !alcanzable) Text(TextosDeTarjetas.SIN_RED)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                (listOf(TextosDeTarjetas.ESCANEAR, TextosDeTarjetas.TECLEAR_CODIGO) + if (ajustesEfectivos.porTelefono) listOf(TextosDeTarjetas.TELEFONO) else emptyList()).forEach { tab ->
                    FilterChip(selected = modo == tab, enabled = !ocupado, onClick = { modo = tab }, label = { Text(tab) })
                }
            }
            if (modo == TextosDeTarjetas.TELEFONO) {
                Text("+52 · México")
                AvoqadoPillTextField(enabled = !ocupado, value = telefono, onValueChange = { telefono = it.filter { c -> c in '0'..'9' }.take(10); viewModel.cambiarTelefono(telefono); otp = ""; pidioCodigo = false }, placeholder = TextosDeTarjetas.TELEFONO, keyboardType = KeyboardType.Phone)
                if (pidioCodigo) {
                    Text(TextosDeTarjetas.CODIGO_ENVIADO)
                    TextButton(enabled = habilitado, onClick = { scope.launch { viewModel.pedirCodigo(telefono); if (viewModel.estado.value == EstadoDeIdentificacion.CodigoPedido) otp = "" } }) { Text(TextosDeTarjetas.PEDIR_CODIGO) }
                    AvoqadoPillTextField(enabled = !ocupado, value = otp, onValueChange = { otp = it.filter { c -> c in '0'..'9' }.take(6) }, placeholder = TextosDeTarjetas.CODIGO_DE_6_DIGITOS, keyboardType = KeyboardType.Number)
                }
            } else {
                if (modo == TextosDeTarjetas.ESCANEAR) PrimaryButton(text = TextosDeTarjetas.ESCANEAR, enabled = habilitado, onClick = { camara = true })
                AvoqadoPillTextField(enabled = !ocupado, value = codigo, onValueChange = viewModel::cambiarCodigo,
                    placeholder = TextosDeTarjetas.TECLEAR_CODIGO, keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done, keyboardActions = KeyboardActions(onDone = {
                        if (habilitado) scope.launch { viewModel.enviarCodigo() }
                    }))
            }
            when (val e = estado) {
                EstadoDeIdentificacion.Consultando -> AvoqadoBrandLoader()
                is EstadoDeIdentificacion.Error -> {
                    Text(e.mensaje)
                    if (modo != TextosDeTarjetas.TELEFONO && viewModel.puedeReintentar) {
                        TextButton(enabled = habilitado, onClick = { scope.launch { viewModel.reintentar() } }) { Text(TextosDeTarjetas.REINTENTAR) }
                    }
                }
                EstadoDeIdentificacion.Desconocido -> Text("Producto no encontrado")
                is EstadoDeIdentificacion.Saldo -> saldo?.invoke(e.consulta, false)
                is EstadoDeIdentificacion.Eligiendo -> {
                    saldo?.invoke(ConsultaDeSaldo(e.resultado.usableBalance, e.resultado.cards), e.resultado.hayMas)
                    e.resultado.cards.forEach { card ->
                        OutlinedButton(onClick = { viewModel.elegir(card.giftCardId) }) { Text("•••• ${card.last4}") }
                    }
                }
                else -> Unit
            }
        }
    }
    if (camara) BarcodeScannerView(onBarcodeScanned = { crudo -> camara = false; scope.launch { viewModel.porEscaneo(crudo) } },
        onDismiss = { camara = false }, cameraHint = TextosDeTarjetas.ESCANEAR, manualTitle = TextosDeTarjetas.ESCANEAR,
        manualLabel = TextosDeTarjetas.TECLEAR_CODIGO, actionText = TextosDeTarjetas.VERIFICAR,
        lectorIntegrado = remember { com.avoqado.pos.pos.data.LectorIntegradoSunmi.hay() })
    }
}
