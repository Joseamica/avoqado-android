package com.avoqado.pos.kiosk

import android.content.Intent
import com.avoqado.pos.kiosk.system.KioskBootReceiver.Companion.shouldLaunchOnBoot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ¿Se levanta sola la app cuando enciende el aparato?
 *
 * 🔴 Nace de una medición: se reinició la Sunmi D3 y el servidor no recibió UNA sola
 * petición del aparato en los 84 minutos siguientes. El túnel y el servidor estaban vivos
 * —200 los dos— así que el silencio era de la app: sin `BOOT_COMPLETED`, Android no la
 * vuelve a levantar. Para un kiosco en la entrada eso significa que un corte de luz de
 * madrugada lo deja muerto hasta que alguien llegue a despertarlo.
 *
 * La otra mitad de la regla importa igual: en un POS de mostrador arrancar solo se metería
 * encima de lo que el negocio esté haciendo. Sólo el kiosco vuelve solo, porque es el único
 * donde no hay nadie a quien pedírselo.
 */
class KioskBootReceiverTest {

    @Test
    fun `con el kiosco prendido, al encender el aparato se levanta`() {
        assertTrue(shouldLaunchOnBoot(Intent.ACTION_BOOT_COMPLETED, kioskEnabled = true))
    }

    @Test
    fun `tambien con el arranque temprano, antes de desbloquear`() {
        assertTrue(shouldLaunchOnBoot(Intent.ACTION_LOCKED_BOOT_COMPLETED, kioskEnabled = true))
    }

    @Test
    fun `con el kiosco APAGADO no se levanta nada`() {
        assertFalse(shouldLaunchOnBoot(Intent.ACTION_BOOT_COMPLETED, kioskEnabled = false))
    }

    @Test
    fun `un aviso que no es de arranque no levanta nada`() {
        assertFalse(shouldLaunchOnBoot(Intent.ACTION_POWER_CONNECTED, kioskEnabled = true))
        assertFalse(shouldLaunchOnBoot(null, kioskEnabled = true))
    }
}
