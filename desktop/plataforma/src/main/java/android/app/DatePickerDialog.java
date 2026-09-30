package android.app;

import android.content.Context;
import android.widget.DatePicker;

/** Sustituto: el calendario nativo de Android no existe en escritorio; se dice en la bitácora y, con ventana, en pantalla. */
public class DatePickerDialog {
    public interface OnDateSetListener { void onDateSet(DatePicker view, int year, int month, int dayOfMonth); }

    public DatePickerDialog(Context context, OnDateSetListener listener, int year, int month, int dayOfMonth) {}

    public void show() {
        com.avoqado.escritorio.Escritorio.INSTANCE.avisarNoDisponible("calendario");
    }
}
