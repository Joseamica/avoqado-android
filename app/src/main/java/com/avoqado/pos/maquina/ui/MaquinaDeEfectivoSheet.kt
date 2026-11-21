package com.avoqado.pos.maquina.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import com.avoqado.pos.designsystem.components.AvoqadoFullscreenHeader
import com.avoqado.pos.designsystem.components.AvoqadoModalBottomSheet
import com.avoqado.pos.designsystem.components.AvoqadoPillTextField
import com.avoqado.pos.designsystem.components.AvoqadoSuccessToast
import com.avoqado.pos.designsystem.components.FullscreenHeaderNav
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.maquina.TEXTO_IP_INVALIDA
import com.avoqado.pos.maquina.TEXTO_PENDIENTES_DE_LA_MAQUINA
import com.avoqado.pos.maquina.esIpv4

/**
 * Ajustes «Máquina de efectivo» (C6, sólo en WINDOWS): la IP de la QuickPay, «Probar conexión» y emparejar o desemparejar
 * ESTA PC con el SERVIDOR. Pantalla completa: mientras empareja no se cierra.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaquinaDeEfectivoSheet(onDismiss: () -> Unit, viewModel: MaquinaDeEfectivoViewModel = hiltViewModel()) {
    val estado by viewModel.estado.collectAsState()
    LaunchedEffect(Unit) { viewModel.abrir() }
    val cerrar = { if (!viewModel.estado.value.ocupado) onDismiss() }
    AvoqadoModalBottomSheet(
        onDismissRequest = cerrar,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null,
        conXPropia = true,
        aPantallaCompleta = true,
    ) {
        Box(Modifier.fillMaxSize()) {
            ContenidoDeLaMaquina(
                estado = estado,
                onIp = viewModel::cambiarIp,
                onProbar = viewModel::probar,
                onEmparejar = viewModel::emparejar,
                onDesemparejar = viewModel::desemparejar,
                onCerrar = cerrar,
                onImprimirComprobante = viewModel::imprimirComprobante,
                onResolver = viewModel::resolver,
                onResolverCerrado = viewModel::avisoDeResolverVisto,
                onVerMasComprobantes = viewModel::verMasComprobantes,
                onImprimirTickets = viewModel::imprimirTicketsPendientes,
                onVerMasDudas = viewModel::verMasDudas,
            )
            estado.listo?.let { AvoqadoSuccessToast(message = it, onDismiss = viewModel::toastVisto) }
        }
    }
}

/** Sin estado propio: lo prueba `desktop/` (`MaquinaDeEfectivoSheetTest`). */
@Composable
internal fun ContenidoDeLaMaquina(
    estado: EstadoDeLaMaquina,
    onIp: (String) -> Unit,
    onProbar: () -> Unit,
    onEmparejar: () -> Unit,
    onDesemparejar: () -> Unit,
    onCerrar: () -> Unit,
    onImprimirComprobante: (String) -> Unit = {},
    onResolver: (String, com.avoqado.pos.maquina.ResolucionDelGerente) -> Unit = { _, _ -> },
    onResolverCerrado: () -> Unit = {},
    onVerMasComprobantes: () -> Unit = {},
    onImprimirTickets: () -> Unit = {},
    onVerMasDudas: () -> Unit = {},
) {
    var resolviendoFolio by remember { mutableStateOf<String?>(null) }
    // Al resolverse, la duda sale de la lista: el diálogo se cierra solo.
    val folioAbierto = resolviendoFolio?.takeIf { folio -> estado.enEspera.any { it.referenceCC == folio } }
    Column(Modifier.fillMaxSize()) {
        AvoqadoFullscreenHeader(
            title = "Máquina de efectivo",
            onNav = onCerrar,
            navStyle = FullscreenHeaderNav.CLOSE,
            navEnabled = !estado.ocupado,
            showDivider = true,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(AvoqadoTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
        ) {
            Text(
                text = "Conecta esta PC con la QuickPay Mini de la tienda. Sólo una PC por máquina registra sus cobros: " +
                    "emparejar ésta se la quita a la que la tenía.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = when {
                    !estado.cargado -> "Revisando…"
                    // Codex código r1 #10: en otra sucursal la máquina no cobra; se dice con cuál está emparejada.
                    estado.emparejadaConOtra != null -> "Esta PC está emparejada con ${estado.emparejadaConOtra}"
                    estado.emparejada -> "Emparejada con ${estado.ipGuardada}"
                    estado.credencialPerdida -> "Esta PC perdió su credencial (¿se copió de otra PC?): vuelve a emparejarla"
                    else -> "Sin emparejar"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            // Codex código r3 #7: sin `cash-machine:configure` se entra a la bandeja, pero la IP y emparejar se ven en gris y se dice por qué.
            if (estado.cargado && !estado.puedeConfigurar) Nota(com.avoqado.pos.maquina.TEXTO_SIN_PERMISO)
            AvoqadoPillTextField(
                value = estado.ip,
                onValueChange = onIp,
                placeholder = "IP de la máquina, por ejemplo 192.168.1.50",
                enabled = estado.cargado && !estado.ocupado && !estado.ipBloqueada && estado.puedeConfigurar,
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
            )
            if (estado.ip.isNotBlank() && !esIpv4(estado.ip)) Nota(TEXTO_IP_INVALIDA)
            if (estado.hayPendientes && estado.activa) Nota(TEXTO_PENDIENTES_DE_LA_MAQUINA, error = true)

            OutlinedButton(
                onClick = onProbar,
                enabled = estado.cargado && estado.puedeConfigurar && esIpv4(estado.ip) && estado.prueba != PruebaDeConexion.PROBANDO,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Probar conexión") }
            when (estado.prueba) {
                PruebaDeConexion.PROBANDO -> Nota("Probando…")
                PruebaDeConexion.CONECTADA -> Nota("Conectada")
                PruebaDeConexion.NO_RESPONDE -> Nota("No responde: revisa que esté prendida y en la misma red que esta PC", error = true)
                null -> Unit
            }

            PrimaryButton(
                text = "Emparejar esta PC con la máquina",
                onClick = onEmparejar,
                enabled = estado.puedeEmparejar,
                isLoading = estado.ocupado,
                fullWidth = true,
            )
            if (estado.activa) {
                OutlinedButton(onClick = onDesemparejar, enabled = estado.puedeDesemparejar, modifier = Modifier.fillMaxWidth()) {
                    Text("Desemparejar")
                }
            }
            Nota("Emparejar y desemparejar necesitan internet.")
            // El freno ya se ve arriba: no se repite.
            estado.aviso?.takeUnless { it == TEXTO_PENDIENTES_DE_LA_MAQUINA && estado.hayPendientes && estado.activa }
                ?.let { Nota(it, error = true) }

            // Codex código r2 #12: los papeles que no salieron, también con la máquina apagada (Cobrar sólo los enseña con ella activa).
            TicketsSinImprimirDeLaMaquina(estado.ticketsSinImprimir, onImprimirTickets)

            // Codex código r1 #7 y r2 H6: los cobros del negocio «Esperando a la máquina», con «Resolver con gerente».
            if (estado.enEspera.isNotEmpty()) {
                Text(
                    "${com.avoqado.pos.maquina.TEXTO_COBROS_EN_ESPERA} (${maxOf(estado.enEsperaTotal, estado.enEspera.size)})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                estado.enEspera.forEach { c ->
                    androidx.compose.foundation.layout.Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text(
                            "Folio ${c.referenceCC} · ${com.avoqado.pos.core.util.formatMoneyFromCents(c.dueCents)}" +
                                // Codex código r3 #9 y #8: lo que entró, en número; y si sólo falta terminarlo.
                                (c.creditCents?.let { " · entró ${com.avoqado.pos.core.util.formatMoneyFromCents(it)}" } ?: "") +
                                (if (c.porTerminar) " · $TEXTO_POR_TERMINAR" else ""),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = { resolviendoFolio = c.referenceCC }) {
                            Text(if (c.porTerminar) "Terminar" else "Resolver con gerente")
                        }
                    }
                }
                // Codex código r2 H6 y r3 #8: ninguna duda se queda fuera por el tope de la página (del SERVIDOR o de esta PC).
                if (estado.hayMasDudas) {
                    OutlinedButton(onClick = onVerMasDudas, modifier = Modifier.fillMaxWidth()) {
                        Text("Ver más (${(estado.enEsperaTotal - estado.enEspera.size).coerceAtLeast(1)})")
                    }
                }
            }

            // C7 (R5#9, R3#11): lo que la máquina les debe a clientes, con su comprobante (mismo folio) para reimprimir.
            if (estado.comprobantes.isNotEmpty()) {
                Text("Lo que la máquina les debe a clientes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Nota("Se paga con un retiro de caja, con el folio en la nota.")
                estado.comprobantes.forEach { c ->
                    androidx.compose.foundation.layout.Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text(
                            "Folio ${c.referenceCC} · ${com.avoqado.pos.core.util.formatMoneyFromCents(c.debeCents)}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = { onImprimirComprobante(c.referenceCC) }) { Text("Imprimir comprobante") }
                    }
                }
                // Codex código r1 #13: ninguna deuda se queda fuera por el tope de la página.
                if (estado.comprobantesTotal > estado.comprobantes.size) {
                    OutlinedButton(onClick = onVerMasComprobantes, modifier = Modifier.fillMaxWidth()) {
                        Text("Ver más (${estado.comprobantesTotal - estado.comprobantes.size})")
                    }
                }
            }
        }
    }
    folioAbierto?.let { folio ->
        DialogoDeResolver(estado, folio, onResolver, onCerrar = { resolviendoFolio = null; onResolverCerrado() })
    }
}

@Composable
private fun DialogoDeResolver(
    estado: EstadoDeLaMaquina,
    folio: String,
    onResolver: (String, com.avoqado.pos.maquina.ResolucionDelGerente) -> Unit,
    onCerrar: () -> Unit,
) = ResolverAMano(
    estado.puedeResolver, estado.resolviendo, estado.avisoDeResolver, estado.enEspera.firstOrNull { it.referenceCC == folio }?.creadoEn,
    onResolver = { onResolver(folio, it) }, onCerrar = onCerrar,
    // Codex código r2 H7: la evidencia y la versión de la lista; un 409 la reemplaza por la del SERVIDOR.
    duda = estado.enEspera.firstOrNull { it.referenceCC == folio },
)

@Composable
private fun Nota(texto: String, error: Boolean = false) {
    Text(
        text = texto,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
