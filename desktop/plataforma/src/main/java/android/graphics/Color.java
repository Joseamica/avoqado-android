package android.graphics;

/** Sustituto: parseColor de AOSP para #RRGGBB y #AARRGGBB. */
public class Color {
    public static final int BLACK = 0xFF000000;
    public static final int WHITE = 0xFFFFFFFF;

    // ponytail: sin los nombres de color ("red", "navy"…) de Android; la app sólo manda hex. Agregar el mapa si un catálogo trae nombres.
    public static int parseColor(String colorString) {
        if (colorString.length() > 0 && colorString.charAt(0) == '#') {
            long color = Long.parseLong(colorString.substring(1), 16);
            if (colorString.length() == 7) {
                color |= 0x00000000ff000000L;
            } else if (colorString.length() != 9) {
                throw new IllegalArgumentException("Unknown color");
            }
            return (int) color;
        }
        throw new IllegalArgumentException("Unknown color");
    }
}
