package com.avoqado.escritorio

import com.google.inject.AbstractModule
import com.google.inject.Key
import com.google.inject.Provider
import com.google.inject.Scopes
import dagger.Binds
import dagger.Provides
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Type
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Los @Module de Hilt de la app, leídos TAL CUAL y convertidos en un módulo de Guice.
 * - `object` → sus métodos @Provides sobre la instancia única.
 * - clase abstracta o interfaz → @Binds (parámetro → tipo de retorno) y, si tiene, su `Companion` con @Provides.
 * Respeta @Singleton y los calificadores (@Named, @ApplicationContext). Sin multibindings: la app no usa ninguno (medido 29-sep).
 */
class ModulosDeDagger(private vararg val modulos: Class<*>) : AbstractModule() {
    override fun configure() {
        for (modulo in modulos) {
            // La instancia de un `object` por reflexión de JAVA: `KClass.objectInstance` exige kotlin-reflect.
            val instancia = modulo.declaredFields
                .firstOrNull { it.name == "INSTANCE" && Modifier.isStatic(it.modifiers) && it.type == modulo }?.get(null)
            when {
                instancia != null -> proveedores(instancia)
                Modifier.isAbstract(modulo.modifiers) -> {
                    modulo.declaredMethods.filter { it.isAnnotationPresent(Binds::class.java) }.forEach(::enlazar)
                    modulo.declaredFields.firstOrNull { it.name == "Companion" && Modifier.isStatic(it.modifiers) }
                        ?.get(null)?.let(::proveedores)
                }
                else -> addError("Módulo no soportado (ni object ni abstracto): ${modulo.name}")
            }
        }
    }

    private fun proveedores(instancia: Any) {
        for (metodo in instancia.javaClass.declaredMethods.filter { it.isAnnotationPresent(Provides::class.java) }) {
            val parametros = metodo.genericParameterTypes.indices.map { i ->
                getProvider(llave(metodo.genericParameterTypes[i], metodo.parameterAnnotations[i]))
            }
            val enlace = bind(llave(metodo.genericReturnType, metodo.annotations)).toProvider(
                Provider {
                    // Sin desenvolver, Guice sólo diría «InvocationTargetException» y taparía la causa real del @Provides.
                    try { metodo.invoke(instancia, *parametros.map { it.get() }.toTypedArray()) }
                    catch (e: InvocationTargetException) { throw e.targetException }
                },
            )
            if (metodo.isAnnotationPresent(Singleton::class.java)) enlace.`in`(Scopes.SINGLETON)
        }
    }

    private fun enlazar(metodo: Method) {
        val enlace = bind(llave(metodo.genericReturnType, metodo.annotations))
            .to(llave(metodo.genericParameterTypes.single(), metodo.parameterAnnotations.single()))
        if (metodo.isAnnotationPresent(Singleton::class.java)) enlace.`in`(Scopes.SINGLETON)
    }
}

@Suppress("UNCHECKED_CAST")
internal fun llave(tipo: Type, anotaciones: Array<Annotation>): Key<Any> {
    val calificador = anotaciones.firstOrNull { it.annotationClass.java.isAnnotationPresent(Qualifier::class.java) }
    return (if (calificador != null) Key.get(tipo, calificador) else Key.get(tipo)) as Key<Any>
}
