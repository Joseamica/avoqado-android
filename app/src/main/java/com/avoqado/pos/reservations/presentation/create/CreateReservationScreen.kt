package com.avoqado.pos.reservations.presentation.create

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avoqado.pos.designsystem.components.AvoqadoSuccessToast
import com.avoqado.pos.reservations.domain.CreateStep
import com.avoqado.pos.reservations.presentation.create.components.StepperHeader
import com.avoqado.pos.reservations.presentation.create.steps.ConfirmStep
import com.avoqado.pos.reservations.presentation.create.steps.CustomerStep
import com.avoqado.pos.reservations.presentation.create.steps.DateTimeStep
import com.avoqado.pos.reservations.presentation.create.steps.DetailsStep
import com.avoqado.pos.reservations.presentation.create.steps.ServiceStep

@Composable
fun CreateReservationScreen(
    onClose: () -> Unit,
    viewModel: CreateReservationViewModel = hiltViewModel(),
) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val isSubmitting by viewModel.isSubmitting.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()

    var showSuccess by remember { mutableStateOf(false) }
    LaunchedEffect(result) {
        result?.onSuccess { showSuccess = true }
    }

    val canContinue = when (draft.step) {
        CreateStep.CUSTOMER -> draft.canContinueFromCustomer
        CreateStep.SERVICE -> draft.canContinueFromService
        CreateStep.DATETIME -> true
        CreateStep.DETAILS -> true
        CreateStep.CONFIRM -> draft.canSubmit
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            StepperHeader(
                step = draft.step,
                canContinue = canContinue,
                isFirstStep = draft.step == CreateStep.CUSTOMER,
                isLastStep = draft.step == CreateStep.CONFIRM,
                isSubmitting = isSubmitting,
                onBack = viewModel::back,
                onClose = onClose,
                onContinue = {
                    if (draft.step == CreateStep.CONFIRM) viewModel.submit()
                    else viewModel.next()
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (draft.step) {
                CreateStep.CUSTOMER -> CustomerStep(viewModel)
                CreateStep.SERVICE -> ServiceStep(viewModel)
                CreateStep.DATETIME -> DateTimeStep(viewModel)
                CreateStep.DETAILS -> DetailsStep(viewModel)
                CreateStep.CONFIRM -> ConfirmStep(viewModel)
            }
        }
    }

    if (showSuccess) {
        AvoqadoSuccessToast(
            message = "¡Reserva creada!",
            onDismiss = {
                showSuccess = false
                onClose()
            },
        )
    }
}
