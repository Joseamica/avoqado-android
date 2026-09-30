package android.util;

import java.util.regex.Pattern;

/** Sustituto: sólo EMAIL_ADDRESS, copiado de AOSP (frameworks/base/core/java/android/util/Patterns.java). */
public final class Patterns {
    private Patterns() {}
    public static final Pattern EMAIL_ADDRESS = Pattern.compile(
        "[a-zA-Z0-9\\+\\.\\_\\%\\-\\+]{1,256}" +
        "\\@" +
        "[a-zA-Z0-9][a-zA-Z0-9\\-]{0,64}" +
        "(" +
            "\\." +
            "[a-zA-Z0-9][a-zA-Z0-9\\-]{0,25}" +
        ")+"
    );
}
