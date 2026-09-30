package android.os;

/** Sustituto: un solo «hilo principal», el de Swing (el mismo que Dispatchers.Main). */
public final class Looper {
    private static final Looper PRINCIPAL = new Looper();
    private Looper() {}
    public static Looper getMainLooper() { return PRINCIPAL; }
}
