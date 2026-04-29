package com.avoqado.pos.reservations.presentation.create.steps

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.avoqado.pos.reservations.presentation.create.CreateReservationViewModel

@Composable
fun CustomerStep(viewModel: CreateReservationViewModel) {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Text("Paso Cliente — pendiente (T6)")
    }
}
