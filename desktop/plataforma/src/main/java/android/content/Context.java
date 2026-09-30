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
    public abstract Context getApplicationContext();
    public abstract File getFilesDir();
    public abstract File getDatabasePath(String name);   // lo usa el DatabaseModule de escritorio (tarea 5)
    public abstract String getPackageName();
    public abstract PackageManager getPackageManager();
    public abstract Resources getResources();
    public abstract ContentResolver getContentResolver();
    public abstract void startActivity(Intent intent);
}
