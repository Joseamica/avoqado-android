package android.os;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.Timer;

/** Sustituto: postDelayed corre en el hilo de Swing (el «hilo principal» de escritorio) con un javax.swing.Timer. */
public class Handler {
    private final Map<Runnable, List<Timer>> pendientes = new HashMap<>();

    public Handler(Looper looper) {}

    public boolean postDelayed(Runnable r, long delayMillis) {
        Timer t = new Timer((int) Math.max(0, Math.min(delayMillis, Integer.MAX_VALUE)), null);
        t.setRepeats(false);
        t.addActionListener(e -> {
            synchronized (pendientes) {
                List<Timer> ts = pendientes.get(r);
                if (ts != null && ts.remove(t) && ts.isEmpty()) pendientes.remove(r);
            }
            r.run();
        });
        synchronized (pendientes) { pendientes.computeIfAbsent(r, k -> new ArrayList<>()).add(t); }
        t.start();
        return true;
    }

    public void removeCallbacks(Runnable r) {
        List<Timer> ts;
        synchronized (pendientes) { ts = pendientes.remove(r); }
        if (ts != null) for (Timer t : ts) t.stop();
    }
}
