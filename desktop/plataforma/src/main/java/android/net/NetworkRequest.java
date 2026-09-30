package android.net;

/** Sustituto: la petición no filtra nada (sólo hay una red). */
public class NetworkRequest {
    public static class Builder {
        public Builder addCapability(int capability) { return this; }
        public Builder addTransportType(int transportType) { return this; }
        public Builder removeCapability(int capability) { return this; }
        public NetworkRequest build() { return new NetworkRequest(); }
    }
}
