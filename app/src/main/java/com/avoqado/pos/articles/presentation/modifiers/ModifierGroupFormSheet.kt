package com.avoqado.pos.articles.presentation.modifiers

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.avoqado.pos.articles.data.model.ModifierGroup
import com.avoqado.pos.articles.presentation.ArticlesViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModifierGroupFormSheet(
    group: ModifierGroup?,
    viewModel: ArticlesViewModel,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Modifier group form - TODO")
    }
}
