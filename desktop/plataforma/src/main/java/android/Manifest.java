package android;

/** Sustituto: los permisos que la app pregunta. En escritorio todos están concedidos (ContextCompat). */
public final class Manifest {
    private Manifest() {}
    public static final class permission {
        private permission() {}
        public static final String CAMERA = "android.permission.CAMERA";
        public static final String POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS";
        public static final String BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT";
        public static final String BLUETOOTH_SCAN = "android.permission.BLUETOOTH_SCAN";
    }
}
