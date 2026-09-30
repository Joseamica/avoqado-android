package android.content;

import android.net.Uri;

/** Sustituto: acción + Uri. En escritorio ningún broadcast llega nunca; ACTION_VIEW abre el navegador (AccionesDeEscritorio). */
public class Intent {
    public static final String ACTION_VIEW = "android.intent.action.VIEW";
    public static final String ACTION_PICK = "android.intent.action.PICK";

    private final String action;
    private final Uri data;

    public Intent(String action, Uri uri) { this.action = action; this.data = uri; }
    public String getAction() { return action; }
    public Uri getData() { return data; }
}
