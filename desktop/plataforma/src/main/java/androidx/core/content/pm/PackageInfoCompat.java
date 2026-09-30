package androidx.core.content.pm;

import android.content.pm.PackageInfo;

public final class PackageInfoCompat {
    private PackageInfoCompat() {}
    public static long getLongVersionCode(PackageInfo info) { return info.getLongVersionCode(); }
}
