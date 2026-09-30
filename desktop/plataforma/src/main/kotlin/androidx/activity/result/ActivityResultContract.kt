package androidx.activity.result

import android.content.Intent

abstract class ActivityResultContract<I, O> {
    /** Escritorio: lo que el contrato devolvería sin sistema Android detrás. */
    abstract fun resultadoEnEscritorio(input: I): O
}

class ActivityResult(val resultCode: Int, val data: Intent?)
