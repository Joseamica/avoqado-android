package androidx.activity.result.contract

import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultContract
import com.avoqado.escritorio.Escritorio.avisarNoDisponible

/** Sólo los 4 contratos que la app instancia. */
object ActivityResultContracts {
    class RequestPermission : ActivityResultContract<String, Boolean>() {
        override fun resultadoEnEscritorio(input: String) = true
    }

    class RequestMultiplePermissions : ActivityResultContract<Array<String>, Map<String, Boolean>>() {
        override fun resultadoEnEscritorio(input: Array<String>) = input.associateWith { true }
    }

    // El selector de contactos y de archivos no existen en escritorio: se contesta «cancelado» (ponytail: selector real si Windows lo pide).
    class StartActivityForResult : ActivityResultContract<Intent, ActivityResult>() {
        override fun resultadoEnEscritorio(input: Intent): ActivityResult {
            avisarNoDisponible("elegir de otra app")
            return ActivityResult(0, null)   // 0 = RESULT_CANCELED
        }
    }

    class CreateDocument(@Suppress("unused") private val mimeType: String) : ActivityResultContract<String, Uri?>() {
        override fun resultadoEnEscritorio(input: String): Uri? {
            avisarNoDisponible("guardar PDF")
            return null
        }
    }
}
