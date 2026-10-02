package android.graphics;

import android.content.res.Resources;
import android.util.Log;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;

/** Sustituto con ImageIO (PNG, JPEG, GIF, BMP). Como en Android, lo que no se puede leer devuelve null. */
public class BitmapFactory {
    public static Bitmap decodeFile(String pathName) {
        try { return envolver(ImageIO.read(new File(pathName))); } catch (IOException e) { return null; }
    }

    public static Bitmap decodeByteArray(byte[] data, int offset, int length) {
        try { return envolver(ImageIO.read(new ByteArrayInputStream(data, offset, length))); } catch (IOException e) { return null; }
    }

    /**
     * La ruta del recurso la da la tabla que :pos registra en RecursosDeImagen (los ids de R viven allá). Como en Android,
     * lo que no se puede decodificar devuelve null: nunca lanza.
     */
    public static Bitmap decodeResource(Resources res, int id) {
        try {
            String ruta = com.avoqado.escritorio.RecursosDeImagen.rutaDe.invoke(id);
            if (ruta != null) {
                ClassLoader cargador = Thread.currentThread().getContextClassLoader();
                if (cargador == null) cargador = BitmapFactory.class.getClassLoader();
                try (InputStream flujo = cargador.getResourceAsStream(ruta)) {
                    if (flujo != null) {
                        Bitmap bitmap = envolver(ImageIO.read(flujo));
                        if (bitmap != null) return bitmap;
                    }
                }
            }
        } catch (Exception e) {
            // se cae al aviso de abajo
        }
        Log.w("Escritorio", "No disponible en Windows todavía: imagen de recurso " + id + " (el ticket sale sin ella)");
        return null;
    }

    private static Bitmap envolver(BufferedImage leida) {
        if (leida == null) return null;
        if (leida.getType() == BufferedImage.TYPE_INT_ARGB) return new Bitmap(leida);
        BufferedImage argb = new BufferedImage(leida.getWidth(), leida.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = argb.createGraphics();
        try { g.drawImage(leida, 0, 0, null); } finally { g.dispose(); }
        return new Bitmap(argb);
    }
}
