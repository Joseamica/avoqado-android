package android.media;

import android.util.Log;
import java.awt.Toolkit;
import java.io.File;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.concurrent.Executor;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;

/**
 * Sustituto: el «sonido de notificación» de la pantalla de cocina (KDSViewModel.playNotificationSound).
 * En Windows toca el sonido de notificación del sistema ({@code %WINDIR%\Media\Windows Notify System Generic.wav}) en un
 * hilo aparte, para no detener a quien llama. Si el archivo no está, no hay tarjeta de sonido o falla cualquier cosa, suena
 * el bip del sistema; fuera de Windows, el bip. 🔴 Nunca lanza: la cocina sigue aunque no suene nada.
 */
public class Ringtone {
    /** Quién toca el WAV. Lanza si no puede (sin mezclador, archivo que no es audio…): {@link Ringtone} cae al bip. */
    interface Reproductor {
        void tocar(File wav) throws Exception;
        void detener();
    }

    static final String NOMBRE_DEL_SONIDO = "Windows Notify System Generic.wav";
    private static final String TAG = "Sonido";

    private final boolean enWindows;
    private final File wav;
    private final Reproductor reproductor;
    private final Runnable bip;
    private final Executor hilo;

    /** El que crea {@link RingtoneManager#getRingtone}: el Windows de verdad, su carpeta de sonidos y Java Sound. */
    public Ringtone() {
        this(esWindows(), sonidoDeWindows(System.getenv("WINDIR")), new ReproductorDeClip(), Ringtone::bipDelSistema,
            Ringtone::enHiloAparte);
    }

    /** Para pruebas: si es Windows, qué archivo, quién lo toca, qué es «bip» y en qué hilo se toca. */
    Ringtone(boolean enWindows, File wav, Reproductor reproductor, Runnable bip, Executor hilo) {
        this.enWindows = enWindows;
        this.wav = wav;
        this.reproductor = reproductor;
        this.bip = bip;
        this.hilo = hilo;
    }

    public void play() {
        try {
            if (!enWindows || wav == null) {
                bipSinFallar();
                return;
            }
            hilo.execute(this::tocarOBip);
        } catch (Throwable t) {   // ni siquiera se pudo pedir el hilo: al menos el bip
            bipSinFallar();
        }
    }

    /**
     * Detiene el sonido si sonaba. Nunca lanza ni detiene a quien llama: {@code Clip.stop()/close()} pueden esperar al hilo
     * de audio (hasta 2 s en JDK 17), así que se detiene en el mismo tipo de hilo aparte que toca (Codex, auditoría).
     */
    public void stop() {
        try {
            hilo.execute(() -> {
                try {
                    reproductor.detener();
                } catch (Throwable ignorada) { }
            });
        } catch (Throwable ignorada) { }   // ni un hilo para detener: el clip se cierra solo al terminar
    }

    private void tocarOBip() {
        try {
            if (!wav.isFile()) {
                Log.w(TAG, "No está el sonido de Windows (" + wav + "): suena el bip");
                bipSinFallar();
                return;
            }
            reproductor.tocar(wav);
            Log.i(TAG, "Sonido de cocina: " + wav);
        } catch (Throwable t) {
            Log.w(TAG, "No se pudo tocar " + wav + " (" + t.getClass().getSimpleName() + ": " + t.getMessage() + "): suena el bip");
            bipSinFallar();
        }
    }

    private void bipSinFallar() {
        try {
            bip.run();
        } catch (Throwable ignorada) { }   // sin audio ni bip: callado, como un teléfono en silencio
    }

    /** {@code <windir>\Media\Windows Notify System Generic.wav}; sin %WINDIR% (o con uno inválido), null. */
    static File sonidoDeWindows(String windir) {
        if (windir == null || windir.isBlank()) return null;
        try {
            return Paths.get(windir, "Media", NOMBRE_DEL_SONIDO).toFile();
        } catch (RuntimeException e) {
            return null;
        }
    }

    static boolean esWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    static void bipDelSistema() {
        Toolkit.getDefaultToolkit().beep();
    }

    /** Un hilo daemon por sonido: abrir el WAV y el mezclador puede tardar, y un daemon no impide cerrar la app. */
    static void enHiloAparte(Runnable tarea) {
        Thread hilo = new Thread(tarea, "sonido-de-cocina");
        hilo.setDaemon(true);
        hilo.start();
    }

    /** Java Sound: un {@link Clip} por sonido, que se cierra solo al terminar (o con {@link #detener}). */
    static final class ReproductorDeClip implements Reproductor {
        private volatile Clip clip;

        @Override
        public void tocar(File wav) throws Exception {
            Clip nuevo;
            try (AudioInputStream audio = AudioSystem.getAudioInputStream(wav)) {
                nuevo = AudioSystem.getClip();   // sin mezclador: IllegalArgumentException o LineUnavailableException
                try {
                    nuevo.open(audio);
                    // Un WAV válido sin muestras abre un clip que nunca manda STOP: quedaría abierto con su hilo, sin
                    // sonar. Se trata como «no es audio» y cae al bip (Codex, auditoría).
                    if (nuevo.getFrameLength() <= 0) throw new IllegalStateException("el WAV no trae muestras");
                } catch (Throwable t) {
                    nuevo.close();
                    throw t;
                }
            }
            nuevo.addLineListener(evento -> {
                if (evento.getType() == LineEvent.Type.STOP) nuevo.close();
            });
            clip = nuevo;
            nuevo.start();
        }

        @Override
        public void detener() {
            Clip actual = clip;
            if (actual != null) {
                actual.stop();
                actual.close();
            }
        }
    }
}
