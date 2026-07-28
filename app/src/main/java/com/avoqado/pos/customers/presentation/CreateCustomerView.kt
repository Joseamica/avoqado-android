package com.avoqado.pos.customers.presentation

import android.app.Activity
import android.content.Intent
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.avoqado.pos.customers.data.model.Customer
import com.avoqado.pos.designsystem.components.AvoqadoDialog
import com.avoqado.pos.designsystem.components.CloseButton
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoAdaptiveSizeClass
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

    val contactPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { contactUri ->
                val contentResolver = context.contentResolver
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

    val canSave = firstName.isNotBlank() ||
        lastName.isNotBlank() ||
        phone.isNotBlank() ||
        email.isNotBlank()

    LaunchedEffect(error) {
        if (error != null) showErrorAlert = true
    }

    val configuration = LocalConfiguration.current
    val adaptive = AvoqadoTheme.adaptive
    val lowDensityTabletFallback = configuration.densityDpi in 1..220 &&
        adaptive.sizeClass != AvoqadoAdaptiveSizeClass.Compact &&
        (!adaptive.isPortrait || configuration.screenWidthDp <= 900)
    val denseMode = adaptive.isAggressiveCompact || lowDensityTabletFallback
    val horizontalPadding = if (denseMode) AvoqadoTheme.spacing.lg else AvoqadoTheme.spacing.xl
    val verticalSpacing = if (denseMode) AvoqadoTheme.spacing.md else AvoqadoTheme.spacing.lg
    val actionSlotWidth = if (denseMode) 132.dp else 148.dp
    val headerActionContainer = MaterialTheme.colorScheme.onSurface
    val headerActionContent = MaterialTheme.colorScheme.surface

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AvoqadoTheme.spacing.md, vertical = AvoqadoTheme.spacing.sm),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Crear cliente",
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.SemiBold,
                style = if (denseMode) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CloseButton(onClick = onBack)
                Spacer(modifier = Modifier.weight(1f))

                Box(
                    modifier = Modifier.width(actionSlotWidth),
                    contentAlignment = Alignment.CenterEnd,
                ) {
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
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = headerActionContent,
                            )
                            Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.xs))
                        }

                        Text(
                            text = "Guardar",
                            style = if (denseMode) {
                                MaterialTheme.typography.titleSmall
                            } else {
                                MaterialTheme.typography.titleMedium
                            },
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = horizontalPadding),
        ) {
            Spacer(modifier = Modifier.height(if (denseMode) AvoqadoTheme.spacing.sm else AvoqadoTheme.spacing.md))

            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI)
                    contactPickerLauncher.launch(intent)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Text(
                    text = "Importar desde Contactos",
                    style = if (denseMode) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(vertical = AvoqadoTheme.spacing.xs),
                )
            }

            Spacer(modifier = Modifier.height(verticalSpacing))

            Row(modifier = Modifier.fillMaxWidth()) {
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

            Spacer(modifier = Modifier.height(verticalSpacing))

            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text("Teléfono") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            )

            Spacer(modifier = Modifier.height(verticalSpacing))

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

    if (showErrorAlert && error != null) {
        AvoqadoDialog(
            title = "No se pudo crear el cliente",
            onDismiss = {
                showErrorAlert = false
                viewModel.clearError()
            },
            actionButton = {
                PrimaryButton(
                    text = "Entendido",
                    onClick = {
                        showErrorAlert = false
                        viewModel.clearError()
                    },
                    fullWidth = true,
                )
            },
        ) {
            Text(
                text = error ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
