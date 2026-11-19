package android.view;

public class KeyCharacterMap {
    public static final int VIRTUAL_KEYBOARD = -1;

    /**
     * D14: en escritorio no hay distribución de teclado de Android que traducir (la pistola de WINDOWS entra por
     * LectorDePistola). {@link #get} devuelve 0 y LectorHidBus usa el carácter tal como llegó.
     */
    public static KeyCharacterMap load(int deviceId) {
        return new KeyCharacterMap();
    }

    public int get(int keyCode, int metaState) {
        return 0;
    }
}
