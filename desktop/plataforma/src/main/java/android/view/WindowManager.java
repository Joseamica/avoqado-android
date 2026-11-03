package android.view;

/** Sustituto: sólo las constantes que la app le pasa a `Window.setSoftInputMode`, con el valor de Android. */
public interface WindowManager {
    final class LayoutParams {
        private LayoutParams() {}
        public static final int SOFT_INPUT_ADJUST_RESIZE = 0x10;
        public static final int SOFT_INPUT_ADJUST_NOTHING = 0x30;
    }
}
