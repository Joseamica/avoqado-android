package com.avoqado.pos.pos.presentation.customer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
fun CustomersScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Clientes",
            style = MaterialTheme.typography.headlineLarge,
        )
    }
}

@Composable
fun CreateCustomerScreen(
    onDismiss: () -> Unit,
    onCustomerCreated: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Crear Cliente",
            style = MaterialTheme.typography.headlineLarge,
        )
    }
}
