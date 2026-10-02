package android.content;

import android.content.pm.PackageManager;
import android.content.res.Resources;
import java.io.File;

/**
 * Sustituto de android.content.Context: sólo lo que la app usa (medido 30-sep, sin los archivos que la tarea 5 excluye).
 * La implementación es ContextoDeEscritorio.
 */
public abstract class Context {
    public static final int MODE_PRIVATE = 0;
    public static final String CONNECTIVITY_SERVICE = "connectivity", WIFI_SERVICE = "wifi", NSD_SERVICE = "servicediscovery",
        BLUETOOTH_SERVICE = "bluetooth";

    public abstract SharedPreferences getSharedPreferences(String name, int mode);
    public abstract Object getSystemService(String name);

    /**
     * Como en Android: traduce la clase al nombre del servicio y pide ESE ({@link #getSystemService(String)}), así por
     * clase y por nombre se recibe la misma instancia. Una clase que no es servicio del sistema ⇒ null.
     */
    public final <T> T getSystemService(Class<T> serviceClass) {
        String nombre = getSystemServiceName(serviceClass);
        return nombre == null ? null : serviceClass.cast(getSystemService(nombre));
    }

    /** Los servicios que escritorio atiende por nombre (ContextoDeEscritorio / ServiciosDeSistema). */
    public String getSystemServiceName(Class<?> serviceClass) {
        if (serviceClass == android.net.ConnectivityManager.class) return CONNECTIVITY_SERVICE;
        if (serviceClass == android.net.nsd.NsdManager.class) return NSD_SERVICE;
        if (serviceClass == android.net.wifi.WifiManager.class) return WIFI_SERVICE;
        if (serviceClass == android.bluetooth.BluetoothManager.class) return BLUETOOTH_SERVICE;
        return null;
    }
    public abstract Context getApplicationContext();
    public abstract File getFilesDir();
    public abstract File getDatabasePath(String name);   // lo usa el DatabaseModule de escritorio (tarea 5)
    public abstract String getPackageName();
    public abstract PackageManager getPackageManager();
    public abstract Resources getResources();
    public abstract ContentResolver getContentResolver();
    public abstract void startActivity(Intent intent);
}
