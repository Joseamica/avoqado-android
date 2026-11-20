package com.avoqado.pos.maquina.di

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.avoqado.pos.maquina.BovedaDeLaMaquina
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * La bóveda de la credencial de la máquina (R5#3). La máquina sólo se empareja en WINDOWS, donde este archivo se REEMPLAZA
 * (`desktop/pos/excluidos.txt`): allá es DPAPI de máquina. Aquí basta EncryptedSharedPreferences, para que compile y la
 * inyección esté completa.
 */
@Module
@InstallIn(SingletonComponent::class)
object MaquinaModule {
    @Provides
    @Singleton
    fun provideBovedaDeLaMaquina(@ApplicationContext context: Context): BovedaDeLaMaquina = BovedaDeAndroid(context)
}

private class BovedaDeAndroid(private val context: Context) : BovedaDeLaMaquina {
    private val prefs by lazy {
        EncryptedSharedPreferences.create(
            context,
            "avoqado_maquina_boveda",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun guardar(credencial: String) {
        check(prefs.edit().putString(LLAVE, credencial).commit()) { "No se pudo guardar la credencial de la máquina" }
    }

    override fun leer(): String? = prefs.getString(LLAVE, null)

    override fun borrar() {
        prefs.edit().remove(LLAVE).commit()
    }

    private companion object {
        const val LLAVE = "credencial"
    }
}
