package com.avoqado.pos.settings.domain

import com.avoqado.pos.core.data.network.ApiConstants

/**
 * El catálogo de lo que se configura en el panel web, y la puerta para llegar desde el POS.
 *
 * 🔴 **Decisión del founder, 2026-09-18:** *«quiero tener bastante para que la gente en el POS
 * sepa que puede configurarlo en el dashboard, así nos evitamos construir todo del POS, a menos
 * que sea absolutamente necesario»*.
 *
 * Esa es la estrategia, y conviene no re-litigarla: **el POS opera, el dashboard configura.** La
 * pantalla que esto alimenta no es una lista de atajos — es el DIRECTORIO de lo configurable. Su
 * trabajo es que el comerciante descubra que la función existe y dónde vive, para no pedir que se
 * construya otra vez en la tablet.
 *
 * La idea salió de Square: desde su POS, «Personalizar recibos» no abre un editor en el aparato,
 * te manda a su página web.
 *
 * 🔴 **Antes de agregar una entrada aquí, abrir `avoqado-web-dashboard/src/routes/venueRoutes.tsx`
 * y confirmar que la ruta existe.** Un enlace roto es peor que no tener el enlace: manda al
 * comerciante a una pantalla en blanco y le enseña a desconfiar de todo el grupo. Las de abajo se
 * verificaron una por una contra ese archivo el 18-sep.
 *
 * ⚠️ El precio, declarado: el navegador pide iniciar sesión si esa cuenta no la tiene abierta en
 * ese aparato. Por eso la pantalla se llama «en el panel web» y cada fila lo repite ANTES de que
 * la toquen: una sorpresa de login a media jornada es peor que un renglón de más.
 */
/**
 * Los grupos son los MISMOS de la pantalla de Ajustes, a propósito.
 *
 * 🔴 La primera versión los apartaba en una pantalla «Configurar en el panel web» con los 22
 * juntos. El founder lo cuestionó el mismo día —*«¿crees conveniente que todo esté en un solo
 * lugar, o que esté acomodado en los lugares correctos?»*— y tenía razón: era otro cajón de
 * sastre, exactamente lo que se le había criticado a «Complementos» esa mañana. Quien buscaba la
 * impresora veía «Impresora» y no se enteraba de que las estaciones vivían en otra pantalla.
 *
 * Ahora cada destino se mezcla con lo que ya se configura en el aparato, bajo su tema. El
 * descubrimiento no se pierde —Ajustes YA es el sitio donde se configura, así que entrar ahí
 * sigue enseñando todo lo configurable— pero cada cosa está donde uno la busca.
 */
enum class GrupoDelPanel(val titulo: String) {
    NEGOCIO("Tu negocio"),
    COBRO("Cobro"),
    CATALOGO("Catálogo"),
    PERSONAL("Personal"),
    OPERACION("Hardware y aparatos"),
    PLAN("Tu plan"),
}

enum class EnElPanelWeb(
    val etiqueta: String,
    val subtitulo: String,
    /** Ruta bajo `/venues/<slug>/`, tal como la sirve `venueRoutes.tsx`. */
    val ruta: String,
    val grupo: GrupoDelPanel,
    /**
     * `true` = sólo para quien administra el negocio. Un cajero no necesita ver la suscripción ni
     * los datos fiscales: es ruido que no puede accionar, y encima le enseña dónde vive el dinero.
     */
    val soloAdministradores: Boolean = true,
) {
    // ── Tu negocio ────────────────────────────────────────────────────────────────────────
    DATOS_DEL_NEGOCIO("Datos del negocio", "Nombre, dirección y contacto", "settings/local/basic-info", GrupoDelPanel.NEGOCIO),
    DISENO_DEL_TICKET("Diseño del ticket", "Logo, encabezado y orden de los datos", "settings/receipt-layout", GrupoDelPanel.NEGOCIO),
    MARCA("Marca", "Logo y colores del negocio", "branding", GrupoDelPanel.NEGOCIO),

    // ── Cobro y facturación ───────────────────────────────────────────────────────────────
    TIPOS_DE_PAGO("Tipos de pago", "Vales, transferencia, apps de reparto", "settings/tender-types", GrupoDelPanel.COBRO),
    METODOS_DE_PAGO("Métodos de cobro", "Terminales y cómo entra el dinero", "settings/payment-methods", GrupoDelPanel.COBRO),
    IMPUESTOS("Impuestos", "Tasas y su tratamiento contable", "contabilidad/impuestos", GrupoDelPanel.COBRO),
    FACTURACION("Datos de facturación", "RFC, régimen y sellos para el CFDI", "cfdi/configuracion", GrupoDelPanel.COBRO),
    LIGAS_DE_PAGO("Ligas de pago", "Cobrar por WhatsApp o correo", "payment-links", GrupoDelPanel.COBRO, soloAdministradores = false),

    // ── Catálogo y promociones ────────────────────────────────────────────────────────────
    MENU("Menú y categorías", "Cómo se agrupa lo que vendes", "menumaker", GrupoDelPanel.CATALOGO),
    PROMOCIONES("Promociones y cupones", "Descuentos, paquetes y códigos", "promotions", GrupoDelPanel.CATALOGO),
    LEALTAD("Programa de lealtad", "Sellos, puntos y premios", "loyalty", GrupoDelPanel.CATALOGO),
    PROVEEDORES("Proveedores y compras", "Órdenes de compra y recepción", "suppliers", GrupoDelPanel.CATALOGO),

    // ── Personal ──────────────────────────────────────────────────────────────────────────
    EQUIPO("Equipo", "Dar de alta personal y asignar roles", "team", GrupoDelPanel.PERSONAL),
    PERMISOS("Permisos por rol", "Qué puede hacer cada puesto", "settings/role-permissions", GrupoDelPanel.PERSONAL),
    ASISTENCIA("Asistencia y horarios", "Checadas, cuadrante y retardos", "asistencia", GrupoDelPanel.PERSONAL),
    COMISIONES("Comisiones", "Esquemas y metas por persona", "commissions", GrupoDelPanel.PERSONAL),

    // ── Operación y aparatos ──────────────────────────────────────────────────────────────
    ESTACIONES_DE_IMPRESION("Estaciones de impresión", "A qué impresora va cada comanda", "settings/print-stations", GrupoDelPanel.OPERACION),
    APARATOS("Aparatos", "Terminales y tabletas del negocio", "devices", GrupoDelPanel.OPERACION),
    VALES_POR_AREA("Vales por área", "Entrega contra comprobante pagado", "settings/area-tickets", GrupoDelPanel.OPERACION),
    INTEGRACIONES("Integraciones", "Google, WhatsApp y apps conectadas", "settings/integrations", GrupoDelPanel.OPERACION),

    // ── Tu plan ───────────────────────────────────────────────────────────────────────────
    SUSCRIPCION("Tu plan y funciones", "Qué tienes contratado y qué puedes añadir", "settings/subscriptions", GrupoDelPanel.PLAN),
    COBRANZA("Pagos a Avoqado", "Método de pago y comprobantes", "settings/billing", GrupoDelPanel.PLAN),
    ;

    /**
     * La dirección completa. Sin `slug` no se puede armar una que lleve al negocio correcto, así
     * que devuelve `null` y la entrada no se ofrece: mandar a alguien a la sucursal equivocada —o
     * a una pantalla en blanco— es peor que no ofrecer el atajo.
     */
    fun url(venueSlug: String?): String? {
        val slug = venueSlug?.takeIf { it.isNotBlank() } ?: return null
        return ApiConstants.DASHBOARD_URL.trimEnd('/') + "/venues/" + slug + "/" + ruta
    }

    companion object {
        /** Lo que esta persona puede configurar, ya agrupado y en orden. */
        fun porGrupo(esAdministrador: Boolean): List<Pair<GrupoDelPanel, List<EnElPanelWeb>>> =
            GrupoDelPanel.entries.mapNotNull { grupo ->
                val destinos = entries.filter {
                    it.grupo == grupo && (esAdministrador || !it.soloAdministradores)
                }
                if (destinos.isEmpty()) null else grupo to destinos
            }
    }
}
