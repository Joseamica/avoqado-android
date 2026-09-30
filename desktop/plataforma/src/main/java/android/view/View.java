package android.view;

import android.content.Context;

/** Sustituto: la «vista» detrás de LocalView (tarea 4). No hay jerarquía de vistas ni pantalla que mantener prendida. */
public class View {
    private final Context context;
    private boolean keepScreenOn;

    public View(Context context) { this.context = context; }

    public Context getContext() { return context; }
    public boolean isInEditMode() { return false; }
    /** En Android es el ViewParent; en escritorio nunca es un DialogWindowProvider. */
    public Object getParent() { return null; }
    // ponytail: no impide que Windows apague la pantalla; guardar y leer basta para la cocina hoy.
    public boolean getKeepScreenOn() { return keepScreenOn; }
    public void setKeepScreenOn(boolean keepScreenOn) { this.keepScreenOn = keepScreenOn; }
}
