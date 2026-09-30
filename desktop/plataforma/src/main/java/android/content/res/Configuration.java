package android.content.res;

/** Sustituto: los 5 campos públicos que la app lee. Como en Android, recién creada vale 0 («sin definir»). */
public class Configuration {
    public static final int ORIENTATION_PORTRAIT = 1, ORIENTATION_LANDSCAPE = 2;
    public int screenWidthDp, screenHeightDp, smallestScreenWidthDp, densityDpi, orientation;

    /** Escritorio: copia los 5 campos (lo usa ConfiguracionDeAndroid, tarea 4). */
    public void copiarDe(Configuration otra) {
        screenWidthDp = otra.screenWidthDp;
        screenHeightDp = otra.screenHeightDp;
        smallestScreenWidthDp = otra.smallestScreenWidthDp;
        densityDpi = otra.densityDpi;
        orientation = otra.orientation;
    }
}
