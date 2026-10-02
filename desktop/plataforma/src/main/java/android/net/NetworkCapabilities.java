package android.net;

import java.util.Set;

/**
 * Sustituto. La red activa ({@link Network#UNICA}) se reporta como cable con internet (el servidor real lo confirma
 * ConnectivityMonitor). Una red por interfaz ({@link ConnectivityManager#getAllNetworks()}) trae los transportes que
 * le tocan: WiFi, cable o ninguno (adaptador virtual, VPN).
 */
public class NetworkCapabilities {
    public static final int NET_CAPABILITY_INTERNET = 12;
    public static final int TRANSPORT_WIFI = 1;
    public static final int TRANSPORT_ETHERNET = 3;

    private final Set<Integer> transportes;

    public NetworkCapabilities() { this(Set.of(TRANSPORT_ETHERNET)); }

    NetworkCapabilities(Set<Integer> transportes) { this.transportes = Set.copyOf(transportes); }

    public boolean hasCapability(int capability) { return capability == NET_CAPABILITY_INTERNET; }
    public boolean hasTransport(int transportType) { return transportes.contains(transportType); }
}
