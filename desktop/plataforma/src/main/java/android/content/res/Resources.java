package android.content.res;

/** Sustituto: sólo la Configuration, que llena ConfiguracionDeAndroid (tarea 4) con el tamaño real de la ventana. */
public class Resources {
    private final Configuration configuracion = new Configuration();
    public Configuration getConfiguration() { return configuracion; }
}
