package android.net;

import java.util.Objects;

/**
 * Sustituto: {@link #UNICA} es «la red de la computadora» (la activa, la de los avisos de conexión); además
 * {@link ConnectivityManager#getAllNetworks()} da una por interfaz, que se reconoce por su nombre.
 */
public final class Network {
    public static final Network UNICA = new Network(null);

    /** El nombre de la interfaz (NetworkInterface#getName), o null para {@link #UNICA}. */
    final String interfaz;

    Network(String interfaz) { this.interfaz = interfaz; }

    @Override public boolean equals(Object o) { return o instanceof Network && Objects.equals(((Network) o).interfaz, interfaz); }
    @Override public int hashCode() { return Objects.hashCode(interfaz); }
    @Override public String toString() { return interfaz == null ? "red-de-la-computadora" : interfaz; }
}
