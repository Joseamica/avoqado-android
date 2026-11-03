package com.avoqado.pos.auth.presentation

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import com.avoqado.pos.designsystem.components.AuthButton
import com.avoqado.pos.designsystem.components.AvoqadoDialog
import com.avoqado.pos.designsystem.components.BackButton
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Error

@Composable
fun SignInFlowScreen(
    onLoginSuccess: () -> Unit,
    onBack: () -> Unit,
    viewModel: SignInViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val activity = LocalContext.current.findFragmentActivity()
    val keyboardController = LocalSoftwareKeyboardController.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding(),
    ) {
        // Custom back button (iOS-style circular)
        Row(
            modifier = Modifier
                .padding(horizontal = AvoqadoTheme.spacing.xl)
                .padding(top = AvoqadoTheme.spacing.md),
        ) {
            BackButton(
                onClick = {
                    if (uiState.step == SignInStep.PASSWORD) {
                        viewModel.goBackToEmail()
                    } else {
                        onBack()
                    }
                },
            )
            Spacer(modifier = Modifier.weight(1f))
        }

        // Content
        //
        // D6 (2-nov): en la tableta Samsung SM-X133 en horizontal, el teclado dejaba «Iniciar sesión» debajo de lo visible:
        // sólo se entraba con «Realiz.» del teclado. Con `reverseScrolling`, cuando el contenido no cabe (teclado abierto)
        // queda anclado ABAJO: se ven el botón y, arriba de él, el campo; lo que se recorta es el título. Sin teclado todo
        // cabe y se ve igual que antes. El margen de arriba va DENTRO del scroll, para que no se coma 40 dp de lo poco que
        // deja el teclado.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 40.dp)
                .imePadding()
                .verticalScroll(rememberScrollState(), reverseScrolling = true)
                .padding(top = 40.dp),
        ) {
            AnimatedContent(
                targetState = uiState.step,
                label = "sign_in_step",
            ) { step ->
                when (step) {
                    SignInStep.EMAIL -> EmailStepContent(
                        email = uiState.email,
                        onEmailChange = viewModel::onEmailChange,
                        isEmailValid = uiState.isEmailValid,
                        isLoading = uiState.isLoading,
                        errorMessage = uiState.errorMessage,
                        canUseBiometric = uiState.canUseBiometric,
                        biometricEmail = uiState.biometricEmail,
                        onNext = {
                            keyboardController?.hide()
                            viewModel.goToPassword()
                        },
                        onBiometricLogin = {
                            activity?.let { viewModel.loginWithBiometric(it, onLoginSuccess) }
                        },
                    )
                    SignInStep.PASSWORD -> PasswordStepContent(
                        email = uiState.email,
                        password = uiState.password,
                        onPasswordChange = viewModel::onPasswordChange,
                        isPasswordVisible = uiState.isPasswordVisible,
                        onTogglePasswordVisibility = viewModel::togglePasswordVisibility,
                        isLoading = uiState.isLoading,
                        errorMessage = uiState.errorMessage,
                        onChangeEmail = viewModel::goBackToEmail,
                        pidiendoEnlace = uiState.pidiendoEnlace,
                        onOlvide = {
                            keyboardController?.hide()
                            viewModel.pedirEnlaceDeContrasena()
                        },
                        onLogin = {
                            keyboardController?.hide()
                            viewModel.loginWithPassword(onLoginSuccess)
                        },
                    )
                }
            }
        }
    }

    // Mismo aviso que iOS («Restablecer contraseña» + OK), exista o no la cuenta.
    uiState.avisoContrasena?.let { aviso ->
        AvoqadoDialog(
            title = "Restablecer contraseña",
            onDismiss = viewModel::cerrarAvisoContrasena,
            actionButton = {
                PrimaryButton(text = "OK", onClick = viewModel::cerrarAvisoContrasena, fullWidth = true)
            },
        ) {
            Text(text = aviso, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}

@Composable
private fun EmailStepContent(
    email: String,
    onEmailChange: (String) -> Unit,
    isEmailValid: Boolean,
    isLoading: Boolean,
    errorMessage: String?,
    canUseBiometric: Boolean,
    biometricEmail: String?,
    onNext: () -> Unit,
    onBiometricLogin: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Iniciar sesión",
            style = MaterialTheme.typography.displayMedium,
        )

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

        // Email field - 52dp height matching iOS
        OutlinedTextField(
            value = email,
            onValueChange = onEmailChange,
            label = { Text("Correo electrónico") },
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            singleLine = true,
            shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(onNext = { if (isEmailValid) onNext() }),
            trailingIcon = {
                if (canUseBiometric) {
                    IconButton(onClick = onBiometricLogin) {
                        Icon(
                            Icons.Filled.Fingerprint,
                            contentDescription = "Iniciar con biometría",
                            modifier = Modifier.size(AvoqadoTheme.dimensions.iconLarge),
                        )
                    }
                }
            },
        )

        if (canUseBiometric && biometricEmail != null) {
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xs))
            Text(
                text = biometricEmail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
            AuthErrorCard(errorMessage = errorMessage)
        }

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

        AuthButton(
            text = "Siguiente",
            onClick = onNext,
            enabled = isEmailValid,
            isLoading = isLoading,
        )
    }
}

@Composable
private fun PasswordStepContent(
    email: String,
    password: String,
    onPasswordChange: (String) -> Unit,
    isPasswordVisible: Boolean,
    onTogglePasswordVisibility: () -> Unit,
    isLoading: Boolean,
    errorMessage: String?,
    onChangeEmail: () -> Unit,
    pidiendoEnlace: Boolean,
    onOlvide: () -> Unit,
    onLogin: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Bienvenido",
            style = MaterialTheme.typography.displayMedium,
        )

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

        // Email + "Cambiar" link (underlined, not blue)
        Row {
            Text(
                text = email,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))
            Text(
                text = "Cambiar",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable(onClick = onChangeEmail),
            )
        }

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

        // Password field - 52dp height matching iOS
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text("Contraseña") },
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            singleLine = true,
            shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
            visualTransformation = if (isPasswordVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onLogin() }),
            trailingIcon = {
                IconButton(onClick = onTogglePasswordVisibility) {
                    Icon(
                        imageVector = if (isPasswordVisible) {
                            Icons.Filled.VisibilityOff
                        } else {
                            Icons.Filled.Visibility
                        },
                        contentDescription = if (isPasswordVisible) "Ocultar" else "Mostrar",
                    )
                }
            },
        )

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))

        // «¿Olvidaste tu contraseña?» (subrayado, como iOS). Antes era sólo texto: tocarlo no hacía nada (1-nov-2026).
        if (pidiendoEnlace) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
            Text(
                text = "¿Olvidaste tu contraseña?",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .clickable(enabled = !isLoading, onClick = onOlvide)
                    .padding(vertical = AvoqadoTheme.spacing.xs),
            )
        }

        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
            AuthErrorCard(errorMessage = errorMessage)
        }

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

        // Rectangular auth button (NOT pill)
        AuthButton(
            text = "Iniciar sesión",
            onClick = onLogin,
            enabled = password.length >= 4,
            isLoading = isLoading,
        )
    }
}

@Composable
private fun AuthErrorCard(errorMessage: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Error.copy(alpha = 0.1f),
                RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
            )
            .padding(AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = Error,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))
        Text(
            text = errorMessage,
            style = MaterialTheme.typography.bodySmall,
            color = Error,
        )
    }
}
