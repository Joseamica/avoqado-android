package android.net;

import java.util.List;

/** Sustituto: las direcciones IPv4 de UNA interfaz (ver {@link ConnectivityManager#getLinkProperties(Network)}). */
public final class LinkProperties {
    private final List<LinkAddress> direcciones;

    LinkProperties(List<LinkAddress> direcciones) { this.direcciones = List.copyOf(direcciones); }

    public List<LinkAddress> getLinkAddresses() { return direcciones; }
}
