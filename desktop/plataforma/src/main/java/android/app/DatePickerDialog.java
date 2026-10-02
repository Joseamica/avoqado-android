package android.app;

import android.content.Context;
import android.widget.DatePicker;
import com.avoqado.escritorio.calendario.CalendarioDeEscritorio;
import com.avoqado.escritorio.calendario.FechaDeAndroid;
import com.avoqado.escritorio.calendario.SolicitudDeCalendario;
import kotlin.Unit;

/** Sustituto: el calendario nativo de Android, dibujado con el DatePicker de Material3 en la ventana (CalendarioPendiente). */
public class DatePickerDialog {
    public interface OnDateSetListener { void onDateSet(DatePicker view, int year, int month, int dayOfMonth); }

    private final OnDateSetListener listener;
    private final FechaDeAndroid inicial;

    public DatePickerDialog(Context context, OnDateSetListener listener, int year, int month, int dayOfMonth) {
        this.listener = listener;
        this.inicial = new FechaDeAndroid(year, month, dayOfMonth);
    }

    public void show() {
        CalendarioDeEscritorio.INSTANCE.pedir(new SolicitudDeCalendario(inicial, fecha -> {
            if (listener != null) listener.onDateSet(new DatePicker(), fecha.getAnio(), fecha.getMes0(), fecha.getDia());
            return Unit.INSTANCE;
        }));
    }
}
