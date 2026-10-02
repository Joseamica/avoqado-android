package androidx.security.crypto;

import android.content.Context;
import android.content.SharedPreferences;
import com.avoqado.escritorio.PreferenciasSeguras;

/**
 * Sustituto de escritorio. En Windows: `<carpeta>/shared_prefs/<fileName>.cifrado` con DPAPI del usuario, convertido
 * y verificado desde el `.json` en claro (ver preferenciasCifradas); si algo no se puede leer o verificar lanza
 * PreferenciasIlegibles y la app NO arranca. Fuera de Windows (desarrollo): las preferencias normales, SIN cifrar.
 */
public final class EncryptedSharedPreferences {
    public enum PrefKeyEncryptionScheme { AES256_SIV }
    public enum PrefValueEncryptionScheme { AES256_GCM }

    private EncryptedSharedPreferences() {}

    public static SharedPreferences create(Context context, String fileName, MasterKey masterKey,
                                           PrefKeyEncryptionScheme prefKeyEncryptionScheme,
                                           PrefValueEncryptionScheme prefValueEncryptionScheme) {
        return PreferenciasSeguras.abrir(context, fileName);
    }
}
