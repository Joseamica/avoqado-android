package android.net;

/** Sustituto: en escritorio hay una sola red, «la de la computadora». */
public final class Network {
    public static final Network UNICA = new Network();
    private Network() {}
}
