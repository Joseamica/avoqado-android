package com.avoqado.pos.areatickets.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.avoqado.pos.designsystem.components.AvoqadoDialog
import com.avoqado.pos.designsystem.components.AvoqadoSuccessToast
import com.avoqado.pos.designsystem.components.LocalEsEscritorio
import com.avoqado.pos.designsystem.components.PrimaryButton

/** D3: el recuadro «Vales» de «Más» abre la MISMA hoja que la compuerta. null = no hay compuerta (pruebas, previews). */
val LocalPapelDelAparato = staticCompositionLocalOf<PapelDelAparatoGateViewModel?> { null }

/**
 * D3 (2-nov): la primera vez que un aparato que se dio de alta solo entra con red a un negocio con vales,
 * pregunta qué hará. Vive arriba del NavHost, junto a `AnnouncementGate`: guardar rehace la navegación ([onGuardado]) y el
 * aviso de éxito no se va con ella. Sin nada que preguntar no pinta nada; nunca detiene una venta.
 */
@Composable
fun PapelDelAparatoGate(
    viewModel: PapelDelAparatoGateViewModel,
    consulta: ConsultaDelPapel,
    onGuardado: () -> Unit,
) {
    val hoja by viewModel.hoja.collectAsState()
    val avisoSinPermiso by viewModel.avisoSinPermiso.collectAsState()
    val guardado by viewModel.guardado.collectAsState()
    val hayRed by viewModel.hayRed.collectAsState()
    val esEscritorio = LocalEsEscritorio.current

    LaunchedEffect(consulta) { viewModel.consultar(consulta) }

    hoja?.let { estado ->
        PapelDelAparatoSheet(
            estado = estado,
            hayRed = hayRed,
            onElegir = viewModel::elegir,
            onGuardar = viewModel::guardar,
            onAhoraNo = if (estado.primeraVez) viewModel::ahoraNo else null,
            onCerrar = viewModel::cerrar,
        )
    }

    if (avisoSinPermiso) {
        AvoqadoDialog(
            title = tituloDeLaHoja(esEscritorio),
            description = "Pídele a tu administrador que lo configure",
            onDismiss = viewModel::cerrarAviso,
            actionButton = {
                PrimaryButton(text = "Entendido", onClick = viewModel::entendido, fullWidth = true)
            },
        ) {}
    }

    guardado?.let { papel ->
        LaunchedEffect(papel) { onGuardado() }
        AvoqadoSuccessToast(message = textoAlGuardar(papel, esEscritorio), onDismiss = viewModel::avisoVisto)
    }
}
