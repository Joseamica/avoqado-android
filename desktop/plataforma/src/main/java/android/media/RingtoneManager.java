package android.media;

import android.content.Context;
import android.net.Uri;

/** Sustituto: el «sonido de notificación» de escritorio lo toca {@link Ringtone} (Windows: el del sistema; si no, el bip). */
public class RingtoneManager {
    public static final int TYPE_NOTIFICATION = 2;
    public static Uri getDefaultUri(int type) { return Uri.parse("content://settings/system/notification_sound"); }
    public static Ringtone getRingtone(Context context, Uri ringtoneUri) { return new Ringtone(); }
}
