package android.media;

import android.content.Context;
import android.net.Uri;

/** Sustituto: el «sonido de notificación» de escritorio es el bip del sistema. */
public class RingtoneManager {
    public static final int TYPE_NOTIFICATION = 2;
    public static Uri getDefaultUri(int type) { return Uri.parse("content://settings/system/notification_sound"); }
    public static Ringtone getRingtone(Context context, Uri ringtoneUri) { return new Ringtone(); }
}
