package com.avoqado.pos.printing.data.model

import java.text.Normalizer

/**
 * Con qué tabla de letras se mandan los acentos a una impresora ESC/POS.
 *
 * Por default la de siempre: Windows-1252 (`ESC t 16`), que obedecen Sunmi, Epson y la mayoría. Algunas impresoras
 * ignoran `ESC t 16` y se quedan en su tabla de fábrica, PC437 (La Galeterie, 5-oct: «CAFETERÍA» salía «CAFETER═A», medido
 * letra por letra en su página de prueba). Para ésas: PC850 (`ESC t 2`, trae todo el español) o PC437 (`ESC t 0`, la que
 * ya tienen; sin Á Í Ó Ú mayúsculas, que salen sin acento). La página de prueba imprime la línea de acentos en las tres
 * para elegir la que salga bien, sin adivinar.
 *
 * Mapa propio en vez de `Charset.forName("IBM850")`: ese charset vive en `jdk.charsets`, que el JRE recortado de Windows
 * no garantiza. Un carácter por byte, siempre: las columnas siguen cuadrando.
 */
enum class TablaDeAcentos(val escT: Byte, val etiqueta: String, private val mapa: Map<Char, Int>) {
    WINDOWS_1252(16, "Normal", emptyMap()),
    PC850(2, "PC850", COMUNES + mapOf('Á' to 0xB5, 'Í' to 0xD6, 'Ó' to 0xE0, 'Ú' to 0xE9)),
    PC437(0, "PC437", COMUNES),
    ;

    fun codificar(texto: String): ByteArray {
        if (this == WINDOWS_1252) return texto.toByteArray(Charsets.ISO_8859_1)
        return ByteArray(texto.length) { i ->
            val c = texto[i]
            when {
                c.code < 0x80 -> c.code.toByte()
                else -> (mapa[c] ?: sinAcento(c).code).toByte()
            }
        }
    }

    companion object {
        /** Lo que guarda [SavedPrinter.tablaDeAcentos]; nada o un valor raro = la de siempre. */
        fun deGuardada(valor: String?): TablaDeAcentos = entries.firstOrNull { it.name == valor } ?: WINDOWS_1252

        /** El campo «Codificación» del panel web: sólo 850 y 437 cambian algo; el «CP858» de fábrica queda como siempre. */
        fun delPanel(charset: String?): TablaDeAcentos =
            when (charset?.trim()?.uppercase()?.removePrefix("CP")?.removePrefix("PC")) {
                "850" -> PC850
                "437" -> PC437
                else -> WINDOWS_1252
            }

        private fun sinAcento(c: Char): Char {
            val base = Normalizer.normalize(c.toString(), Normalizer.Form.NFD).firstOrNull() ?: '?'
            return if (base.code < 0x80) base else '?'
        }
    }
}

/** Lo que PC437 y PC850 comparten en la misma posición. */
private val COMUNES: Map<Char, Int> = mapOf(
    'á' to 0xA0, 'é' to 0x82, 'í' to 0xA1, 'ó' to 0xA2, 'ú' to 0xA3,
    'É' to 0x90, 'ñ' to 0xA4, 'Ñ' to 0xA5, 'ü' to 0x81, 'Ü' to 0x9A,
    '¿' to 0xA8, '¡' to 0xAD, '°' to 0xF8, 'ª' to 0xA6, 'º' to 0xA7,
    'ç' to 0x87, 'Ç' to 0x80,
)
