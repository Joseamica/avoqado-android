package com.google.firebase.crashlytics;

import android.util.Log;

/** Sustituto: sin Firebase en escritorio; las excepciones que la app reportaría van a la bitácora. */
public final class FirebaseCrashlytics {
    private static final FirebaseCrashlytics UNICA = new FirebaseCrashlytics();
    private FirebaseCrashlytics() {}
    public static FirebaseCrashlytics getInstance() { return UNICA; }
    public void recordException(Throwable throwable) { Log.e("Crashlytics", "", throwable); }
    /** Un rastro de la app (en Android va al siguiente reporte de error): aquí, a la bitácora como Log.d (saneado; en producción no se escribe). */
    public void log(String message) { Log.d("Crashlytics", message); }
    public void setCustomKey(String key, String value) {}
    public void setCustomKey(String key, boolean value) {}
}
