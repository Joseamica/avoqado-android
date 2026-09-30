package android.content;

import android.database.Cursor;
import android.net.Uri;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.URI;

/** Sustituto: no hay proveedores de contenido (contactos) en escritorio; sólo se escribe a archivos locales (file:). */
public class ContentResolver {
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }

    public OutputStream openOutputStream(Uri uri, String mode) throws FileNotFoundException {
        URI destino = URI.create(uri.toString());
        return "file".equals(destino.getScheme()) ? new FileOutputStream(new File(destino)) : null;
    }
}
