package android.media;

import java.awt.Toolkit;

public class Ringtone {
    public void play() {
        try { Toolkit.getDefaultToolkit().beep(); } catch (Throwable ignorada) { }   // sin audio: callado, como un teléfono en silencio
    }
}
