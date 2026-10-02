package android.net.nsd;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Sustituto: los datos de un servicio mDNS, sólo los campos que la app usa. */
public final class NsdServiceInfo {
    private String serviceName, serviceType;
    private int port;
    private InetAddress host;
    private final Map<String, byte[]> attributes = new HashMap<>();

    public String getServiceName() { return serviceName; }
    public void setServiceName(String s) { serviceName = s; }
    public String getServiceType() { return serviceType; }
    public void setServiceType(String s) { serviceType = s; }
    public int getPort() { return port; }
    public void setPort(int p) { port = p; }
    public InetAddress getHost() { return host; }
    public void setHost(InetAddress h) { host = h; }
    public void setAttribute(String key, String value) { attributes.put(key, value == null ? null : value.getBytes(StandardCharsets.UTF_8)); }
    public Map<String, byte[]> getAttributes() { return attributes; }
}
