package android.content;

import android.content.pm.PackageManager;
import android.content.res.Resources;
import java.io.File;

/** Delegación pura, como en Android. */
public class ContextWrapper extends Context {
    private final Context base;
    public ContextWrapper(Context base) { this.base = base; }
    public Context getBaseContext() { return base; }
    @Override public SharedPreferences getSharedPreferences(String name, int mode) { return base.getSharedPreferences(name, mode); }
    @Override public Object getSystemService(String name) { return base.getSystemService(name); }
    @Override public Context getApplicationContext() { return base.getApplicationContext(); }
    @Override public File getFilesDir() { return base.getFilesDir(); }
    @Override public File getDatabasePath(String name) { return base.getDatabasePath(name); }
    @Override public String getPackageName() { return base.getPackageName(); }
    @Override public PackageManager getPackageManager() { return base.getPackageManager(); }
    @Override public Resources getResources() { return base.getResources(); }
    @Override public ContentResolver getContentResolver() { return base.getContentResolver(); }
    @Override public void startActivity(Intent intent) { base.startActivity(intent); }
}
