package android.net;

import android.util.Log;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Sustituto con comportamiento real: hay red si alguna interfaz física arriba tiene una dirección que no es
 * loopback ni link-local. Revisa cada 5 s y avisa onAvailable/onLost al cambiar. «WiFi arriba sin internet» se ve
 * como red ARRIBA, igual que en Android: lo detecta ConnectivityMonitor cuando el servidor no contesta.
 * Todo aviso corre en el mismo hilo («red-escritorio»), así que nunca se cruzan.
 */
public class ConnectivityManager {
    public static class NetworkCallback {
        public void onAvailable(Network network) {}
        public void onLost(Network network) {}
    }

    /** Valor = lo último que se le dijo a ese callback (true = onAvailable). */
    private final Map<NetworkCallback, Boolean> oyentes = new ConcurrentHashMap<>();
    private final BooleanSupplier hayInterfaz;
    private volatile boolean arriba;
    private final ScheduledExecutorService reloj = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "red-escritorio"); t.setDaemon(true); return t;
    });

    public ConnectivityManager() { this(ConnectivityManager::interfazArriba, 5_000); }

    /** Para pruebas: quién decide si «hay interfaz arriba». */
    public ConnectivityManager(BooleanSupplier hayInterfaz) { this(hayInterfaz, 5_000); }

    /** Para pruebas: sonda e intervalo inyectables. */
    ConnectivityManager(BooleanSupplier hayInterfaz, long intervaloMs) {
        this.hayInterfaz = hayInterfaz;
        this.arriba = hayInterfaz.getAsBoolean();
        reloj.scheduleWithFixedDelay(this::revisar, intervaloMs, intervaloMs, TimeUnit.MILLISECONDS);
    }

    public Network getActiveNetwork() { return arriba ? Network.UNICA : null; }
    public NetworkCapabilities getNetworkCapabilities(Network network) { return network == null ? null : new NetworkCapabilities(); }

    public void registerNetworkCallback(NetworkRequest request, NetworkCallback callback) {
        oyentes.put(callback, Boolean.FALSE);
        // Se revisa DENTRO del hilo de avisos: si la red cayó o el callback se fue mientras esperaba, no se avisa.
        reloj.execute(() -> { if (arriba) avisar(callback, true); });
    }

    private void revisar() {
        boolean ahora = hayInterfaz.getAsBoolean();
        if (ahora == arriba) return;
        arriba = ahora;
        for (NetworkCallback c : oyentes.keySet()) avisar(c, ahora);
    }

    /** Un callback que truena no apaga al vigilante (una excepción aquí cancelaría el scheduleWithFixedDelay). */
    private void avisar(NetworkCallback c, boolean disponible) {
        Boolean dicho = oyentes.get(c);
        if (dicho == null || dicho == disponible) return;   // ya no está registrado, o ya se le dijo
        oyentes.put(c, disponible);
        try {
            if (disponible) c.onAvailable(Network.UNICA); else c.onLost(Network.UNICA);
        } catch (Exception e) {
            Log.e("ConnectivityManager", "Un callback de red falló en " + (disponible ? "onAvailable" : "onLost"), e);
        }
    }

    private static boolean interfazArriba() {
        try {
            for (NetworkInterface i : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!i.isUp() || i.isLoopback() || i.isVirtual()) continue;
                for (InetAddress a : Collections.list(i.getInetAddresses())) {
                    if (!a.isLoopbackAddress() && !a.isLinkLocalAddress()) return true;
                }
            }
        } catch (Exception ignorada) { }
        return false;
    }
}
