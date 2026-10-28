package com.avoqado.pos.giftcards.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import com.avoqado.pos.designsystem.components.*
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.core.util.formatMoneyFromCents
import com.avoqado.pos.giftcards.data.AjustesDeTarjetas
import com.avoqado.pos.giftcards.domain.*
import com.avoqado.pos.payment.presentation.CobroDeCuentaScreen
import kotlinx.coroutines.launch

@Composable
fun VenderTarjetaScreen(
    venueId: String,
    ajustes: AjustesDeTarjetas,
    apertura: String,
    onSalir: () -> Unit,
    onResultadoPendiente: (String) -> Unit,
    onCuentaCancelada: () -> Unit,
    viewModel: VenderTarjetaViewModel = hiltViewModel(key = "venta-tarjetas-$apertura"),
) {
    BarreraDeAtajos()
    val form by viewModel.formulario.collectAsState()
    val estado by viewModel.estado.collectAsState()
    val solicitudes by viewModel.solicitudes.collectAsState()
    val conectado by viewModel.conectado.collectAsState()
    val servidor by viewModel.servidor.collectAsState()
    val scope = rememberCoroutineScope()
    var cuenta by rememberSaveable { mutableStateOf<String?>(null) }
    var mensaje by rememberSaveable { mutableStateOf<String?>(null) }
    var montos by rememberSaveable { mutableStateOf(listOf("")) }
    val invalidos = form.invalidos(ajustes)
    LaunchedEffect(viewModel, venueId) { viewModel.alAbrir(venueId, ajustes) }
    LaunchedEffect(estado) {
        when (val e = estado) {
            is VenderTarjetaViewModel.Estado.Cobro -> cuenta = e.orderId
            is VenderTarjetaViewModel.Estado.ResultadoPendiente -> onResultadoPendiente(e.orderId)
            else -> Unit
        }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        AvoqadoFullscreenHeader(
            title = TextosDeTarjetas.VENDER, onNav = onSalir,
            primaryActionText = if (viewModel.bloqueado) TextosDeTarjetas.REINTENTAR else TextosDeTarjetas.cobrar(form.sobres.sumOf { it.montoCents ?: 0 }),
            primaryActionEnabled = controlDeVentaDeTarjeta(conectado, servidor, estado != VenderTarjetaViewModel.Estado.Enviando && (viewModel.bloqueado || invalidos.isEmpty())),
            primaryActionDisabledReason = if (!conectado || !servidor) TextosDeTarjetas.SIN_RED else if (invalidos.isNotEmpty() && !viewModel.bloqueado) "Revisa los campos de la tarjeta" else null,
            onPrimaryAction = { scope.launch { viewModel.cobrarDesdePantalla() } },
            showDivider = true,
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding().padding(AvoqadoTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.lg)) {
            if (!conectado || !servidor) Text(TextosDeTarjetas.SIN_RED)
            if (estado is VenderTarjetaViewModel.Estado.Error) Text((estado as VenderTarjetaViewModel.Estado.Error).texto)
            mensaje?.let { Text(it) }
            if (estado == VenderTarjetaViewModel.Estado.Enviando) {
                AvoqadoBrandLoader()
                Text(TextosDeTarjetas.ENVIANDO)
            }
            solicitudes.filter { it.venueId == venueId && it.tipo == "GIFT_CARD_SALE" }.forEach { solicitud ->
                key(solicitud.llave) {
                    VentaGuardada(solicitud, enabled = controlDeVentaDeTarjeta(conectado, servidor, estado != VenderTarjetaViewModel.Estado.Enviando), onClick = {
                        if (viewModel.reanudar(solicitud.llave)) scope.launch { viewModel.cobrarDesdePantalla() }
                        else mensaje = TextosDeTarjetas.CUENTA_NO_ENCONTRADA
                    })
                }
            }
            if (!viewModel.bloqueado) {
                form.sobres.forEachIndexed { i, sobre ->
                    Column(verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                        Text("${TextosDeTarjetas.MOSAICO} ${i + 1}", style = MaterialTheme.typography.titleMedium)
                        fun cambiar(nuevo: SobreEnFormulario) = viewModel.cambiar(form.copy(sobres = form.sobres.toMutableList().also { it[i] = nuevo }))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                            ajustes.suggestedAmounts.forEach { pesos ->
                                val cents = kotlin.math.round(pesos * 100).toInt()
                                OutlinedButton(onClick = {
                                    montos = montos.toMutableList().also { it[i] = java.math.BigDecimal.valueOf(cents.toLong(), 2).toPlainString() }
                                    cambiar(sobre.copy(montoCents = cents))
                                }) { Text(formatMoneyFromCents(cents)) }
                            }
                        }
                        AvoqadoPillTextField(value = montos.getOrElse(i) { "" }, placeholder = TextosDeTarjetas.OTRO_MONTO,
                            keyboardType = KeyboardType.Decimal, onValueChange = { texto ->
                                montos = montos.toMutableList().also { it[i] = texto }
                                val cents = runCatching { texto.toBigDecimal().movePointRight(2).intValueExact() }.getOrNull()
                                cambiar(sobre.copy(montoCents = cents))
                            })
                        if ("monto$i" in invalidos) Text(TextosDeTarjetas.entre(kotlin.math.round(ajustes.minAmount * 100).toInt(), kotlin.math.round(ajustes.maxAmount * 100).toInt()))
                        Text(TextosDeTarjetas.COMO_LA_ENTREGAS)
                        val canales = listOf("PRINTED" to TextosDeTarjetas.IMPRIMIR, "HANDED_TO_BUYER" to TextosDeTarjetas.ENTREGAR_AL_COMPRADOR,
                            "WHATSAPP" to TextosDeTarjetas.WHATSAPP, "EMAIL" to TextosDeTarjetas.CORREO)
                        canales.forEach { (canal, nombre) ->
                            Row {
                                RadioButton(selected = sobre.canal == canal, onClick = { cambiar(sobre.copy(canal = canal)) })
                                TextButton(onClick = { cambiar(sobre.copy(canal = canal)) }) { Text(nombre) }
                            }
                        }
                        AvoqadoPillTextField(value = sobre.nombre, onValueChange = { cambiar(sobre.copy(nombre = it)) }, placeholder = TextosDeTarjetas.PARA)
                        if (sobre.canal == "WHATSAPP") {
                            Text(TextosDeTarjetas.TELEFONO_DE_QUIEN_LA_RECIBE)
                            AvoqadoPillTextField(value = sobre.telefonoDigitos, keyboardType = KeyboardType.Phone,
                                onValueChange = { cambiar(sobre.copy(telefonoDigitos = it, lada = "52")) }, placeholder = "+52 · 10 dígitos")
                            if ("telefono$i" in invalidos) Text("Escribe 10 dígitos de México (+52)")
                        }
                        if (sobre.canal == "EMAIL") {
                            AvoqadoPillTextField(value = sobre.correo, onValueChange = { cambiar(sobre.copy(correo = it)) },
                                placeholder = TextosDeTarjetas.CORREO_DE_QUIEN_LA_RECIBE, keyboardType = KeyboardType.Email)
                            if ("correo$i" in invalidos) Text("Revisa el correo")
                        }
                        AvoqadoPillTextField(value = sobre.mensaje, onValueChange = { cambiar(sobre.copy(mensaje = it)) }, placeholder = TextosDeTarjetas.MENSAJE)
                        if ("nombre$i" in invalidos) Text("Máximo 80 caracteres")
                        if ("mensaje$i" in invalidos) Text("Máximo 200 caracteres")
                        if (form.sobres.size > 1) TextButton(onClick = {
                            montos = montos.filterIndexed { index, _ -> index != i }
                            viewModel.cambiar(form.copy(sobres = form.sobres.filterIndexed { index, _ -> index != i }))
                        }) { Text("Quitar tarjeta") }
                        HorizontalDivider()
                    }
                }
                TextButton(enabled = form.sobres.size < 20, onClick = {
                    montos = montos + ""
                    viewModel.cambiar(form.copy(sobres = form.sobres + SobreEnFormulario(null, "PRINTED", "", "", "", "")))
                }) { Text(TextosDeTarjetas.AGREGAR_OTRA) }
                AvoqadoPillTextField(value = form.deParteDe, onValueChange = { viewModel.cambiar(form.copy(deParteDe = it)) }, placeholder = TextosDeTarjetas.DE_PARTE_DE)
                if ("deParteDe" in invalidos) Text(TextosDeTarjetas.OBLIGATORIO_CON_ENTREGA)
                if ("total" in invalidos) Text(TextosDeTarjetas.totalDebajoDe(kotlin.math.round(ajustes.topePorOperacion!! * 100).toInt()))
            }
            if (estado is VenderTarjetaViewModel.Estado.ResultadoPendiente) Text(TextosDeTarjetas.CUENTA_YA_PAGADA)
        }
    }
    cuenta?.let { id ->
        CobroDeCuentaScreen(orderId = id, onSalir = { cierre ->
            cuenta = null
            if (cierre == com.avoqado.pos.payment.presentation.CierreDeCuenta.PAGADA) onResultadoPendiente(id)
            if (cierre == com.avoqado.pos.payment.presentation.CierreDeCuenta.CANCELADA) onCuentaCancelada()
            onSalir()
        })
    }
}

@Composable
private fun VentaGuardada(solicitud: SolicitudDeTarjeta, enabled: Boolean, onClick: () -> Unit) {
    val cuenta: com.avoqado.pos.payment.presentation.CobroDeCuentaViewModel = hiltViewModel(key = "venta-guardada-${solicitud.llave}")
    val detalle by cuenta.detalle.collectAsState()
    val conectado by cuenta.conectado.collectAsState()
    val servidor by cuenta.servidor.collectAsState()
    LaunchedEffect(solicitud.orderId) {
        if (com.avoqado.pos.payment.presentation.controlDeRedDeCuenta(conectado, servidor, true))
            solicitud.orderId?.let { cuenta.cargar(it) }
    }
    val cents = detalle?.let { com.avoqado.pos.tables.data.restanteCents(it) }
    OutlinedButton(enabled = enabled, onClick = onClick) {
        Column {
            Text(TextosDeTarjetas.NOMBRE_EN_CUENTAS)
            Text(if (cents != null && cents > 0) TextosDeTarjetas.cobrar(cents) else TextosDeTarjetas.REINTENTAR)
        }
    }
}

internal fun controlDeVentaDeTarjeta(conectado: Boolean, servidor: Boolean, disponible: Boolean): Boolean = conectado && servidor && disponible
