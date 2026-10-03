package android.net.nsd;

import android.util.Log;
import com.avoqado.escritorio.red.RedLocalMdns;

/**
 * Sustituto: el Hub LAN (`_avoqado-pos._tcp`) anuncia, busca y resuelve por mDNS de verdad (jmDNS, en la tarjeta del local:
 * RedLocalMdns); para impresoras crudas (`_pdl-datastream._tcp`) barre el 9100 de las subredes privadas del equipo. Cualquier
 * otro tipo no encuentra a nadie y lo dice en la bitácora.
 */
public final class NsdManager {
    public static final int PROTOCOL_DNS_SD = 1;
    public static final int FAILURE_INTERNAL_ERROR = 0;

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

    // --- Hub LAN (`_avoqado-pos._tcp`): mDNS de verdad sobre jmDNS (RedLocalMdns). Estáticos por lo mismo que BARRIDOS. ---
    // Cada operación se guarda como «pedido» ANTES de pedirla y se llena después: su respuesta puede llegar antes de que
    // vuelva la llamada, y sólo se entrega si el pedido sigue guardado (un stop/unregister lo saca).
    private static final class PedidoDeAnuncio { RedLocalMdns.Anuncio anuncio; }
    private static final class PedidoDeBusqueda { final String tipo; RedLocalMdns.Busqueda busqueda; PedidoDeBusqueda(String t) { tipo = t; } }
    private static final class PedidoDeResolucion { RedLocalMdns.Resolucion resolucion; }
    private static final java.util.Map<RegistrationListener, PedidoDeAnuncio> ANUNCIOS = new java.util.HashMap<>();
    private static final java.util.Map<DiscoveryListener, PedidoDeBusqueda> BUSQUEDAS = new java.util.HashMap<>();
    private static final java.util.Map<ResolveListener, PedidoDeResolucion> RESOLUCIONES = new java.util.HashMap<>();

    private static RedLocalMdns red() { return RedLocalMdns.Companion.actual(); }

    public void registerService(NsdServiceInfo serviceInfo, int protocolType, RegistrationListener listener) {
        String tipo = serviceInfo.getServiceType();
        if (!esHubLan(tipo)) {
            Log.w("Escritorio", "No disponible en Windows todavía: anunciar en la red local (" + tipo + ")");
            return;
        }
        java.util.Map<String, String> txt = new java.util.LinkedHashMap<>();
        serviceInfo.getAttributes().forEach((k, v) -> txt.put(k, v == null ? "" : new String(v, java.nio.charset.StandardCharsets.UTF_8)));
        PedidoDeAnuncio pedido = new PedidoDeAnuncio();
        synchronized (ANUNCIOS) {
            ANUNCIOS.put(listener, pedido);
            pedido.anuncio = red().anunciar(tipo, serviceInfo.getServiceName(), serviceInfo.getPort(), txt,
                nombreFinal -> {
                    synchronized (ANUNCIOS) { if (ANUNCIOS.get(listener) != pedido) return kotlin.Unit.INSTANCE; }
                    NsdServiceInfo registrado = new NsdServiceInfo();
                    registrado.setServiceName(nombreFinal);
                    registrado.setServiceType(tipo);
                    registrado.setPort(serviceInfo.getPort());
                    listener.onServiceRegistered(registrado);
                    return kotlin.Unit.INSTANCE;
                },
                error -> {
                    synchronized (ANUNCIOS) { if (ANUNCIOS.get(listener) != pedido) return kotlin.Unit.INSTANCE; ANUNCIOS.remove(listener); }
                    listener.onRegistrationFailed(serviceInfo, FAILURE_INTERNAL_ERROR);
                    return kotlin.Unit.INSTANCE;
                });
        }
    }

    public void unregisterService(RegistrationListener listener) {
        PedidoDeAnuncio pedido;
        synchronized (ANUNCIOS) { pedido = ANUNCIOS.remove(listener); }
        if (pedido != null && pedido.anuncio != null) red().retirar(pedido.anuncio);
    }

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
        if (esHubLan(serviceType)) {
            // Guardada ANTES de avisar «iniciado» (y el aviso va por el hilo de avisos, antes que cualquier encontrado): un
            // listener que se detiene dentro de onDiscoveryStarted encuentra su búsqueda.
            synchronized (BUSQUEDAS) {
                if (BUSQUEDAS.containsKey(listener)) return;   // ya busca con este listener
                PedidoDeBusqueda pedido = new PedidoDeBusqueda(serviceType);
                BUSQUEDAS.put(listener, pedido);
                red().enAvisos(() -> { listener.onDiscoveryStarted(serviceType); return kotlin.Unit.INSTANCE; });
                if (BUSQUEDAS.get(listener) != pedido) return;   // se detuvo dentro de onDiscoveryStarted: no se arranca
                pedido.busqueda = red().buscar(serviceType,
                    nombre -> { listener.onServiceFound(encontrado(nombre, serviceType)); return kotlin.Unit.INSTANCE; },
                    nombre -> { listener.onServiceLost(encontrado(nombre, serviceType)); return kotlin.Unit.INSTANCE; });
            }
            return;
        }
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
        PedidoDeBusqueda pedido;
        synchronized (BUSQUEDAS) { pedido = BUSQUEDAS.remove(listener); }
        if (pedido != null) {
            if (pedido.busqueda != null) red().detener(pedido.busqueda);
            // Por el MISMO hilo de avisos que «iniciado»: nunca llega «detenido» antes que «iniciado».
            red().enAvisos(() -> { listener.onDiscoveryStopped(pedido.tipo); return kotlin.Unit.INSTANCE; });
            return;
        }
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

    /**
     * Lo que encontró el barrido de impresoras ya trae IP y puerto: se resuelve en el acto. Un POS del Hub LAN se pregunta por
     * mDNS (hasta 3 s) y se entrega en un NsdServiceInfo NUEVO: el encontrado no se toca, porque LanDiscovery lo vuelve a
     * resolver cada minuto y, con host y puerto puestos, caería en el atajo de arriba con el TXT viejo.
     */
    public void resolveService(NsdServiceInfo serviceInfo, ResolveListener listener) {
        if (serviceInfo.getHost() != null && serviceInfo.getPort() > 0) { listener.onServiceResolved(serviceInfo); return; }
        String tipo = serviceInfo.getServiceType();
        if (!esHubLan(tipo)) return;
        String nombre = serviceInfo.getServiceName();
        PedidoDeResolucion pedido = new PedidoDeResolucion();
        synchronized (RESOLUCIONES) {
            RESOLUCIONES.put(listener, pedido);
            pedido.resolucion = red().resolver(tipo, nombre,
                resuelto -> {
                    // Sólo si sigue pedida: un stopServiceResolution que llegó mientras tanto la sacó del mapa.
                    synchronized (RESOLUCIONES) { if (RESOLUCIONES.get(listener) != pedido) return kotlin.Unit.INSTANCE; RESOLUCIONES.remove(listener); }
                    NsdServiceInfo info = encontrado(nombre, tipo);
                    info.setHost(resuelto.getHost());
                    info.setPort(resuelto.getPuerto());
                    resuelto.getTxt().forEach(info::setAttribute);
                    listener.onServiceResolved(info);
                    return kotlin.Unit.INSTANCE;
                },
                () -> {
                    synchronized (RESOLUCIONES) { if (RESOLUCIONES.get(listener) != pedido) return kotlin.Unit.INSTANCE; RESOLUCIONES.remove(listener); }
                    listener.onResolveFailed(serviceInfo, FAILURE_INTERNAL_ERROR);
                    return kotlin.Unit.INSTANCE;
                });
        }
    }

    private static NsdServiceInfo encontrado(String nombre, String tipo) {
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(nombre);
        info.setServiceType(tipo);
        return info;
    }

    private static boolean esHubLan(String tipo) {
        return tipo != null && tipo.replaceAll("^\\.|\\.$", "").equals("_avoqado-pos._tcp");
    }

    private static boolean esImpresoraCruda(String tipo) {
        return tipo != null && tipo.replaceAll("\\.$", "").equals("_pdl-datastream._tcp");
    }
    public void stopServiceResolution(ResolveListener listener) {
        PedidoDeResolucion pedido;
        synchronized (RESOLUCIONES) { pedido = RESOLUCIONES.remove(listener); }
        if (pedido != null && pedido.resolucion != null) red().cancelar(pedido.resolucion);
    }
}
