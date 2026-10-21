package com.avoqado.pos.pos.data

/**
 * El lector INTEGRADO de las Sunmi (OrderPAD 3 con lector, L2…): la app de Sunmi
 * (`com.sunmi.scanner`) lee la etiqueta y avisa el código por broadcast; lo escucha
 * `MainActivity` y entra por [LectorHidBus.recibirDelLectorIntegrado].
 *
 * 🔴 Ese lector **se duerme solo si hay una cámara prendida** (log de la OrderPAD, 20-oct-2026:
 * `checkScreenOrCameraState onCameraOnInternal` → `sleep handle`) y no avisa a nadie. Por eso,
 * en un aparato con lector, la pantalla de escanear abre con la cámara APAGADA y la cámara se
 * prende sólo si el cajero la pide.
 */
object LectorIntegradoSunmi {
    const val ACCION = "com.sunmi.scanner.ACTION_DATA_CODE_RECEIVED"
    const val EXTRA_CODIGO = "data"

    /**
     * ¿Este aparato trae lector integrado? Lo dice la ROM de Sunmi: `persist.sys.scan=1` en la
     * OrderPAD con lector; la D3 no la trae.
     *
     * 🔴 No se deduce de que llegue un aviso: las pistolas USB de Sunmi usan la MISMA acción, y una
     * D3 con pistola quedaría marcada como «con lector» para siempre (Codex r1 #3, 20-oct).
     */
    fun hay(): Boolean = propiedad("persist.sys.scan") == "1"

    private fun propiedad(nombre: String): String? = runCatching {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
            .invoke(null, nombre) as? String
    }.getOrNull()
}
