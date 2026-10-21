package com.avoqado.pos

/** Los recursos que la app usa por `R.` (3 medidos el 29-sep; los 2 logos horizontales, 21-oct). El número sólo tiene que ser único; lo traduce RecursosDeEscritorio. */
object R {
    object drawable {
        const val avoqado_logo_mark = 1
        const val ic_whatsapp = 2
        const val avoqado_logo_inline_black = 4   // «Powered by» de la pantalla del cliente (tema claro)
        const val avoqado_logo_inline_white = 5   // y el de tema oscuro
    }
    object raw {
        const val background_video = 3   // el video del login se reemplaza por un fondo fijo (LandingScreen, tarea 5)
    }
}
