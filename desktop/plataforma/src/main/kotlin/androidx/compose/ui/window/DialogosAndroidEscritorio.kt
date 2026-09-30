package androidx.compose.ui.window

/** Android-only: la app pregunta `LocalView.current.parent as? DialogWindowProvider`; en escritorio nunca lo es. */
interface DialogWindowProvider { val window: android.view.Window }
