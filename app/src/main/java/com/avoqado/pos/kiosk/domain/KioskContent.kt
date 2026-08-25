package com.avoqado.pos.kiosk.domain

import com.avoqado.pos.designsystem.components.Country

/**
 * Lo que ve el CLIENTE cuando la segunda cara del equipo trabaja como KIOSCO:
 * se identifica solo, confirma su llegada y compra su paquete sin pedirle nada
 * a nadie.
 *
 * 🔴 Esto NO es [com.avoqado.pos.customerdisplay.CustomerContent], y la
 * tentación de meterlo ahí como un caso más es real. Son cosas opuestas:
 *
 * - `CustomerContent` es un **espejo**. El cajero teclea y el cliente MIRA. La
 *   verdad vive en el carrito; la pantalla del cliente solo la refleja, y su
 *   propio archivo lo declara ("cero matemática de dinero aquí").
 * - `KioskContent` es un **volante**. Aquí no hay cajero: el cliente CONDUCE, y
 *   lo que toca se convierte en un check-in o en un cobro de verdad.
 *
 * Mezclarlos obligaría a que el espejo supiera cuándo NO es espejo, y ese `if`
 * se filtraría a cada estado. Separados, el kiosco no puede romper el mostrador:
 * son dos máquinas que se turnan la misma ventana, nunca la comparten.
 *
 * ## Lo que el kiosco NO hace, por diseño
 *
 * El cliente **nunca elige cuánto se cobra ni a quién**: escoge algo con precio
 * de catálogo y pasa SU tarjeta en la terminal. Por eso el autoservicio no pide
 * PIN de empleado — pedirlo en cada compra sería dejar de ser un kiosco.
 * Devoluciones, descuentos, cortesías, montos libres y efectivo NO viven aquí.
 */
sealed interface KioskContent {

    /** En reposo: no hay ninguna clase dentro de su ventana de check-in. */
    data object Welcome : KioskContent

    /**
     * La clase que está corriendo AHORA, con quién viene. Se abre y se cierra
     * sola: la manda el reloj del negocio, no una persona.
     *
     * 🔴 **Sólo la clase en curso, jamás la agenda del día.** El kiosco mira a
     * la entrada: la lista completa del día pondría los nombres de toda la
     * clientela en una pantalla pública durante horas. Las cuatro personas que
     * van a compartir el salón en diez minutos se van a ver la cara de todos
     * modos — la de las 9 PM no tiene por qué salir a las 6.
     *
     * La ventana la define el servidor y él la vuelve a checar al confirmar
     * (`CHECK_IN_OUTSIDE_WINDOW`, 422): esta pantalla es comodidad, no permiso.
     */
    data class Roster(
        val classTitle: String,
        val timeLabel: String,
        val staffLabel: String?,
        val people: List<KioskPerson>,
        /** Se está confirmando a alguien. */
        val busyId: String? = null,
        /**
         * A quién se le acaba de confirmar. Su renglón se abre unos segundos con
         * su lugar y su instructor, y luego se encoge solo.
         *
         * 🔴 Se expande EN SITIO en vez de mandar a una pantalla de "listo": en
         * una clase se registran varias personas seguidas, y una confirmación a
         * pantalla completa dejaría a la de atrás mirando ocho segundos.
         */
        val justConfirmedId: String? = null,
        val failed: Boolean = false,
    ) : KioskContent

    /**
     * "¿Cuál es tu teléfono?" — el único dato que pedimos.
     *
     * Sin cuenta y sin contraseña a propósito: alguien parado en la entrada,
     * con prisa, no va a crear una cuenta ni recordar una contraseña. El
     * teléfono ya identifica a quien reservó, haya reservado como invitada o
     * con cuenta — el servidor busca en los dos lados.
     */
    data class Identify(
        val country: Country,
        val national: String,
        val searching: Boolean = false,
        /** Se buscó y no había nada. Distinto de "todavía no busca". */
        val notFound: Boolean = false,
        /** Falló la red o el servidor. Distinto de "no existe". */
        val failed: Boolean = false,
    ) : KioskContent {
        /** Lo mínimo para que valga la pena buscar. */
        val canSearch: Boolean get() = national.length >= MIN_DIGITS && !searching

        private companion object {
            // 10 dígitos es el largo nacional en México; abajo de eso la
            // búsqueda por coincidencia parcial devolvería a media colonia.
            const val MIN_DIGITS = 10
        }
    }

    /**
     * La encontramos, y esto es lo que tiene hoy.
     *
     * `sessions` vacío ⇒ vino sin reserva para hoy: no es un error, es el caso
     * de quien pasa a comprar. Por eso la compra no cuelga del check-in.
     */
    data class Found(
        val customerName: String,
        val sessions: List<KioskSession>,
        val working: Boolean = false,
        val failed: Boolean = false,
    ) : KioskContent

    /** Confirmó su llegada. Se va sola al reposo. */
    data class CheckedIn(
        val customerName: String,
        val session: KioskSession,
    ) : KioskContent

    /**
     * "¿Quieres un paquete?" — el momento en que el cliente ya está enfrente.
     *
     * 🔴 `selectedId` MARCA, no cobra. El mismo criterio que el upsell del
     * mostrador: un toque que cobrara al instante suena mejor hasta que alguien
     * roza la pantalla con la manga.
     */
    data class Offer(
        val customerName: String,
        val packs: List<KioskPack>,
        val selectedId: String? = null,
    ) : KioskContent {
        val selected: KioskPack? get() = packs.firstOrNull { it.id == selectedId }
    }

    /**
     * El pago se hace en el TELÉFONO del cliente, escaneando un QR.
     *
     * Es lo que dice el spec ("CreditPack → PAX o Stripe Checkout como QR") y resuelve
     * tres cosas de una: nadie mete su tarjeta en el aparato compartido de la entrada;
     * el kiosco no tiene que averiguar quién eres antes de cobrarte, así que no se
     * vuelve un buscador de personas; y reutiliza el carril de pago en línea que ya
     * existe y ya está probado, en vez de estrenar código en el camino del dinero.
     *
     * 🔴 [payUrl] en null = todavía se está pidiendo el enlace. La pantalla NO puede
     * enseñar un QR vacío ni decir que ya se puede pagar.
     */
    data class Paying(
        val customerName: String,
        val pack: KioskPack,
        val payUrl: String? = null,
        val failed: Boolean = false,
    ) : KioskContent

    /** Se cobró y el paquete ya es suyo. */
    data class Purchased(
        val customerName: String,
        val pack: KioskPack,
    ) : KioskContent

    /**
     * Algo se cayó y hay que decirlo en la pantalla, no en un log.
     *
     * 🔴 Un kiosco que se queda mudo es peor que uno que falla: el cliente se
     * queda parado sin saber si sirvió, y nadie del negocio se entera. Siempre
     * hay salida a mano ([onRestart]).
     */
    data class Trouble(
        val message: String,
        /**
         * 🔴 El título por defecto ALARMA, y no todo lo que cae aquí es una falla.
         *
         * "Este negocio todavía no tiene paquetes a la venta" es un estado NORMAL de
         * configuración, y anunciarlo como "Algo salió mal" le dice al cliente que algo
         * se rompió cuando no se rompió nada — y al negocio le esconde que lo único que
         * falta es dar de alta sus paquetes. Se vio en la D3, de frente a la pantalla.
         */
        val title: String = "Algo salió mal",
    ) : KioskContent
}

/** Una clase o cita de hoy, ya resuelta por el servidor. Aquí no se calcula nada. */
data class KioskSession(
    val reservationId: String,
    /** "Yoga Flow" */
    val title: String,
    /** "7:00 PM" — ya formateado en hora del NEGOCIO, nunca del aparato. */
    val timeLabel: String,
    /** "con Sofía", o null si no hay quien la imparta asignado. */
    val staffLabel: String?,
    /** Ya hizo check-in antes: se muestra, pero no se le vuelve a pedir. */
    val alreadyCheckedIn: Boolean = false,
)

/** Un paquete comprable, con su precio de catálogo. El kiosco nunca lo cambia. */
data class KioskPack(
    val id: String,
    /** "10 clases" */
    val name: String,
    /** En centavos, tal como llega del servidor. */
    val priceCents: Int,
    /** "Vence en 3 meses", o null. */
    val detail: String?,
)

/**
 * Alguien con lugar en la clase en curso.
 *
 * 🔴 [displayName] es nombre + inicial ("Ana G."), NUNCA el nombre completo ni
 * el teléfono: esto se pinta de cara a la entrada. Alcanza para que alguien se
 * reconozca y no alcanza para que un extraño identifique a nadie.
 */
data class KioskPerson(
    val reservationId: String,
    val displayName: String,
    val checkedIn: Boolean,
    /**
     * Su lugar en el salón, ya legible ("Tapete 3"). Null cuando el negocio no
     * tiene acomodo configurado o la reserva no eligió lugar — que hoy es lo
     * normal, así que la pantalla NO puede darlo por hecho.
     */
    val spotLabel: String? = null,
)

/**
 * ¿Esta pantalla es del RELOJ o de la persona que está enfrente?
 *
 * El kiosco tiene dos dueños. El reloj manda en el reposo y en la lista de la clase: las
 * abre y las cierra solo, sin que nadie toque nada. Todo lo demás —teclear el teléfono,
 * elegir un paquete, mirar el QR de pago— es de quien está ahí parado, y sólo termina con
 * su propio temporizador o porque se sale.
 *
 * 🔴 Existe porque el tick de refresco borraba el teclado a los 25 segundos con la persona
 * todavía tecleando. Se vio en la D3, no en el compilador ni en las pruebas.
 */
fun tickerOwnsScreen(content: KioskContent): Boolean =
    content is KioskContent.Welcome || content is KioskContent.Roster

/**
 * El orden en que se pintan los nombres de la clase.
 *
 * 🔴 Alfabético, y es una decisión de SEGURIDAD, no de estética. El servidor devuelve las
 * reservas en su propio orden y confirmar una llegada MUEVE a esa persona de sitio. En la
 * D3 se vio: Ana tocó su nombre y saltó al final de la lista. En un kiosco eso es
 * peligroso — la siguiente persona ya venía estirando el dedo hacia donde SU nombre estaba
 * hace un segundo, y termina confirmando la llegada de alguien más.
 *
 * Alfabético es el único orden que NO se mueve cuando alguien confirma, y además es como
 * uno busca su propio nombre en una lista.
 */
fun sortRoster(people: List<KioskPerson>): List<KioskPerson> {
    // Con acentos y ñ: "Ñuño" va tras "Nadia", no al final del abecedario.
    val nombre = java.text.Collator.getInstance(java.util.Locale("es", "MX"))
    return people.sortedWith(compareBy(nombre) { it.displayName })
}
