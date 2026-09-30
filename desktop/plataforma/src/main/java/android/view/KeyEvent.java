package android.view;

/** Sustituto: lo que lee LectorHidBus, con los valores reales de Android. El lector de pistola no se cablea todavía (tarea 7). */
public class KeyEvent {
    public static final int ACTION_DOWN = 0, ACTION_UP = 1;
    public static final int KEYCODE_ALT_LEFT = 57, KEYCODE_ALT_RIGHT = 58, KEYCODE_SHIFT_LEFT = 59, KEYCODE_SHIFT_RIGHT = 60,
        KEYCODE_TAB = 61, KEYCODE_SYM = 63, KEYCODE_ENTER = 66, KEYCODE_CTRL_LEFT = 113, KEYCODE_CTRL_RIGHT = 114,
        KEYCODE_CAPS_LOCK = 115, KEYCODE_META_LEFT = 117, KEYCODE_META_RIGHT = 118, KEYCODE_FUNCTION = 119,
        KEYCODE_NUM_LOCK = 143, KEYCODE_NUMPAD_ENTER = 160;

    private final int action, keyCode;

    public KeyEvent(int action, int code) { this.action = action; this.keyCode = code; }

    public int getAction() { return action; }
    public int getKeyCode() { return keyCode; }
    public int getUnicodeChar() { return 0; }
    public int getDeviceId() { return 0; }
    public long getEventTime() { return 0; }
    public int getRepeatCount() { return 0; }
}
