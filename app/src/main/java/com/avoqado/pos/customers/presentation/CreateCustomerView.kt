package com.avoqado.pos.customers.presentation

import android.app.Activity
import android.content.Intent
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import com.avoqado.pos.designsystem.components.CircleBackButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.avoqado.pos.customers.data.model.Customer
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

@Composable
fun CreateCustomerView(
    viewModel: CustomersViewModel,
    initialPhone: String?,
    initialName: String?,
    onCustomerCreated: (Customer) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val isSaving by viewModel.isSaving.collectAsState()
    val error by viewModel.error.collectAsState()

    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var showErrorAlert by remember { mutableStateOf(false) }

    // Pre-populate from search text
    LaunchedEffect(Unit) {
        if (!initialPhone.isNullOrEmpty()) {
            phone = initialPhone
        }
        if (!initialName.isNullOrEmpty()) {
            val parts = initialName.split(" ", limit = 2)
            if (parts.isNotEmpty()) firstName = parts[0]
            if (parts.size >= 2) lastName = parts[1]
        }
    }

    // Contact picker launcher
    val contactPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { contactUri ->
                val contentResolver = context.contentResolver

                // Read contact name
                contentResolver.query(contactUri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                        if (nameIdx >= 0) {
                            val displayName = cursor.getString(nameIdx) ?: ""
                            val parts = displayName.split(" ", limit = 2)
                            if (parts.isNotEmpty()) firstName = parts[0]
                            if (parts.size >= 2) lastName = parts[1]
                        }

                        val idIdx = cursor.getColumnIndex(ContactsContract.Contacts._ID)
                        if (idIdx >= 0) {
                            val contactId = cursor.getString(idIdx)

                            // Read phone
                            contentResolver.query(
                                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                                null,
                                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                                arrayOf(contactId),
                                null,
                            )?.use { phoneCursor ->
                                if (phoneCursor.moveToFirst()) {
                                    val phoneIdx = phoneCursor.getColumnIndex(
                                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                                    )
                                    if (phoneIdx >= 0) {
                                        phone = phoneCursor.getString(phoneIdx) ?: ""
                                    }
                                }
                            }

                            // Read email
                            contentResolver.query(
                                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                                null,
                                "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
                                arrayOf(contactId),
                                null,
                            )?.use { emailCursor ->
                                if (emailCursor.moveToFirst()) {
                                    val emailIdx = emailCursor.getColumnIndex(
                                        ContactsContract.CommonDataKinds.Email.ADDRESS,
                                    )
                                    if (emailIdx >= 0) {
                                        email = emailCursor.getString(emailIdx) ?: ""
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val canSave = firstName.isNotBlank() || lastName.isNotBlank() ||
        phone.isNotBlank() || email.isNotBlank()

    // Show error alert
    LaunchedEffect(error) {
        if (error != null) showErrorAlert = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // Header: Back | spacer | "Guardar" button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AvoqadoTheme.spacing.xl, vertical = AvoqadoTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Back button
            CircleBackButton(onClick = onBack)

            Spacer(modifier = Modifier.weight(1f))

            // Save button
            Button(
                onClick = {
                    viewModel.createCustomer(
                        firstName = firstName.ifBlank { null },
                        lastName = lastName.ifBlank { null },
                        phone = phone.ifBlank { null },
                        email = email.ifBlank { null },
                        onSuccess = onCustomerCreated,
                    )
                },
                enabled = canSave && !isSaving,
                shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.xl),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    disabledContainerColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                ),
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))
                }
                Text(
                    text = "Guardar",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        // Form
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AvoqadoTheme.spacing.xl),
        ) {
            // Title
            Text(
                text = "Cliente nuevo",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = AvoqadoTheme.spacing.sm),
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

            // Import from Contacts button
            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI)
                    contactPickerLauncher.launch(intent)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(25.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Text(
                    text = "Importar desde Contactos",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(vertical = AvoqadoTheme.spacing.sm),
                )
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

            // Name fields side by side
            Row(
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = firstName,
                    onValueChange = { firstName = it },
                    label = { Text("Nombre") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))
                OutlinedTextField(
                    value = lastName,
                    onValueChange = { lastName = it },
                    label = { Text("Apellido") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

            // Phone field
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text("Telefono") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

            // Email field
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Correo electronico") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))
        }
    }

    // Error alert
    if (showErrorAlert && error != null) {
        AlertDialog(
            onDismissRequest = {
                showErrorAlert = false
                viewModel.clearError()
            },
            title = { Text("No se pudo crear el cliente") },
            text = { Text(error ?: "") },
            confirmButton = {
                TextButton(onClick = {
                    showErrorAlert = false
                    viewModel.clearError()
                }) {
                    Text("Entendido")
                }
            },
        )
    }
}
