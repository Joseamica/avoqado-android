package androidx.security.crypto;

import android.content.Context;

/** Sustituto: no hay llave maestra en escritorio (ver EncryptedSharedPreferences). */
public final class MasterKey {
    public enum KeyScheme { AES256_GCM }

    private MasterKey() {}

    public static final class Builder {
        public Builder(Context context) {}
        public Builder setKeyScheme(KeyScheme keyScheme) { return this; }
        public MasterKey build() { return new MasterKey(); }
    }
}
