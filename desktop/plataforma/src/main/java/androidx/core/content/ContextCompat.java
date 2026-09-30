package androidx.core.content;

import android.content.Context;
import android.content.pm.PackageManager;

/** Sustituto: en escritorio no hay permisos que pedir; todo está concedido. */
public final class ContextCompat {
    private ContextCompat() {}
    public static int checkSelfPermission(Context context, String permission) { return PackageManager.PERMISSION_GRANTED; }
}
