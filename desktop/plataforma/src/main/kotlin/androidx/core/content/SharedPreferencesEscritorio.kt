package androidx.core.content

import android.content.SharedPreferences

/** Sustituto de core-ktx, misma firma. */
inline fun SharedPreferences.edit(commit: Boolean = false, action: SharedPreferences.Editor.() -> Unit) {
    val editor = edit()
    action(editor)
    if (commit) editor.commit() else editor.apply()
}
