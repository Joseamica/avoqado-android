package com.avoqado.pos.customers.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.avoqado.pos.customers.data.model.Customer
import com.avoqado.pos.customers.domain.AVISO_SIN_RED_EDITAR_CLIENTE
import com.avoqado.pos.customers.domain.CampoDeContacto
import com.avoqado.pos.customers.domain.ContactoEditado
import com.avoqado.pos.customers.domain.cambiosDeContacto
import com.avoqado.pos.customers.domain.erroresDeContacto
import com.avoqado.pos.designsystem.components.CloseButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

/**
 * «Editar cliente» (26-oct-2026): mismos cuatro datos y mismo estilo que «Crear cliente», ya llenos. «Guardar» sólo se
 * prende con un cambio de verdad y sin datos vaciados; sin red el aviso sale AQUÍ y lo escrito no se pierde.
 * Espejo de `EditCustomerSheet.swift` en iOS.
 */
@Composable
fun EditCustomerView(
    viewModel: CustomersViewModel,
    cliente: Customer,
    onGuardado: (Customer) -> Unit,
    onBack: () -> Unit,
) {
    val isSaving by viewModel.isSaving.collectAsState()
    val errorDeEdicion by viewModel.errorDeEdicion.collectAsState()

    // rememberSaveable: girar la tablet no borra lo escrito. Si «Más» cerró Clientes a media guardada, se reabre con lo
    // que se mandó a guardar (D3).
    val borrador = viewModel.borradorDeEdicion
    var nombre by rememberSaveable(cliente.id) { mutableStateOf(borrador?.nombre ?: cliente.firstName.orEmpty()) }
    var apellido by rememberSaveable(cliente.id) { mutableStateOf(borrador?.apellido ?: cliente.lastName.orEmpty()) }
    var telefono by rememberSaveable(cliente.id) { mutableStateOf(borrador?.telefono ?: cliente.phone.orEmpty()) }
    var correo by rememberSaveable(cliente.id) { mutableStateOf(borrador?.correo ?: cliente.email.orEmpty()) }

    val editado = ContactoEditado(nombre, apellido, telefono, correo)
    val errores = remember(editado, cliente) { erroresDeContacto(cliente, editado) }
    val hayCambios = remember(editado, cliente) { cambiosDeContacto(cliente, editado) != null }
    val puedeGuardar = hayCambios && errores.isEmpty() && !isSaving

    // El «atrás» del sistema cierra el formulario (no toda la pantalla de Clientes), y tampoco mientras guarda.
    BackHandler { if (!isSaving) onBack() }

    val headerActionContainer = MaterialTheme.colorScheme.onSurface
    val headerActionContent = MaterialTheme.colorScheme.surface

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .imePadding(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AvoqadoTheme.spacing.md, vertical = AvoqadoTheme.spacing.sm),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Editar cliente",
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium,
            )
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // Mientras guarda no se cierra: la respuesta (y su PIN) pertenecen a ESTE formulario (Codex r3 #1).
                CloseButton(onClick = { if (!isSaving) onBack() })
                Spacer(modifier = Modifier.weight(1f))
                Button(
                    onClick = { viewModel.guardarEdicion(editado, onGuardado) },
                    enabled = puedeGuardar,
                    shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(horizontal = AvoqadoTheme.spacing.lg),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = headerActionContainer,
                        contentColor = headerActionContent,
                        disabledContainerColor = headerActionContainer.copy(alpha = 0.35f),
                        disabledContentColor = headerActionContent.copy(alpha = 0.75f),
                    ),
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = headerActionContent)
                        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.xs))
                    }
                    Text(text = "Guardar", style = MaterialTheme.typography.titleMedium)
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AvoqadoTheme.spacing.xl),
        ) {
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

            errorDeEdicion?.let { aviso ->
                if (aviso == AVISO_SIN_RED_EDITAR_CLIENTE) {
                    // Sin red es un estado, no un error en rojo (regla todo-funciona-sin-red).
                    AvisoSinRedDeClientes(texto = aviso)
                } else {
                    Text(text = aviso, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                CampoDeEdicion(
                    valor = nombre,
                    onCambio = { nombre = it },
                    etiqueta = "Nombre",
                    error = errores[CampoDeContacto.NOMBRE],
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))
                CampoDeEdicion(
                    valor = apellido,
                    onCambio = { apellido = it },
                    etiqueta = "Apellido",
                    error = errores[CampoDeContacto.APELLIDO],
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
            CampoDeEdicion(
                valor = telefono,
                onCambio = { telefono = it },
                etiqueta = "Teléfono",
                error = errores[CampoDeContacto.TELEFONO],
                teclado = KeyboardType.Phone,
            )
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
            CampoDeEdicion(
                valor = correo,
                onCambio = { correo = it },
                etiqueta = "Correo electrónico",
                error = errores[CampoDeContacto.CORREO],
                teclado = KeyboardType.Email,
            )
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))
        }
    }
}

@Composable
private fun CampoDeEdicion(
    valor: String,
    onCambio: (String) -> Unit,
    etiqueta: String,
    error: String?,
    modifier: Modifier = Modifier.fillMaxWidth(),
    teclado: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = valor,
        onValueChange = onCambio,
        label = { Text(etiqueta) },
        modifier = modifier,
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = teclado),
    )
}
