package com.avoqado.pos.pos.data

/**
 * Qué hacer con la tecla que acaba de llegar de un teclado.
 *
 * `DejarPasar` y `Consumir` no son cosmética: `Consumir` significa que la tecla NO
 * llega al campo con foco. Consumir de más deja al cajero sin poder escribir.
 */
sealed interface TeclaHid {
    /** No es del lector: que siga su camino normal. */
    data object DejarPasar : TeclaHid

    /** Parte de una ráfaga ya demostrada: se come para que no ensucie el campo con foco. */
    data object Consumir : TeclaHid

    /** La ráfaga terminó: esto es un código escaneado. */
    data class Codigo(val texto: String) : TeclaHid
}

/**
 * Reconoce un lector de pistola (USB o Bluetooth) entre las teclas que llegan.
 *
 * 🔴 **Por qué existe:** el POS de mostrador de un café real (Sunmi D3) **no tiene
 * cámara**, así que el QR de la tarjeta digital del cliente no se puede escanear ahí y
 * su programa de sellos no arranca. Un lector de pistola lo resuelve — pero ante
 * Android un lector *es* un teclado: escribe el código carácter por carácter y cierra
 * con Enter. No hay forma de preguntarle al sistema "¿eres un lector?".
 *
 * Lo que sí los separa es el **ritmo**: un lector escribe cada carácter en ~10 ms (un
 * EAN-13 entero en ~130 ms); una persona tarda 150-300 ms entre teclas. Es la misma
 * heurística que usan los POS del mercado, y es la razón del umbral.
 *
 * 🔴 **La regla que evita el daño peor:** no se consume una tecla hasta que la ráfaga
 * ya se demostró (dos teclas seguidas dentro del umbral). Si se consumiera desde la
 * primera, un negocio con un teclado USB de verdad se quedaría sin poder escribir —
 * mucho peor que el problema que esto arregla. El precio es un carácter suelto en el
 * campo con foco la PRIMERA vez que un lector nuevo dispara; a partir de ahí ese
 * aparato queda reconocido y ya no se escapa nada.
 *
 * Es lógica PURA: el reloj entra por parámetro y no toca Android, así que se puede
 * probar sin un aparato. Quien la usa desde la app es [com.avoqado.pos.pos.data.LectorHidBus].
 */
class LectorHid(
    /**
     * Máximo entre dos teclas de la misma ráfaga. 120 ms deja pasar cualquier lector
     * (van a ~10 ms) sin alcanzar a una persona (150-300 ms).
     */
    private val maxIntervaloMs: Long = 120,
    /**
     * Un código de verdad nunca mide menos que esto. Sin el mínimo, dos teclas rápidas
     * seguidas de Enter —un atajo del cajero— se leerían como un escaneo.
     */
    private val largoMinimo: Int = 4,
) {
    private val buffer = StringBuilder()
    private var ultimaTeclaMs: Long = 0
    private var teclasEnRafaga: Int = 0
    private var aparatoDeLaRafaga: Int = SIN_APARATO

    /** Aparatos que ya completaron una ráfaga: de ésos sí se consume desde la primera tecla. */
    private val lectoresConocidos = mutableSetOf<Int>()

    /**
     * @param caracter el carácter que escribiría esta tecla, o `null` si no escribe nada
     *   (flechas, función, volumen).
     * @param esTerminador Enter o Tab — el sufijo con el que un lector cierra el código.
     * @param esFisico `false` para el teclado EN PANTALLA, que nunca puede ser un lector.
     * @param deviceId el aparato que mandó la tecla, para no mezclar dos teclados.
     * @param ahoraMs reloj monótono en milisegundos.
     */
    fun procesar(
        caracter: Char?,
        esTerminador: Boolean,
        esFisico: Boolean,
        deviceId: Int,
        ahoraMs: Long,
    ): TeclaHid {
        // El teclado en pantalla nunca es un lector, por rápido que alguien escriba.
        if (!esFisico) {
            limpiar()
            return TeclaHid.DejarPasar
        }

        if (esTerminador) {
            val codigo = buffer.toString().trim()
            // `teclasEnRafaga >= 2` es lo que distingue una ráfaga de una tecla suelta:
            // sin eso, el Enter de "aceptar" de cualquier diálogo se leería como el
            // cierre de un escaneo. Y el Enter tiene que venir PEGADO a la ráfaga y del
            // mismo aparato: un lector manda el sufijo a ~10 ms del último carácter; una
            // persona que tecleó rápido, se detuvo y luego confirmó, no.
            val huboRafaga = teclasEnRafaga >= 2 &&
                deviceId == aparatoDeLaRafaga &&
                (ahoraMs - ultimaTeclaMs) <= maxIntervaloMs
            limpiar()
            return if (huboRafaga && codigo.length >= largoMinimo) {
                lectoresConocidos += deviceId
                TeclaHid.Codigo(codigo)
            } else {
                TeclaHid.DejarPasar
            }
        }

        // Una tecla que no escribe nada parte la ráfaga: arrastrarla entregaría un
        // código cortado a la mitad, que no existe en ningún catálogo.
        if (caracter == null) {
            limpiar()
            return TeclaHid.DejarPasar
        }

        val continuaLaRafaga = teclasEnRafaga > 0 &&
            deviceId == aparatoDeLaRafaga &&
            (ahoraMs - ultimaTeclaMs) <= maxIntervaloMs
        if (!continuaLaRafaga) {
            buffer.setLength(0)
            teclasEnRafaga = 0
            aparatoDeLaRafaga = deviceId
        }
        buffer.append(caracter)
        teclasEnRafaga++
        ultimaTeclaMs = ahoraMs

        return if (teclasEnRafaga >= 2 || deviceId in lectoresConocidos) {
            TeclaHid.Consumir
        } else {
            TeclaHid.DejarPasar
        }
    }

    private fun limpiar() {
        buffer.setLength(0)
        teclasEnRafaga = 0
        aparatoDeLaRafaga = SIN_APARATO
        ultimaTeclaMs = 0
    }

    private companion object {
        const val SIN_APARATO = Int.MIN_VALUE
    }
}
