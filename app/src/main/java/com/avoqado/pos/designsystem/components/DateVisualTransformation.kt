package com.avoqado.pos.designsystem.components

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

// MARK: - Date Visual Transformation

/**
 * [VisualTransformation] that displays a raw digit-only string of up to 8 characters
 * (DDMMYYYY) as a slash-separated date (DD/MM/YYYY).
 *
 * The caller is responsible for ensuring the underlying [androidx.compose.material3.OutlinedTextField]
 * value is digit-only and clamped to 8 characters. Recommended `onValueChange` filter:
 *
 * ```
 * onValueChange = { raw ->
 *     value = raw.filter { it.isDigit() }.take(8)
 * }
 * ```
 *
 * The [OffsetMapping] preserves the cursor position correctly when the user types or
 * deletes characters, so the cursor lands after each just-inserted slash and backspace
 * lands on the last digit (not on a slash).
 *
 * Slash insertion is dynamic: the first slash only appears once `digits.length > 2`,
 * and the second once `digits.length > 4`. The mapping accounts for this so the cursor
 * doesn't jump unexpectedly while the user is still typing the second/fourth digit.
 */
object DateVisualTransformation : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        // Take at most 8 digits to be safe; caller should already enforce this.
        val digits = text.text.take(8)

        val formatted = buildString {
            digits.forEachIndexed { index, c ->
                append(c)
                if (index == 1 && digits.length > 2) append('/')
                if (index == 3 && digits.length > 4) append('/')
            }
        }

        // Capture once so OffsetMapping is consistent for this filter pass.
        val rawLen = digits.length
        val outLen = formatted.length
        val firstSlashInserted = rawLen > 2
        val secondSlashInserted = rawLen > 4

        val offsetMapping = object : OffsetMapping {
            // Map original cursor position -> displayed cursor position.
            override fun originalToTransformed(offset: Int): Int {
                val clamped = offset.coerceIn(0, rawLen)
                // 0..1: always before any slash.
                if (clamped <= 1) return clamped
                // 2: after the second day digit. If a slash was inserted right after it,
                // place the cursor AFTER the slash so the next typed digit flows naturally.
                if (clamped == 2) return if (firstSlashInserted) 3 else 2
                // 3: third digit position (first month digit). Always shifted by one slash.
                if (clamped == 3) return 4
                // 4: after the second month digit. If second slash was inserted, jump past it.
                if (clamped == 4) return if (secondSlashInserted) 6 else 5
                // 5..8: year digits, always shifted by two slashes.
                return (clamped + 2).coerceAtMost(outLen)
            }

            // Map displayed cursor position -> original cursor position.
            override fun transformedToOriginal(offset: Int): Int {
                val clamped = offset.coerceIn(0, outLen)
                // 0..1 always passes through.
                if (clamped <= 1) return clamped
                // 2 lands either on the second day digit boundary (no slash yet) or
                // exactly on the inserted slash — both collapse to raw 2.
                if (clamped == 2) return 2
                // 3 is just after the first slash (or the third raw char if no slash) -> raw 2 or 3.
                if (clamped == 3) return if (firstSlashInserted) 2 else 3
                // 4 is one slash in -> raw 3.
                if (clamped == 4) return 3
                // 5 is two raw chars + one slash -> raw 4 (whether or not second slash exists).
                if (clamped == 5) return 4
                // 6 sits exactly on the second slash (if present) or first year digit.
                if (clamped == 6) return if (secondSlashInserted) 4 else 5
                // 7..10 fall after both slashes -> raw - 2.
                return (clamped - 2).coerceAtMost(rawLen)
            }
        }

        return TransformedText(AnnotatedString(formatted), offsetMapping)
    }
}
