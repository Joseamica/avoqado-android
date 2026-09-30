package com.avoqado.pos.escritorio

import android.content.Context
import com.avoqado.escritorio.ModulosDeDagger
import com.avoqado.pos.core.di.DatabaseModule
import com.avoqado.pos.core.di.NetworkModule
import com.avoqado.pos.core.di.StorageModule
import com.avoqado.pos.inventory.di.InventoryModule
import com.avoqado.pos.payment.di.CancelacionDeCobroModule
import com.avoqado.pos.referrals.di.ReferralsModule
import com.avoqado.pos.reservations.di.ReservationModule
import com.google.inject.AbstractModule
import com.google.inject.Guice
import com.google.inject.Injector
import com.google.inject.Key
import dagger.hilt.android.qualifiers.ApplicationContext

/** Los 7 @Module de Hilt de la app (DatabaseModule = el reemplazo de escritorio con el mismo nombre) + el contexto. */
object Inyector {
    val modulosDeLaApp: Array<Class<*>> = arrayOf(
        NetworkModule::class.java, DatabaseModule::class.java, StorageModule::class.java, ReservationModule::class.java,
        CancelacionDeCobroModule::class.java, ReferralsModule::class.java, InventoryModule::class.java,
    )

    fun crear(contexto: Context): Injector = Guice.createInjector(
        object : AbstractModule() {
            override fun configure() {
                binder().disableCircularProxies()        // Hilt no permite ciclos: que truene igual que allá
                binder().requireAtInjectOnConstructors() // Hilt exige @Inject: sin esto Guice inventaría objetos
                bind(Context::class.java).toInstance(contexto)
                bind(Key.get(Context::class.java, ApplicationContext::class.java)).toInstance(contexto)
            }
        },
        ModulosDeDagger(*modulosDeLaApp),
    )
}
