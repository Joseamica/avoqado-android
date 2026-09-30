package android.graphics;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/** Sustituto respaldado por java.awt.image.BufferedImage (ARGB, como ARGB_8888). */
public final class Bitmap {
    public enum Config { ARGB_8888 }

    private final BufferedImage imagen;

    Bitmap(BufferedImage imagen) { this.imagen = imagen; }

    /** Escritorio: la imagen de AWT detrás (la usa asImageBitmap para Compose). */
    public BufferedImage imagen() { return imagen; }

    public static Bitmap createBitmap(int width, int height, Config config) {
        return new Bitmap(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB));
    }

    public static Bitmap createScaledBitmap(Bitmap src, int dstWidth, int dstHeight, boolean filter) {
        if (src.getWidth() == dstWidth && src.getHeight() == dstHeight) return src;   // como Android: mismo tamaño ⇒ el mismo objeto
        BufferedImage destino = new BufferedImage(dstWidth, dstHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = destino.createGraphics();
        try {
            if (filter) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src.imagen, 0, 0, dstWidth, dstHeight, null);
        } finally {
            g.dispose();
        }
        return new Bitmap(destino);
    }

    public int getWidth() { return imagen.getWidth(); }
    public int getHeight() { return imagen.getHeight(); }

    public void getPixels(int[] pixels, int offset, int stride, int x, int y, int width, int height) {
        imagen.getRGB(x, y, width, height, pixels, offset, stride);
    }

    public void setPixels(int[] pixels, int offset, int stride, int x, int y, int width, int height) {
        imagen.setRGB(x, y, width, height, pixels, offset, stride);
    }

    public void recycle() {}
}
