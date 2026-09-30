package android.database;

import java.io.Closeable;

/** Sólo el tipo: ContentResolver.query() de escritorio nunca devuelve un cursor (no hay agenda de contactos). */
public interface Cursor extends Closeable {
    boolean moveToFirst();
    int getColumnIndex(String columnName);
    String getString(int columnIndex);
}
