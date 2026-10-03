package android.net;

import android.util.Log;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

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
    /**
     * La red activa ({@link Network#UNICA}): la de la tarjeta del local (WiFi o cable, la misma que usa el Hub LAN); sin
     * tarjeta del local, cable como antes. 🔴 El Hub LAN anuncia «wired» con esto y el cable gana la elección del árbitro: una
     * PC en WiFi que dijera «cable» le ganaría a una tablet que sí lo está. Una interfaz: sus transportes reales.
     */
    public NetworkCapabilities getNetworkCapabilities(Network network) {
        if (network == null) return null;
        if (network.interfaz == null) {
            kotlin.Pair<NetworkInterface, InetAddress> lan = com.avoqado.escritorio.red.RedLocalMdns.interfazDelLocalDeEsteEquipo();
            return lan == null ? new NetworkCapabilities() : capacidadesDe(lan.getFirst());
        }
        NetworkInterface i = interfazDe(network);
        return i == null ? null : capacidadesDe(i);
    }

    // --- Una red por interfaz: para la app que barre la red del local (BuscadorDeImpresoraEnLan) ---

    /** Una por interfaz activa, que no es loopback y tiene al menos una IPv4 con prefijo válido (también las virtuales). */
    public Network[] getAllNetworks() {
        List<Network> redes = new ArrayList<>();
        try {
            for (NetworkInterface i : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (i.isUp() && !i.isLoopback() && !direccionesIPv4(i).isEmpty()) redes.add(new Network(i.getName()));
            }
        } catch (Exception e) {
            Log.w("ConnectivityManager", "No se pudieron leer las interfaces de red: " + e.getClass().getSimpleName());
        }
        return redes.toArray(new Network[0]);
    }

    /** De {@link Network#UNICA}: null (no es una interfaz). De una interfaz que ya no está: null. */
    public LinkProperties getLinkProperties(Network network) {
        NetworkInterface i = interfazDe(network);
        return i == null ? null : new LinkProperties(direccionesIPv4(i));
    }

    private static NetworkInterface interfazDe(Network network) {
        if (network == null || network.interfaz == null) return null;
        try {
            NetworkInterface i = NetworkInterface.getByName(network.interfaz);
            return i != null && i.isUp() ? i : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Sólo IPv4, sin loopback y con prefijo de 1 a 32 (en Windows Java a veces reporta un prefijo imposible: se omite). */
    private static List<LinkAddress> direccionesIPv4(NetworkInterface i) {
        List<LinkAddress> lista = new ArrayList<>();
        for (InterfaceAddress d : i.getInterfaceAddresses()) {
            InetAddress a = d.getAddress();
            int prefijo = d.getNetworkPrefixLength();
            if (a instanceof Inet4Address && !a.isLoopbackAddress() && prefijo >= 1 && prefijo <= 32) lista.add(new LinkAddress(a, prefijo));
        }
        return lista;
    }

    /** Escritorio (no es API de Android): qué es una tarjeta para el Hub LAN — WiFi, cable, o nada si es virtual (RedLocalMdns). */
    public static NetworkCapabilities capacidadesDeLaInterfaz(NetworkInterface i) { return capacidadesDe(i); }

    private static NetworkCapabilities capacidadesDe(NetworkInterface i) {
        boolean conMac;
        try {
            byte[] mac = i.getHardwareAddress();
            conMac = mac != null && mac.length > 0;
        } catch (Exception e) {
            conMac = false;
        }
        return new NetworkCapabilities(transportesDe(i.getName(), i.getDisplayName(), i.isVirtual(), conMac));
    }

    private static final Pattern VIRTUAL = Pattern.compile(
        "vEthernet|Hyper-V|\\bWSL\\b|VirtualBox|VMware|Tailscale|\\bTAP\\b|WireGuard|Bluetooth|Loopback|Virtual|VPN|Tunnel",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern INALAMBRICO = Pattern.compile("Wi-?Fi|WLAN|Wireless|802\\.11", Pattern.CASE_INSENSITIVE);

    /**
     * Qué transportes tiene una interfaz, por su nombre (Windows: «Wi-Fi», o el corto de Java: «wlan0», «eth3») y su
     * descripción (la del adaptador: «Intel(R) Wi-Fi 6 AX201»). Función pura.
     * - VIRTUAL ⇒ ninguno: `isVirtual`, sin dirección física (MAC), o nombre/descripción de máquina virtual, VPN o
     *   Bluetooth (vEthernet, Hyper-V, WSL, VirtualBox, VMware, Tailscale, TAP, WireGuard, Bluetooth, Loopback, Virtual,
     *   VPN, Tunnel). Gana sobre WiFi: «Wi-Fi Direct Virtual Adapter» no es la red del local.
     * - inalámbrica (Wi-Fi, WLAN, Wireless, 802.11) ⇒ WiFi;
     * - cualquier otra tarjeta física ⇒ cable.
     */
    static Set<Integer> transportesDe(String nombre, String descripcion, boolean virtual, boolean conMac) {
        String texto = (nombre == null ? "" : nombre) + " " + (descripcion == null ? "" : descripcion);
        if (virtual || !conMac || VIRTUAL.matcher(texto).find()) return Set.of();
        if (INALAMBRICO.matcher(texto).find()) return Set.of(NetworkCapabilities.TRANSPORT_WIFI);
        return Set.of(NetworkCapabilities.TRANSPORT_ETHERNET);
    }

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
