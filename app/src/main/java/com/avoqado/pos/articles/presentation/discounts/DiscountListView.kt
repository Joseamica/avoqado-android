package com.avoqado.pos.articles.presentation.discounts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.avoqado.pos.articles.presentation.ArticlesViewModel

@Composable
fun DiscountListView(viewModel: ArticlesViewModel) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Descuentos - TODO")
    }
}
