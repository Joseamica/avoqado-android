package android.animation;

/** Sustituto: en escritorio las animaciones siempre están prendidas. */
public class ValueAnimator {
    public static boolean areAnimatorsEnabled() { return true; }
}
