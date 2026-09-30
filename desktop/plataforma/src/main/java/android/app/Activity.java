package android.app;

import android.content.Context;
import android.content.ContextWrapper;
import android.view.Window;

/** Sustituto: lo que la app usa de una Activity (la ventana y RESULT_OK). */
public class Activity extends ContextWrapper {
    public static final int RESULT_OK = -1;
    private final Window window = new Window(this);
    public Activity(Context base) { super(base); }
    public Window getWindow() { return window; }
}
