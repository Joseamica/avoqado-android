package android.net;

import java.net.InetAddress;

/** Sustituto: una dirección de la interfaz y el tamaño de su red (prefijo), sacados de java.net.InterfaceAddress. */
public final class LinkAddress {
    private final InetAddress direccion;
    private final int prefijo;

    LinkAddress(InetAddress direccion, int prefijo) { this.direccion = direccion; this.prefijo = prefijo; }

    public InetAddress getAddress() { return direccion; }
    public int getPrefixLength() { return prefijo; }
    @Override public String toString() { return direccion.getHostAddress() + "/" + prefijo; }
}
