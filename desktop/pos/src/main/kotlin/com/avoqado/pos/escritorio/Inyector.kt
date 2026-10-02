package com.avoqado.pos.escritorio

import android.content.Context
import com.avoqado.escritorio.ModulosDeDagger
import com.avoqado.pos.BuildConfig
import com.avoqado.pos.core.data.network.PermissionOverrideRepository
import com.avoqado.pos.core.data.network.TokenRefreshAuthenticator
import com.avoqado.pos.core.di.DatabaseModule
import com.avoqado.pos.core.di.NetworkModule
import com.avoqado.pos.core.di.StorageModule
import com.avoqado.pos.inventory.di.InventoryModule
import com.avoqado.pos.payment.di.CancelacionDeCobroModule
import com.avoqado.pos.referrals.di.ReferralsModule
import com.avoqado.pos.reservations.di.ReservationModule
import com.google.inject.AbstractModule
import com.google.inject.Binding
import com.google.inject.Guice
import com.google.inject.Injector
import com.google.inject.Key
import com.google.inject.matcher.AbstractMatcher
import com.google.inject.spi.ProvisionListener
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient

/** Los 7 @Module de Hilt de la app (DatabaseModule = el reemplazo de escritorio con el mismo nombre) + el contexto. */
object Inyector {
    val modulosDeLaApp: Array<Class<*>> = arrayOf(
        NetworkModule::class.java, DatabaseModule::class.java, StorageModule::class.java, ReservationModule::class.java,
        CancelacionDeCobroModule::class.java, ReferralsModule::class.java, InventoryModule::class.java,
    )

    /** Los dos clientes de la API que la app arma POR SU CUENTA (no salen de un @Provides): reciben la guarda al crearse. */
    private val conClientePropio = listOf(TokenRefreshAuthenticator::class.java, PermissionOverrideRepository::class.java)

    /**
     * [servidor] = el backend de este build (el que pasó por `Urls.api`); las pruebas lo apuntan a un servidor local.
     * 🔴 Los TRES clientes de la API llevan [GuardaDeRedirecciones]: el de NetworkModule (todos los repositorios y
     * Retrofit, vía [ModulosDeDagger]), el del refresco de sesión y el de la autorización de gerente.
     */
    fun crear(contexto: Context, servidor: String = BuildConfig.BASE_URL): Injector {
        val guarda = GuardaDeRedirecciones(servidor)
        return Guice.createInjector(
            object : AbstractModule() {
                override fun configure() {
                    binder().disableCircularProxies()        // Hilt no permite ciclos: que truene igual que allá
                    binder().requireAtInjectOnConstructors() // Hilt exige @Inject: sin esto Guice inventaría objetos
                    bind(Context::class.java).toInstance(contexto)
                    bind(Key.get(Context::class.java, ApplicationContext::class.java)).toInstance(contexto)
                    bindListener(
                        object : AbstractMatcher<Binding<*>>() {
                            override fun matches(enlace: Binding<*>): Boolean =
                                conClientePropio.any { it.isAssignableFrom(enlace.key.typeLiteral.rawType) }
                        },
                        object : ProvisionListener {
                            override fun <T> onProvision(provision: ProvisionListener.ProvisionInvocation<T>) {
                                ponerGuarda(provision.provision(), guarda)
                            }
                        },
                    )
                }
            },
            ModulosDeDagger(*modulosDeLaApp) { if (it is OkHttpClient) guarda.en(it) else it },
        )
    }

    /**
     * Al crearse, sus clientes propios pasan a llevar la guarda. `PermissionOverrideRepository.client` es `private val`:
     * se reemplaza por reflexión, y si la app le cambia el nombre o el tipo esto TRUENA (y su prueba también) en vez de
     * dejar un cliente sin guarda en silencio.
     */
    private fun ponerGuarda(objeto: Any?, guarda: GuardaDeRedirecciones) {
        when (objeto) {
            is TokenRefreshAuthenticator -> objeto.refreshHttpClient = guarda.en(objeto.refreshHttpClient)
            is PermissionOverrideRepository -> {
                val campo = runCatching { PermissionOverrideRepository::class.java.getDeclaredField("client") }.getOrNull()
                check(campo != null && campo.type == OkHttpClient::class.java) {
                    "PermissionOverrideRepository ya no tiene su campo «client: OkHttpClient»: escritorio no le puede poner la " +
                        "guarda de redirecciones (revisa Inyector.ponerGuarda)"
                }
                campo.isAccessible = true
                campo.set(objeto, guarda.en(campo.get(objeto) as OkHttpClient))
            }
        }
    }
}
