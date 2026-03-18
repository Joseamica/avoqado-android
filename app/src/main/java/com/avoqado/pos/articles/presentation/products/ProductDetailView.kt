package com.avoqado.pos.articles.presentation.products

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.avoqado.pos.articles.data.model.ArticleProduct
import com.avoqado.pos.articles.presentation.ArticlesViewModel

@Composable
fun ProductDetailView(
    product: ArticleProduct?,  // null = create mode
    viewModel: ArticlesViewModel,
    onDismiss: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Product form - TODO")
    }
}
