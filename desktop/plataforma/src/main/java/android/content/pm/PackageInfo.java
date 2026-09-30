package android.content.pm;

public class PackageInfo {
    public String versionName;
    // ponytail: el versionCode de Android no existe en escritorio; 0 hasta que el instalador (tarea 9) traiga uno.
    public long getLongVersionCode() { return 0; }
}
