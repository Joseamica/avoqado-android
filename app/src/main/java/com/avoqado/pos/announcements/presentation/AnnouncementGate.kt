package com.avoqado.pos.announcements.presentation

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * El aviso que interrumpe, en DOS niveles:
 *
 *  1. Un diálogo chico y centrado, que sólo dice de qué se trata. Interrumpe una vez.
 *  2. Si toca "Ver más", ahí sí se abre el contenido completo con fotos y ficha técnica.
 *
 * 🔴 Es a propósito que el primer nivel NO tape la pantalla: quien está cobrando no
 * quiere que le cubran todo para leer una novedad. Espejo del `AnnouncementGate` del
 * dashboard y de iOS.
 *
 * Si no hay nada que mostrar no pinta nada, y si la consulta falla tampoco: un anuncio
 * jamás puede impedir cobrar.
 */
@Composable
fun AnnouncementGate(viewModel: AnnouncementGateViewModel = hiltViewModel()) {
    val pendiente by viewModel.ventanaPendiente.collectAsState()
    var verCompleto by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.consultar() }

    val ventana = pendiente ?: return

    if (verCompleto) {
        AnnouncementDialog(
            announcementId = ventana.id,
            onCerrar = {
                verCompleto = false
                viewModel.cerrar(ventana.id)
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = { viewModel.cerrar(ventana.id) },
        title = { Text(ventana.titulo) },
        text = { Text(ventana.cuerpo) },
        confirmButton = {
            TextButton(onClick = { verCompleto = true }) {
                Text(ventana.etiquetaAccion ?: "Ver más")
            }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.cerrar(ventana.id) }) { Text("Ahora no") }
        },
    )
}
