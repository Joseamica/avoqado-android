package android.net.wifi;

/** Sustituto: la JVM no tira paquetes multicast; el candado no hace nada más que recordar si se tomó. */
public class WifiManager {
    public static class MulticastLock {
        private volatile boolean tomado;
        public void setReferenceCounted(boolean refCounted) {}
        public void acquire() { tomado = true; }
        public void release() { tomado = false; }
        public boolean isHeld() { return tomado; }
    }

    public MulticastLock createMulticastLock(String tag) { return new MulticastLock(); }
}
