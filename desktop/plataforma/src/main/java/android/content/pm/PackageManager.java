package android.content.pm;

/** Sustituto: la versión sale de -Davoqado.version; en escritorio no hay permisos que negar. */
public class PackageManager {
    public static final int PERMISSION_GRANTED = 0;

    public static class NameNotFoundException extends Exception {}

    public PackageInfo getPackageInfo(String packageName, int flags) throws NameNotFoundException {
        PackageInfo info = new PackageInfo();
        info.versionName = System.getProperty("avoqado.version", "escritorio");
        return info;
    }
}
