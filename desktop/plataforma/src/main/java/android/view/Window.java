package android.view;

import android.content.Context;

/** Sustituto: no hay barras de sistema que pintar u ocultar. */
public class Window {
    private final View decorView;
    public Window(Context context) { this.decorView = new View(context); }
    public View getDecorView() { return decorView; }
}
