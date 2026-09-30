package androidx.core.net

import android.net.Uri

/** Sustituto de core-ktx. */
fun String.toUri(): Uri = Uri.parse(this)
