package android.os;

/** Sustituto: relojes monótonos de la JVM (como en Android, no cambian si alguien mueve la hora de la computadora). */
public final class SystemClock {
    private SystemClock() {}
    public static long uptimeMillis() { return System.nanoTime() / 1_000_000; }
    public static long elapsedRealtime() { return System.nanoTime() / 1_000_000; }
}
