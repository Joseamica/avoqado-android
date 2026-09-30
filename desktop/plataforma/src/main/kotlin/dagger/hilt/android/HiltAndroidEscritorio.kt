package dagger.hilt.android

import com.avoqado.escritorio.Escritorio

@Target(AnnotationTarget.CLASS) @Retention(AnnotationRetention.RUNTIME) annotation class AndroidEntryPoint
@Target(AnnotationTarget.CLASS) @Retention(AnnotationRetention.RUNTIME) annotation class HiltAndroidApp

/** Sustituto: un @EntryPoint se contesta con un proxy que le pide cada método al inyector. */
object EntryPointAccessors {
    @JvmStatic fun <T : Any> fromApplication(context: android.content.Context, entryPoint: Class<T>): T = Escritorio.puntoDeEntrada(entryPoint)
    @JvmStatic fun <T : Any> fromActivity(activity: android.content.Context, entryPoint: Class<T>): T = Escritorio.puntoDeEntrada(entryPoint)
    inline fun <reified T : Any> fromApplication(context: android.content.Context): T = fromApplication(context, T::class.java)
}
