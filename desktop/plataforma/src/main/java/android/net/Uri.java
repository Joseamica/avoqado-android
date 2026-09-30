package android.net;

/** Sustituto: el texto de la dirección, tal cual (quien necesita las partes usa java.net.URI). */
public final class Uri {
    private final String texto;
    private Uri(String texto) { this.texto = texto; }
    public static Uri parse(String uriString) { return new Uri(uriString); }
    @Override public String toString() { return texto; }
    @Override public boolean equals(Object o) { return o instanceof Uri && ((Uri) o).texto.equals(texto); }
    @Override public int hashCode() { return texto.hashCode(); }
}
