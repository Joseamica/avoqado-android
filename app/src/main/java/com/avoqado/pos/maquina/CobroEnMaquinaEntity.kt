package com.avoqado.pos.maquina

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.json.JSONObject

/** Los estados de un intento de cobro en la máquina (columna `estado`). */
object EstadoDelIntento {
    /** Guardado ANTES del `paycash`: la máquina pudo o no recibirlo. */
    const val EN_CURSO = "EN_CURSO"
    const val PAGADO = "PAGADO"
    /** «Transacción Cancelada» tras nuestro `cancel "1"` (devolver). */
    const val CANCELADO = "CANCELADO"
    /** «Transacción Cancelada» tras nuestro `cancel "2"` (abandono: la máquina se queda lo que entró). */
    const val GUARDADO = "GUARDADO"
    /** «Esperando a la máquina»: la PC no sabe cómo terminó. Sólo lo resuelve un gerente (R4-4), nunca la PC sola. */
    const val ESPERANDO = "ESPERANDO"
    /** La decisión del gerente ya llegó del SERVIDOR (C4). */
    const val RESUELTO = "RESUELTO"
}

/**
 * Un intento de cobro en la QuickPay (fase 1a, R4-1): esta tabla ES la cola propia de la máquina. Un cierre final con
 * `subido = false` (o una duda con `dudaSubida = false`) es lo que la subida (C4) manda a
 * `POST …/cash-machine/sales`; no pasa por `pending_payments`.
 *
 * Se escribe ANTES de tocar la red (`todo-funciona-sin-red.md`, pregunta 2): el `EN_CURSO` antes del `paycash`, el modo
 * del `cancel` antes de mandarlo, y el desenlace junto con `subido = false` en un solo `UPDATE`.
 */
@Entity(tableName = "cobros_en_maquina")
data class CobroEnMaquinaEntity(
    /** `AQ` + 12 `[0-9A-Z]` ([nuevaReferencia]); el SERVIDOR deriva la llave del pago como `maquina-<folio>`. */
    @PrimaryKey val referenceCC: String,
    val venueId: String,
    /** La venta (carrito) que se cobra: a lo más un intento abierto, en duda o pagado por venta ([CobroEnMaquinaDao.abrirIntento]). */
    val llaveDeVenta: String,
    /** El carrito congelado: el mismo JSON de creación de orden que sube con el cierre (R4-1, R5#1). */
    val pedidoJson: String,
    val saleCents: Int,
    val tipCents: Int,
    /** `saleCents + tipCents` (R3#6): lo que se le pidió a la máquina. */
    val dueCents: Int,
    val saleShiftId: String?,
    val quoteId: String?,
    val creadoEn: Long,
    val estado: String,
    /** La conexión en que la máquina aceptó nuestro folio y `Due` (la propiedad del cobro). */
    val generacionPropia: Int? = null,
    val ultimoCreditoEn: Long? = null,
    val creditCents: Int = 0,
    val changeDispensedCents: Int = 0,
    /** `"1"` devolver (botón del cliente) o `"2"` guardar (abandono); se guarda ANTES de mandar el `cancel`. */
    val cancelPedido: String? = null,
    val cancelPedidoEn: Long? = null,
    /** La hora en que la PC vio el `status` de cierre. */
    val cierreEn: Long? = null,
    val cierreConocido: Boolean = false,
    /** `MAQUINA` o `GERENTE`. */
    val resueltoPor: String? = null,
    val subido: Boolean = false,
    val dudaSubida: Boolean = false,
    /** El 409/422 del SERVIDOR (C4), visible. */
    val conflicto: String? = null,
    val ticketImpreso: Boolean = false,
    /** Los últimos mensajes de la máquina de este intento, tal cual llegaron (≤ 8 KB): la evidencia para el gerente. */
    val mensajesCrudos: String = "",
    /**
     * v21: la última respuesta FINAL del SERVIDOR, tal cual (`outcome`, `resolvedBy`, los importes con `excedenteCents` y
     * `owedCents`, `motivo`, `incidentId`): el eco de un cierre (200/202), la decisión del gerente o el 409/422 que guardó una
     * incidencia. De aquí sale el desenlace de un `RESUELTO` ([desenlace]) y el comprobante de diferencia (C7).
     */
    val respuestaServidor: String? = null,
)

/**
 * El SQL de la tabla, en constantes, para que las pruebas lo EJECUTEN contra SQLite de verdad (patrón `KdsLanSql`). Cada
 * escritura de un desenlace sólo toca una fila `EN_CURSO`: un cierre repetido o tardío no reescribe lo que ya terminó.
 */
internal object CobroEnMaquinaSql {

    /** 🔴 La tabla como la creó la v20 (`MIGRATION_19_20`), congelada: la v21 le agrega [MIGRAR_20_21]. */
    const val CREAR_TABLA_V20: String =
        "CREATE TABLE IF NOT EXISTS `cobros_en_maquina` (" +
            "`referenceCC` TEXT NOT NULL, `venueId` TEXT NOT NULL, `llaveDeVenta` TEXT NOT NULL, `pedidoJson` TEXT NOT NULL, " +
            "`saleCents` INTEGER NOT NULL, `tipCents` INTEGER NOT NULL, `dueCents` INTEGER NOT NULL, `saleShiftId` TEXT, " +
            "`quoteId` TEXT, `creadoEn` INTEGER NOT NULL, `estado` TEXT NOT NULL, `generacionPropia` INTEGER, " +
            "`ultimoCreditoEn` INTEGER, `creditCents` INTEGER NOT NULL, `changeDispensedCents` INTEGER NOT NULL, " +
            "`cancelPedido` TEXT, `cancelPedidoEn` INTEGER, `cierreEn` INTEGER, `cierreConocido` INTEGER NOT NULL, " +
            "`resueltoPor` TEXT, `subido` INTEGER NOT NULL, `dudaSubida` INTEGER NOT NULL, `conflicto` TEXT, " +
            "`ticketImpreso` INTEGER NOT NULL, `mensajesCrudos` TEXT NOT NULL, PRIMARY KEY(`referenceCC`))"

    /** 🔴 En el formato EXACTO que Room genera HOY para [CobroEnMaquinaEntity] (lo vigila `CobroEnMaquinaMigracionTest`). */
    const val CREAR_TABLA: String =
        "CREATE TABLE IF NOT EXISTS `cobros_en_maquina` (" +
            "`referenceCC` TEXT NOT NULL, `venueId` TEXT NOT NULL, `llaveDeVenta` TEXT NOT NULL, `pedidoJson` TEXT NOT NULL, " +
            "`saleCents` INTEGER NOT NULL, `tipCents` INTEGER NOT NULL, `dueCents` INTEGER NOT NULL, `saleShiftId` TEXT, " +
            "`quoteId` TEXT, `creadoEn` INTEGER NOT NULL, `estado` TEXT NOT NULL, `generacionPropia` INTEGER, " +
            "`ultimoCreditoEn` INTEGER, `creditCents` INTEGER NOT NULL, `changeDispensedCents` INTEGER NOT NULL, " +
            "`cancelPedido` TEXT, `cancelPedidoEn` INTEGER, `cierreEn` INTEGER, `cierreConocido` INTEGER NOT NULL, " +
            "`resueltoPor` TEXT, `subido` INTEGER NOT NULL, `dudaSubida` INTEGER NOT NULL, `conflicto` TEXT, " +
            "`ticketImpreso` INTEGER NOT NULL, `mensajesCrudos` TEXT NOT NULL, `respuestaServidor` TEXT, PRIMARY KEY(`referenceCC`))"

    /** v21 (C4): la respuesta final del SERVIDOR, en columna propia y nula. Nada que migrar: una fila vieja no la tiene. */
    const val MIGRAR_20_21: String = "ALTER TABLE cobros_en_maquina ADD COLUMN respuestaServidor TEXT"

    const val POR_REFERENCIA: String = "SELECT * FROM cobros_en_maquina WHERE referenceCC = :referenceCC"

    /**
     * Lo que impide otro intento de la MISMA venta: uno abierto, uno en duda, uno pagado o uno que resolvió un gerente (su
     * desenlace lo sabe el SERVIDOR). Sólo un `CANCELADO` o un `GUARDADO` dejan volver a intentar.
     */
    const val BLOQUEAN_LA_VENTA: String =
        "SELECT COUNT(*) FROM cobros_en_maquina WHERE llaveDeVenta = :llaveDeVenta " +
            "AND estado IN ('EN_CURSO', 'ESPERANDO', 'PAGADO', 'RESUELTO')"

    /** Sólo si el `paycash` no salió (`enviar` = false): nada llegó a la máquina, no hay evidencia que guardar. */
    const val BORRAR_SIN_ENVIAR: String =
        "DELETE FROM cobros_en_maquina WHERE referenceCC = :referenceCC AND estado = 'EN_CURSO' AND generacionPropia IS NULL"

    const val GANAR_PROPIEDAD: String =
        "UPDATE cobros_en_maquina SET generacionPropia = :generacion, mensajesCrudos = :crudos " +
            "WHERE referenceCC = :referenceCC AND estado = 'EN_CURSO'"

    const val ANOTAR_CREDITO: String =
        "UPDATE cobros_en_maquina SET creditCents = :creditCents, ultimoCreditoEn = :ahora " +
            "WHERE referenceCC = :referenceCC AND estado = 'EN_CURSO'"

    const val PEDIR_CANCEL: String =
        "UPDATE cobros_en_maquina SET cancelPedido = :cancelvalue, cancelPedidoEn = :ahora " +
            "WHERE referenceCC = :referenceCC AND estado = 'EN_CURSO'"

    /** El desenlace y la marca de «por subir» en UN `UPDATE`: la fila es la cola (R4-1). */
    const val CERRAR: String =
        "UPDATE cobros_en_maquina SET estado = :estado, creditCents = :creditCents, " +
            "changeDispensedCents = :changeDispensedCents, cierreEn = :cierreEn, cierreConocido = 1, " +
            "resueltoPor = 'MAQUINA', subido = 0, mensajesCrudos = :crudos " +
            "WHERE referenceCC = :referenceCC AND estado = 'EN_CURSO'"

    const val A_ESPERANDO: String =
        "UPDATE cobros_en_maquina SET estado = 'ESPERANDO', dudaSubida = 0, mensajesCrudos = :crudos " +
            "WHERE referenceCC = :referenceCC AND estado = 'EN_CURSO'"

    /** Al arrancar: un `EN_CURSO` que no es el cobro vivo quedó de un proceso que murió. No se sabe cómo terminó. */
    const val HUERFANOS_A_ESPERANDO: String =
        "UPDATE cobros_en_maquina SET estado = 'ESPERANDO', dudaSubida = 0 " +
            "WHERE estado = 'EN_CURSO' AND referenceCC != :salvo"

    /** Lo que la máquina dice de un folio en duda se guarda; el estado no cambia (lo resuelve un gerente). */
    const val ANOTAR_EVIDENCIA: String =
        "UPDATE cobros_en_maquina SET mensajesCrudos = :crudos WHERE referenceCC = :referenceCC AND estado = 'ESPERANDO'"

    /**
     * R5#2: lo que frena cerrar sesión, cambiar de sucursal y cambiar la IP o desemparejar la máquina: un intento abierto, una
     * duda (subida o no: sólo la cierra la decisión del gerente) o un cierre final sin acuse. `:venueId` null = todas.
     */
    const val PENDIENTES: String =
        "SELECT COUNT(*) FROM cobros_en_maquina WHERE (:venueId IS NULL OR venueId = :venueId) " +
            "AND (estado IN ('EN_CURSO', 'ESPERANDO') OR (estado IN ('PAGADO', 'CANCELADO', 'GUARDADO') AND subido = 0))"

    const val POR_SUBIR: String =
        "SELECT * FROM cobros_en_maquina " +
            "WHERE (estado IN ('PAGADO', 'CANCELADO', 'GUARDADO') AND subido = 0) OR (estado = 'ESPERANDO' AND dudaSubida = 0) " +
            "ORDER BY creadoEn ASC, referenceCC ASC"

    // MARK: - La subida (C4)

    /**
     * Lo que la subida manda de [venueId]: [POR_SUBIR] sin lo que ya tiene `conflicto` (409, 422 o una respuesta con otros
     * importes): eso espera a un gerente y no se reintenta. Sigue contando en [PENDIENTES].
     */
    const val PARA_SUBIR: String =
        "SELECT * FROM cobros_en_maquina WHERE venueId = :venueId AND conflicto IS NULL " +
            "AND ((estado IN ('PAGADO', 'CANCELADO', 'GUARDADO') AND subido = 0) OR (estado = 'ESPERANDO' AND dudaSubida = 0)) " +
            "ORDER BY creadoEn ASC, referenceCC ASC"

    /** Las dudas ya subidas que esperan la decisión del gerente (R5#6): se consultan cada 60 s. */
    const val ESPERAN_AL_GERENTE: String =
        "SELECT * FROM cobros_en_maquina WHERE venueId = :venueId AND estado = 'ESPERANDO' AND dudaSubida = 1 " +
            "AND conflicto IS NULL ORDER BY creadoEn ASC, referenceCC ASC"

    /**
     * El SERVIDOR repitió el folio y los importes (200), guardó el dinero como incidencia (202) o rechazó el cierre DEJANDO su
     * incidencia durable (409/422 con `incidentId`): ya no se reintenta ni frena cerrar sesión, y lo que dijo queda visible en
     * `conflicto` y completo en `respuestaServidor`.
     */
    const val MARCAR_SUBIDO: String =
        "UPDATE cobros_en_maquina SET subido = 1, conflicto = :conflicto, respuestaServidor = :respuesta " +
            "WHERE referenceCC = :referenceCC AND estado IN ('PAGADO', 'CANCELADO', 'GUARDADO') AND subido = 0"

    /** Un rechazo SIN incidencia en el SERVIDOR (400) o una respuesta con otros importes: visible, y deja de reintentarse. */
    const val ANOTAR_CONFLICTO: String =
        "UPDATE cobros_en_maquina SET conflicto = :conflicto WHERE referenceCC = :referenceCC " +
            "AND ((estado IN ('PAGADO', 'CANCELADO', 'GUARDADO') AND subido = 0) OR estado = 'ESPERANDO')"

    /** `1` = el SERVIDOR ya tiene la duda; `0` = la perdió (404 al consultarla): se vuelve a subir. */
    const val MARCAR_DUDA_SUBIDA: String =
        "UPDATE cobros_en_maquina SET dudaSubida = :subida WHERE referenceCC = :referenceCC AND estado = 'ESPERANDO'"

    /**
     * La decisión del gerente llegó del SERVIDOR (R4-4). `cancelPedido` NO se toca: sigue siendo sólo lo que pidió la PC. El
     * desenlace que decidió el gerente queda en `respuestaServidor` ([desenlace]).
     */
    const val RESOLVER: String =
        "UPDATE cobros_en_maquina SET estado = 'RESUELTO', resueltoPor = :resueltoPor, creditCents = :creditCents, " +
            "changeDispensedCents = :changeDispensedCents, subido = 1, dudaSubida = 1, conflicto = :conflicto, " +
            "respuestaServidor = :respuesta WHERE referenceCC = :referenceCC AND estado = 'ESPERANDO'"

    /**
     * C7: los cobros de [venueId] que pueden deber algo al cliente (un PAGADO con su respuesta, un CANCELADO), los más nuevos
     * primero y acotados: la pantalla filtra los que de verdad deben ([deudaDelComprobante]).
     */
    const val CON_COMPROBANTE: String =
        "SELECT * FROM cobros_en_maquina WHERE venueId = :venueId " +
            "AND ((estado IN ('PAGADO', 'RESUELTO') AND respuestaServidor IS NOT NULL) OR estado = 'CANCELADO' " +
            "OR (estado = 'RESUELTO' AND creditCents > changeDispensedCents)) " +
            "ORDER BY creadoEn DESC, referenceCC DESC LIMIT 50"

    /** C7: el ticket del cobro salió de verdad (resultado real de la impresora). */
    const val MARCAR_TICKET_IMPRESO: String = "UPDATE cobros_en_maquina SET ticketImpreso = 1 WHERE referenceCC = :referenceCC"
}

/**
 * El desenlace de una fila final o `RESUELTO` (para el ticket y el comprobante de C7); null mientras no terminó. En
 * `RESUELTO` es el `outcome` que guardó el SERVIDOR ([CobroEnMaquinaEntity.respuestaServidor]); ilegible = null.
 */
val CobroEnMaquinaEntity.desenlace: Desenlace?
    get() = when (estado) {
        EstadoDelIntento.PAGADO, EstadoDelIntento.CANCELADO, EstadoDelIntento.GUARDADO -> Desenlace.valueOf(estado)
        EstadoDelIntento.RESUELTO -> respuestaServidor
            ?.let { runCatching { JSONObject(it).optString("outcome") }.getOrNull() }
            ?.let { outcome -> Desenlace.entries.firstOrNull { it.name == outcome } }
        else -> null
    }
