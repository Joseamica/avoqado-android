package com.avoqado.pos.core.data.lan

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Etapa 3 del KDS (3.5, D3) — «leer hasta `\n`, máximo 64 KiB, con plazo», para el servidor Y el cliente del transporte.
 * Antes el servidor usaba `BufferedReader.readLine()` SIN tope (un peer malicioso o roto podía hacerlo crecer sin fin)
 * y el cliente lo mismo. El plazo lo pone quien llama con `soTimeout` (lanza `SocketTimeoutException`). Espejo de
 * `LectorDeLinea.swift`.
 *
 * Pasa un `BufferedInputStream`: se lee byte a byte a propósito (no se puede leer de más: una conexión = una línea).
 */
object LineaAcotada {

    const val MAX_BYTES = 65_536

    /**
     * La línea sin su `\n` (y sin un `\r` final), o `null` si supera [maxBytes] antes del salto (quien llama corta la
     * conexión SIN responder) o si el stream termina sin nada. Si el otro lado cierra justo después de la línea sin
     * mandar `\n`, se entrega lo acumulado (es lo que hacía `readLine()`).
     */
    fun leer(input: InputStream, maxBytes: Int = MAX_BYTES): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) {
                if (buffer.size() == 0) return null
                break
            }
            if (b == '\n'.code) break
            if (buffer.size() >= maxBytes) return null
            buffer.write(b)
        }
        return buffer.toString(Charsets.UTF_8.name()).trimEnd('\r')
    }
}
