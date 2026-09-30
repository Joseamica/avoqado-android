package android.net;

/** Sustituto: la red de la computadora se reporta como cable con internet (el servidor real lo confirma ConnectivityMonitor). */
public class NetworkCapabilities {
    public static final int NET_CAPABILITY_INTERNET = 12;
    public static final int TRANSPORT_WIFI = 1;
    public static final int TRANSPORT_ETHERNET = 3;

    public boolean hasCapability(int capability) { return capability == NET_CAPABILITY_INTERNET; }
    public boolean hasTransport(int transportType) { return transportType == TRANSPORT_ETHERNET; }
}
