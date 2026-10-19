package android.graphics;

import android.content.res.Resources;
import android.util.Log;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * Sustituto con ImageIO (PNG, JPEG, GIF, BMP) y, para lo que ImageIO no sabe leer (WebP, que el server acepta como logo),
 * Skia: el mismo decodificador que ya usa CacheDeImagenes. Como en Android, lo que no se puede leer devuelve null.
 */
public class BitmapFactory {
    /** Como en Android: con {@code inJustDecodeBounds} sólo se leen los lados (del encabezado) y se devuelve null. */
    public static class Options {
        public boolean inJustDecodeBounds;
        public int outWidth;
        public int outHeight;
    }

    public static Bitmap decodeFile(String pathName) {
        return decodeFile(pathName, null);
    }

    public static Bitmap decodeFile(String pathName, Options opts) {
        byte[] bytes;
        try { bytes = Files.readAllBytes(Paths.get(pathName)); } catch (IOException | RuntimeException e) { bytes = new byte[0]; }
        return decodeByteArray(bytes, 0, bytes.length, opts);
    }

    public static Bitmap decodeByteArray(byte[] data, int offset, int length) {
        Bitmap bitmap = conImageIO(data, offset, length);
        return bitmap != null ? bitmap : conSkia(data, offset, length);
    }

    public static Bitmap decodeByteArray(byte[] data, int offset, int length, Options opts) {
        if (opts == null) return decodeByteArray(data, offset, length);
        opts.outWidth = -1;
        opts.outHeight = -1;
        if (opts.inJustDecodeBounds) {
            int[] lados = ladosConImageIO(data, offset, length);
            if (lados == null) lados = ladosConSkia(data, offset, length);
            if (lados != null) { opts.outWidth = lados[0]; opts.outHeight = lados[1]; }
            return null;
        }
        Bitmap bitmap = decodeByteArray(data, offset, length);
        if (bitmap != null) { opts.outWidth = bitmap.getWidth(); opts.outHeight = bitmap.getHeight(); }
        return bitmap;
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

    private static Bitmap conImageIO(byte[] data, int offset, int length) {
        try { return envolver(ImageIO.read(new ByteArrayInputStream(data, offset, length))); } catch (Exception e) { return null; }
    }

    /** Skia decodifica y se re-codifica a PNG para que ImageIO arme el BufferedImage: lento, pero sólo para lo raro (WebP). */
    private static Bitmap conSkia(byte[] data, int offset, int length) {
        try {
            org.jetbrains.skia.Image imagen = org.jetbrains.skia.Image.Companion.makeFromEncoded(exactos(data, offset, length));
            try {
                org.jetbrains.skia.Data png = imagen.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG, 100);
                if (png == null) return null;
                try { return envolver(ImageIO.read(new ByteArrayInputStream(png.getBytes()))); } finally { png.close(); }
            } finally {
                imagen.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Sólo el encabezado: un lector de ImageIO no decodifica los pixeles para dar los lados. */
    private static int[] ladosConImageIO(byte[] data, int offset, int length) {
        try (ImageInputStream flujo = ImageIO.createImageInputStream(new ByteArrayInputStream(data, offset, length))) {
            Iterator<ImageReader> lectores = ImageIO.getImageReaders(flujo);
            if (!lectores.hasNext()) return null;
            ImageReader lector = lectores.next();
            try {
                lector.setInput(flujo);
                return new int[] { lector.getWidth(0), lector.getHeight(0) };
            } finally {
                lector.dispose();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Skia lee los lados al crear la imagen y difiere la decodificación hasta que se dibuja. */
    private static int[] ladosConSkia(byte[] data, int offset, int length) {
        try {
            org.jetbrains.skia.Image imagen = org.jetbrains.skia.Image.Companion.makeFromEncoded(exactos(data, offset, length));
            try { return new int[] { imagen.getWidth(), imagen.getHeight() }; } finally { imagen.close(); }
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] exactos(byte[] data, int offset, int length) {
        return offset == 0 && length == data.length ? data : Arrays.copyOfRange(data, offset, offset + length);
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
