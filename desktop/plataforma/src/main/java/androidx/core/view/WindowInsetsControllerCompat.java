package androidx.core.view;

/** Sustituto no-op: recuerda lo que le piden, pero no hay barras que ocultar ni colorear. */
public final class WindowInsetsControllerCompat {
    public static final int BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE = 2;
    private boolean appearanceLightStatusBars;
    private int systemBarsBehavior;

    public boolean isAppearanceLightStatusBars() { return appearanceLightStatusBars; }
    public void setAppearanceLightStatusBars(boolean isLight) { appearanceLightStatusBars = isLight; }
    public int getSystemBarsBehavior() { return systemBarsBehavior; }
    public void setSystemBarsBehavior(int behavior) { systemBarsBehavior = behavior; }
    public void hide(int types) {}
}
