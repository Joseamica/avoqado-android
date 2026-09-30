package androidx.security.crypto;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * 🔴 Sustituto SIN CIFRAR: devuelve las preferencias normales del archivo con ese nombre. El token de sesión queda en
 * claro en la carpeta de datos del usuario. Cifrarlo con DPAPI (Windows) / Keychain (Mac) es de la fase siguiente.
 */
public final class EncryptedSharedPreferences {
    public enum PrefKeyEncryptionScheme { AES256_SIV }
    public enum PrefValueEncryptionScheme { AES256_GCM }

    private EncryptedSharedPreferences() {}

    public static SharedPreferences create(Context context, String fileName, MasterKey masterKey,
                                           PrefKeyEncryptionScheme prefKeyEncryptionScheme,
                                           PrefValueEncryptionScheme prefValueEncryptionScheme) {
        Log.w("Escritorio", "No disponible en Windows todavía: preferencias cifradas; «" + fileName + "» se guarda SIN cifrar");
        return context.getSharedPreferences(fileName, Context.MODE_PRIVATE);
    }
}
