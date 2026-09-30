package androidx.core.view;

public final class WindowInsetsCompat {
    private WindowInsetsCompat() {}
    public static final class Type {
        private Type() {}
        public static int navigationBars() { return 2; }
        public static int systemBars() { return 7; }
    }
}
