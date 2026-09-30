package android.net.nsd;

import android.util.Log;

/**
 * Sustituto «vacío»: anunciar y descubrir no encuentra a nadie. El Hub LAN queda en isla y las impresoras de red
 * se agregan a mano por IP («degradar, nunca bloquear»). Se dice en la bitácora.
 */
public final class NsdManager {
    public static final int PROTOCOL_DNS_SD = 1;

    public interface RegistrationListener {
        void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode);
        void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode);
        void onServiceRegistered(NsdServiceInfo serviceInfo);
        void onServiceUnregistered(NsdServiceInfo serviceInfo);
    }

    public interface DiscoveryListener {
        void onStartDiscoveryFailed(String serviceType, int errorCode);
        void onStopDiscoveryFailed(String serviceType, int errorCode);
        void onDiscoveryStarted(String serviceType);
        void onDiscoveryStopped(String serviceType);
        void onServiceFound(NsdServiceInfo serviceInfo);
        void onServiceLost(NsdServiceInfo serviceInfo);
    }

    public interface ResolveListener {
        void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode);
        void onServiceResolved(NsdServiceInfo serviceInfo);
    }

    public void registerService(NsdServiceInfo serviceInfo, int protocolType, RegistrationListener listener) {
        Log.w("Escritorio", "No disponible en Windows todavía: anunciar este POS en la red local (" + serviceInfo.getServiceType() + ")");
    }
    public void unregisterService(RegistrationListener listener) {}

    public void discoverServices(String serviceType, int protocolType, DiscoveryListener listener) {
        Log.w("Escritorio", "No disponible en Windows todavía: descubrir en la red local (" + serviceType + ")");
    }
    public void stopServiceDiscovery(DiscoveryListener listener) {}

    public void resolveService(NsdServiceInfo serviceInfo, ResolveListener listener) {}
    public void stopServiceResolution(ResolveListener listener) {}
}
