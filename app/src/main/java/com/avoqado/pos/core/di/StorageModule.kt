package com.avoqado.pos.core.di

import android.content.Context
import com.avoqado.pos.core.data.local.PreferencesDataStore
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.printing.data.AlmacenDeTexto
import com.avoqado.pos.printing.data.PrefsComoAlmacen
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {

    @Provides
    @Singleton
    fun provideSecureStorage(@ApplicationContext context: Context): SecureStorage {
        return SecureStorage(context)
    }

    @Provides
    @Singleton
    fun providePreferencesDataStore(@ApplicationContext context: Context): PreferencesDataStore {
        return PreferencesDataStore(context)
    }

    /**
     * Dónde vive la comanda que no salió entre arranques de la app. Se provee aquí —y no con un
     * `Context` dentro de la clase— para que el almacén se pueda probar entero en la JVM;
     * `SharedPreferences` es de Android y este repo no tiene Robolectric.
     */
    @Provides
    @Singleton
    fun provideAlmacenDeComandasPendientes(@ApplicationContext context: Context): AlmacenDeTexto {
        return PrefsComoAlmacen(context)
    }
}
