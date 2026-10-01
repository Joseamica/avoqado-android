package androidx.compose.ui.platform;

import androidx.compose.runtime.Composer;
import androidx.compose.runtime.MutableFloatState;
import kotlin.Unit;
import kotlin.jvm.functions.Function2;

/**
 * Le dice a Compose cuánto mide el teclado en pantalla (el «ime» de los diálogos). En Java y en este paquete porque
 * `InsetsConfig`, `PlatformInsets` y `PlatformInsetsConfig` son `internal` en Kotlin (CMP 1.7.3) y Java los ve públicos.
 * Los diálogos leen `ime` con `DialogProperties.useSoftwareKeyboardInset` (por defecto true).
 */
public final class ConfiguracionDeInsetsDeEscritorio implements InsetsConfig {
    private static InsetsConfig original;

    private final MutableFloatState altoDp;

    private ConfiguracionDeInsetsDeEscritorio(MutableFloatState altoDp) {
        this.altoDp = altoDp;
    }

    /** Idempotente: instalar otra vez no pierde la configuración original. */
    public static synchronized void instalar(MutableFloatState altoDp) {
        if (original == null) original = PlatformInsets_notMobileKt.getPlatformInsetsConfig();
        PlatformInsets_notMobileKt.setPlatformInsetsConfig(new ConfiguracionDeInsetsDeEscritorio(altoDp));
    }

    public static synchronized void desinstalar() {
        if (original == null) return;
        PlatformInsets_notMobileKt.setPlatformInsetsConfig(original);
        original = null;
    }

    // El constructor de PlatformInsets es privado y su sintético no lo ve javac: se usa por reflexión (una vez).
    private static java.lang.reflect.Constructor<PlatformInsets> constructor;

    private static synchronized PlatformInsets conAbajo(float bottom) {
        try {
            if (constructor == null) {
                constructor = PlatformInsets.class.getDeclaredConstructor(float.class, float.class, float.class, float.class);
                constructor.setAccessible(true);
            }
            return constructor.newInstance(0f, 0f, 0f, bottom);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("PlatformInsets cambió en esta versión de Compose", e);
        }
    }

    @Override
    public PlatformInsets getSafeInsets(Composer composer, int changed) {
        return PlatformInsets.Companion.getZero();
    }

    @Override
    public PlatformInsets getIme(Composer composer, int changed) {
        float bottom = altoDp.getFloatValue();   // leído en la composición: recompone a quien lo use
        return bottom <= 0f ? PlatformInsets.Companion.getZero() : conAbajo(bottom);
    }

    @Override
    public void excludeInsets(boolean safeInsets, boolean ime, Function2<? super Composer, ? super Integer, Unit> content, Composer composer, int changed) {
        content.invoke(composer, 0);
    }
}
