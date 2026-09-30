package android.util;

import com.avoqado.escritorio.Bitacora;

/** Sustituto de android.util.Log: consola + archivo en la carpeta de datos. Sólo los niveles que la app usa. */
public final class Log {
    private Log() {}
    public static int d(String tag, String msg) { return Bitacora.INSTANCE.escribir("D", tag, msg, null); }
    public static int d(String tag, String msg, Throwable tr) { return Bitacora.INSTANCE.escribir("D", tag, msg, tr); }
    public static int i(String tag, String msg) { return Bitacora.INSTANCE.escribir("I", tag, msg, null); }
    public static int i(String tag, String msg, Throwable tr) { return Bitacora.INSTANCE.escribir("I", tag, msg, tr); }
    public static int w(String tag, String msg) { return Bitacora.INSTANCE.escribir("W", tag, msg, null); }
    public static int w(String tag, String msg, Throwable tr) { return Bitacora.INSTANCE.escribir("W", tag, msg, tr); }
    public static int e(String tag, String msg) { return Bitacora.INSTANCE.escribir("E", tag, msg, null); }
    public static int e(String tag, String msg, Throwable tr) { return Bitacora.INSTANCE.escribir("E", tag, msg, tr); }
}
