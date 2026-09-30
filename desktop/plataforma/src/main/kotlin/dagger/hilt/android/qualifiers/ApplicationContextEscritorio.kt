package dagger.hilt.android.qualifiers

/** Sustituto: el MISMO calificador que en Hilt, para que `@ApplicationContext context: Context` pida la llave correcta a Guice. */
@javax.inject.Qualifier
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FUNCTION, AnnotationTarget.FIELD)
annotation class ApplicationContext
