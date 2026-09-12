package com.avoqado.pos.printing.data

/**
 * El texto que va encima del QR del recibo.
 *
 * 🔴 El QR se imprime SIEMPRE que exista la liga: lleva al recibo digital, se pueda facturar o no.
 * Lo único que cambia con `autofacturaAvailable` es la promesa: decirle «y factura» a un cliente
 * cuyo negocio no tiene la autofacturación prendida lo manda a buscar un botón que no está ahí.
 *
 * Función pura y aparte para que se pueda probar sin impresora — y para que iOS use exactamente
 * el mismo texto (`LeyendaDelQr.swift`).
 */
fun leyendaDelQr(autofacturaAvailable: Boolean): String =
    if (autofacturaAvailable) "Escanea para tu recibo y factura" else "Escanea para tu recibo digital"
