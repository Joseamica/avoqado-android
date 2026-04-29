package com.avoqado.pos.reservations.presentation.create.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avoqado.pos.customers.data.model.CreateCustomerRequest
import com.avoqado.pos.customers.data.model.Customer
import com.avoqado.pos.designsystem.components.AvoqadoDialog
import com.avoqado.pos.designsystem.components.AvoqadoPhoneInput
import com.avoqado.pos.designsystem.components.AvoqadoPillTextField
import com.avoqado.pos.designsystem.components.Countries
import com.avoqado.pos.designsystem.components.Country
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.reservations.presentation.create.CreateReservationViewModel

/**
 * Quick-create customer sheet shown from the Customer step.
 *
 * Wraps [AvoqadoDialog] with firstName / lastName / phone (international) / email fields.
 * Phone digits are composed into E.164 (`"+${country.dialCode}$digits"`) before submission.
 *
 * On success the parent VM updates the draft (sets customerId + customerName, clears guest
 * fields) and the sheet is dismissed via [onDismiss].
 */
@Composable
fun CustomerQuickCreateSheet(
    viewModel: CreateReservationViewModel,
    onDismiss: () -> Unit,
    onCustomerCreated: (Customer) -> Unit = {},
    initialCountry: Country = Countries.byIso("MX"),
) {
    val isCreating by viewModel.isCreatingCustomer.collectAsStateWithLifecycle()
    val errorMessage by viewModel.customerError.collectAsStateWithLifecycle()

    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var country by remember { mutableStateOf(initialCountry) }
    var digits by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }

    // Reset error when sheet is shown so stale messages don't persist between opens.
    LaunchedEffect(Unit) {
        viewModel.clearCustomerError()
    }

    val canSubmit = firstName.isNotBlank() && (digits.isNotBlank() || email.isNotBlank())

    AvoqadoDialog(
        title = "Crear cliente",
        onDismiss = {
            viewModel.clearCustomerError()
            onDismiss()
        },
        actionButton = {
            PrimaryButton(
                text = "Crear",
                onClick = {
                    val phoneE164 = if (digits.isNotBlank()) "+${country.dialCode}$digits" else null
                    viewModel.createCustomer(
                        request = CreateCustomerRequest(
                            firstName = firstName.trim().ifBlank { null },
                            lastName = lastName.trim().ifBlank { null },
                            phone = phoneE164,
                            email = email.trim().ifBlank { null },
                        ),
                        onSuccess = { customer ->
                            onCustomerCreated(customer)
                            onDismiss()
                        },
                    )
                },
                enabled = canSubmit && !isCreating,
                isLoading = isCreating,
                fullWidth = true,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md)) {
            FieldGroup(label = "Nombre") {
                AvoqadoPillTextField(
                    value = firstName,
                    onValueChange = { firstName = it },
                    placeholder = "Nombre",
                    enabled = !isCreating,
                )
            }

            FieldGroup(label = "Apellido (opcional)") {
                AvoqadoPillTextField(
                    value = lastName,
                    onValueChange = { lastName = it },
                    placeholder = "Apellido",
                    enabled = !isCreating,
                )
            }

            FieldGroup(label = "Teléfono") {
                AvoqadoPhoneInput(
                    country = country,
                    onCountryChange = { country = it },
                    digits = digits,
                    onDigitsChange = { digits = it },
                    enabled = !isCreating,
                )
            }

            FieldGroup(label = "Correo (opcional)") {
                AvoqadoPillTextField(
                    value = email,
                    onValueChange = { email = it },
                    placeholder = "correo@ejemplo.com",
                    keyboardType = KeyboardType.Email,
                    enabled = !isCreating,
                )
            }

            if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxs))
        }
    }
}

@Composable
private fun FieldGroup(
    label: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xs)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}
