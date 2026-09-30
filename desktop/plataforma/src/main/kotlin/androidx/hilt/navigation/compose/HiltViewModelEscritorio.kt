package androidx.hilt.navigation.compose

import androidx.compose.runtime.Composable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.SAVED_STATE_REGISTRY_OWNER_KEY
import androidx.lifecycle.VIEW_MODEL_STORE_OWNER_KEY
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.avoqado.escritorio.Escritorio

/**
 * Sustituto de hilt-navigation-compose: el ViewModel lo arma el inyector de escritorio.
 * Dentro de una pantalla de navegación el SavedStateHandle trae los argumentos de la ruta (como en Android);
 * fuera de ella (la raíz) es uno vacío, igual que el de una Activity recién creada.
 */
@Composable
inline fun <reified VM : ViewModel> hiltViewModel(
    viewModelStoreOwner: ViewModelStoreOwner = checkNotNull(LocalViewModelStoreOwner.current) {
        "No hay ViewModelStoreOwner (AppEscritorio debe proveer uno)"
    },
    key: String? = null,
): VM = viewModel(viewModelStoreOwner = viewModelStoreOwner, key = key) {
    Escritorio.crearViewModel(VM::class.java, estadoDe(this))
}

fun estadoDe(extras: CreationExtras): SavedStateHandle =
    if (extras[SAVED_STATE_REGISTRY_OWNER_KEY] != null && extras[VIEW_MODEL_STORE_OWNER_KEY] != null) {
        extras.createSavedStateHandle()
    } else {
        SavedStateHandle()
    }
