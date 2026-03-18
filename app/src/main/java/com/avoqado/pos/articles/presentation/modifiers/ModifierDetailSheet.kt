package com.avoqado.pos.articles.presentation.modifiers

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.avoqado.pos.articles.data.model.ArticleModifier
import com.avoqado.pos.articles.presentation.ArticlesViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModifierDetailSheet(
    groupId: String,
    modifier: ArticleModifier,
    viewModel: ArticlesViewModel,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Modifier detail - TODO")
    }
}
