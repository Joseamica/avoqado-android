package android.os;

/** Sustituto: el «aparato» es la computadora; la app ve un Android 14 (SDK 34) para tomar los caminos modernos. */
public final class Build {
    private Build() {}
    public static final String MANUFACTURER = "Avoqado";
    public static final String MODEL = System.getProperty("os.name");

    public static final class VERSION {
        private VERSION() {}
        public static final int SDK_INT = 34;
        public static final String RELEASE = "14";
    }

    public static final class VERSION_CODES {
        private VERSION_CODES() {}
        public static final int S = 31;
        public static final int TIRAMISU = 33;
    }
}
