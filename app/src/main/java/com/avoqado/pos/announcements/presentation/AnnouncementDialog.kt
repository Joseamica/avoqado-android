package com.avoqado.pos.announcements.presentation

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * La ventana del anuncio: el detalle que se abre desde el buzón, y también la que
 * interrumpe una vez cuando el anuncio viene marcado como `showAsModal`.
 *
 * Espejo de la hoja de iOS: mismos textos, mismo comportamiento.
 */
@Composable
fun AnnouncementDialog(
    announcementId: String,
    onCerrar: () -> Unit,
    viewModel: AnnouncementViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val detalle by viewModel.detalle.collectAsState()
    val cargando by viewModel.cargando.collectAsState()
    val fallo by viewModel.fallo.collectAsState()

    LaunchedEffect(announcementId) { viewModel.abrir(announcementId) }

    Dialog(
        onDismissRequest = {
            viewModel.limpiar()
            onCerrar()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            val actual = detalle
            if (fallo && !cargando) {
                // La pantalla nunca puede quedarse girando: si no cargó, se dice y se cierra.
                Column(
                    modifier = Modifier.padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "No se pudo abrir el anuncio. Intenta de nuevo más tarde.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = {
                        viewModel.limpiar()
                        onCerrar()
                    }) { Text("Cerrar") }
                }
            } else if (cargando || actual == null) {
                Box(modifier = Modifier.padding(48.dp)) { CircularProgressIndicator() }
            } else {
                AnnouncementSheet(
                    anuncio = actual.anuncio,
                    bloques = actual.bloques,
                    onCerrar = {
                        viewModel.limpiar()
                        onCerrar()
                    },
                    onAccion = { url ->
                        viewModel.registrarAccion(announcementId)
                        // Abrir fuera de la app: el botón del anuncio apunta a la web.
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                        }
                    },
                )
            }
        }
    }
}
