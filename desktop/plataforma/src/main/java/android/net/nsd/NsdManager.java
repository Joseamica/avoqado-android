package android.net.nsd;

import android.util.Log;

/**
 * Sustituto: anunciar y descubrir no encuentra a nadie (el Hub LAN queda en isla; se dice en la bitácora), salvo que
 * para impresoras crudas (`_pdl-datastream._tcp`) barre el 9100 de las subredes privadas del equipo.
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

    /** Un barrido en curso: con qué detenerlo y de qué tipo era (para onDiscoveryStopped). */
    private static final class Barrido {
        final String tipo;
        kotlin.jvm.functions.Function0<kotlin.Unit> detener;   // bajo el candado de BARRIDOS
        Barrido(String tipo) { this.tipo = tipo; }
    }

    /**
     * Estático, compartido por todas las instancias. En la app no hacía falta (ContextoDeEscritorio guarda UN servicio por
     * nombre, así que PrinterService detiene por la misma instancia); sólo blinda el caso de otro contexto (las pruebas).
     * Todo acceso va bajo su candado, y un onServiceFound sólo se entrega si SU barrido sigue registrado: después de
     * onDiscoveryStopped no llega ninguno.
     */
    private static final java.util.Map<DiscoveryListener, Barrido> BARRIDOS = new java.util.HashMap<>();

    public void discoverServices(String serviceType, int protocolType, DiscoveryListener listener) {
        if (!esImpresoraCruda(serviceType)) {
            Log.w("Escritorio", "No disponible en Windows todavía: descubrir en la red local (" + serviceType + ")");
            return;
        }
        stopServiceDiscovery(listener);   // el mismo listener otra vez: el barrido anterior se detiene antes de empezar otro
        Barrido barrido = new Barrido(serviceType);
        // Registrar ANTES de avisar: un listener que se detiene dentro de onDiscoveryStarted tiene que encontrar su barrido.
        synchronized (BARRIDOS) { BARRIDOS.put(listener, barrido); }
        listener.onDiscoveryStarted(serviceType);
        synchronized (BARRIDOS) {
            if (BARRIDOS.get(listener) != barrido) return;   // lo detuvieron dentro de onDiscoveryStarted: no se arranca
        }
        kotlin.jvm.functions.Function0<kotlin.Unit> detener = com.avoqado.escritorio.red.BarridoDeImpresoras.INSTANCE.iniciar(
            ip -> {
                NsdServiceInfo info = new NsdServiceInfo();
                info.setServiceName("Impresora de red " + ip.getHostAddress());
                info.setServiceType(serviceType);
                info.setHost(ip);
                info.setPort(com.avoqado.escritorio.red.BarridoDeImpresoras.INSTANCE.getPuerto());
                synchronized (BARRIDOS) {
                    if (BARRIDOS.get(listener) == barrido) listener.onServiceFound(info);
                }
                return kotlin.Unit.INSTANCE;
            },
            () -> kotlin.Unit.INSTANCE
        );
        boolean sigue;
        synchronized (BARRIDOS) {
            barrido.detener = detener;
            sigue = BARRIDOS.get(listener) == barrido;
        }
        if (!sigue) detener.invoke();   // lo detuvieron mientras arrancaba
    }

    public void stopServiceDiscovery(DiscoveryListener listener) {
        Barrido barrido;
        kotlin.jvm.functions.Function0<kotlin.Unit> detener;
        synchronized (BARRIDOS) {
            barrido = BARRIDOS.remove(listener);
            detener = barrido == null ? null : barrido.detener;
        }
        if (barrido == null) return;   // nunca se registró (el Hub LAN): no hace nada, como antes
        if (detener != null) detener.invoke();
        listener.onDiscoveryStopped(barrido.tipo);
    }

    /** Lo que encontró el barrido ya trae IP y puerto: se resuelve en el acto. */
    public void resolveService(NsdServiceInfo serviceInfo, ResolveListener listener) {
        if (serviceInfo.getHost() != null && serviceInfo.getPort() > 0) listener.onServiceResolved(serviceInfo);
    }

    private static boolean esImpresoraCruda(String tipo) {
        return tipo != null && tipo.replaceAll("\\.$", "").equals("_pdl-datastream._tcp");
    }
    public void stopServiceResolution(ResolveListener listener) {}
}
