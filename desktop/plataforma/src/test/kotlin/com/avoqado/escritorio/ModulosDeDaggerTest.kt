package com.avoqado.escritorio

import com.google.inject.CreationException
import com.google.inject.Guice
import com.google.inject.Key
import com.google.inject.ProvisionException
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame

interface Saludo { fun hola(): String }
class SaludoReal @Inject constructor(@Named("nombre") private val nombre: String) : Saludo {
    override fun hola() = "hola $nombre"
}
class Contador
class Reloj
class Pantalla @Inject constructor(@ApplicationContext val contexto: String, val relojes: Provider<Reloj>)

@Module object ModuloObjeto {
    @Provides @Named("nombre") fun nombre(): String = "Avoqado"
    @Provides @Singleton fun contador(): Contador = Contador()
    @Provides fun reloj(): Reloj = Reloj()
    @Provides @ApplicationContext fun contexto(): String = "contexto de escritorio"
}

@Module abstract class ModuloAbstracto {
    @Binds abstract fun saludo(s: SaludoReal): Saludo
    companion object {
        @Provides fun largo(@Named("nombre") n: String): Int = n.length
    }
}

@Module object ModuloQueFalla {
    @Provides fun rota(): Contador = error("sin base de datos")
}

interface PuntoDeEntrada { fun saludo(): Saludo }

class ModulosDeDaggerTest {
    private val inyector = Guice.createInjector(ModulosDeDagger(ModuloObjeto::class.java, ModuloAbstracto::class.java))

    @Test fun `provides de un object con calificador Named`() {
        // Guice trata javax.inject.Named y com.google.inject.name.Named como la misma llave.
        assertEquals("Avoqado", inyector.getInstance(Key.get(String::class.java, com.google.inject.name.Names.named("nombre"))))
    }

    @Test fun `singleton da la misma instancia y sin alcance da una nueva`() {
        assertSame(inyector.getInstance(Contador::class.java), inyector.getInstance(Contador::class.java))
        assertNotSame(inyector.getInstance(Reloj::class.java), inyector.getInstance(Reloj::class.java))
    }

    @Test fun `binds y companion de una clase abstracta`() {
        assertEquals("hola Avoqado", inyector.getInstance(Saludo::class.java).hola())
        assertEquals(7, inyector.getInstance(Int::class.javaObjectType))
    }

    @Test fun `ApplicationContext es un calificador y Provider funciona`() {
        val p = inyector.getInstance(Pantalla::class.java)
        assertEquals("contexto de escritorio", p.contexto)
        assertNotSame(p.relojes.get(), p.relojes.get())
    }

    @Test fun `punto de entrada contesta con el inyector`() {
        Escritorio.instalarInyector(inyector)
        assertEquals("hola Avoqado", Escritorio.puntoDeEntrada(PuntoDeEntrada::class.java).saludo().hola())
    }

    @Test fun `un Provides que truena deja ver su causa real`() {
        val e = assertFailsWith<ProvisionException> {
            Guice.createInjector(ModulosDeDagger(ModuloQueFalla::class.java)).getInstance(Contador::class.java)
        }
        assertEquals("sin base de datos", e.cause?.message)
    }

    @Test fun `un binding faltante truena al armar el inyector, no despues`() {
        class NecesitaAlgo @Inject constructor(val saludo: Saludo)
        assertFailsWith<CreationException> {
            Guice.createInjector(ModulosDeDagger(ModuloObjeto::class.java), com.google.inject.Module { it.getProvider(NecesitaAlgo::class.java) })
        }
    }
}
