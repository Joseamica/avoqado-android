package androidx.core.view;

import android.view.View;
import android.view.Window;

/** Sustituto: no hay barras de sistema en escritorio; todo es no-op. */
public final class WindowCompat {
    private WindowCompat() {}
    public static void setDecorFitsSystemWindows(Window window, boolean decorFitsSystemWindows) {}
    public static WindowInsetsControllerCompat getInsetsController(Window window, View view) { return new WindowInsetsControllerCompat(); }
}
