package dagger.hilt.android.lifecycle

/** Sustituto: marca los ViewModels que el inyector de escritorio construye (ver ViewModelsSeConstruyenTest). */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class HiltViewModel
