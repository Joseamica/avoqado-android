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
    /** v22 (adaptación al rediseño del SERVIDOR, r6-6): el emparejamiento con el que ESTA PC empezó el cobro; viaja con su cierre y su duda. */
    val bindingId: String? = null,
    /** v22 (Codex código r1 #14): los renglones congelados al empezar (los de la cotización, o los del carrito sin red): el ticket sale de aquí. */
    val renglonesJson: String? = null,
    /** v22 (Codex código r1 #8): el desenlace que decidió el SERVIDOR de un `RESUELTO`. Sólo `PAGADO` (o desconocido) sigue frenando la tarjeta. */
    val desenlaceDelServidor: String? = null,
    /**
     * v22 (Codex código r1 #6): el cierre de NUESTRO folio y `Due` que llegó por la conexión de la propiedad DESPUÉS de que el intento
     * quedó «Esperando»: sube como cierre (la fila sigue en duda hasta que el SERVIDOR conteste TERMINADO). Sus importes van en
     * `creditCents`/`changeDispensedCents`/`cierreEn`, y `subido` dice si ya llegó.
     */
    val cierreTardio: String? = null,
    /** v22 (Codex código r1 #13): lo que la máquina le debe al cliente (lo físico al cerrar; lo del SERVIDOR al subir). */
    val aDeberCents: Int = 0,
    /** v22 (adaptación, r6-4): la `version` de la duda que contestó el SERVIDOR; el gerente la manda al resolver. */
    val versionDeLaDuda: Int? = null,
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

    /** 🔴 La tabla como la dejó la v21 (`MIGRATION_20_21`), congelada: la v22 le agrega [MIGRAR_21_22]. */
    const val CREAR_TABLA_V21: String =
        "CREATE TABLE IF NOT EXISTS `cobros_en_maquina` (" +
            "`referenceCC` TEXT NOT NULL, `venueId` TEXT NOT NULL, `llaveDeVenta` TEXT NOT NULL, `pedidoJson` TEXT NOT NULL, " +
            "`saleCents` INTEGER NOT NULL, `tipCents` INTEGER NOT NULL, `dueCents` INTEGER NOT NULL, `saleShiftId` TEXT, " +
            "`quoteId` TEXT, `creadoEn` INTEGER NOT NULL, `estado` TEXT NOT NULL, `generacionPropia` INTEGER, " +
            "`ultimoCreditoEn` INTEGER, `creditCents` INTEGER NOT NULL, `changeDispensedCents` INTEGER NOT NULL, " +
            "`cancelPedido` TEXT, `cancelPedidoEn` INTEGER, `cierreEn` INTEGER, `cierreConocido` INTEGER NOT NULL, " +
            "`resueltoPor` TEXT, `subido` INTEGER NOT NULL, `dudaSubida` INTEGER NOT NULL, `conflicto` TEXT, " +
            "`ticketImpreso` INTEGER NOT NULL, `mensajesCrudos` TEXT NOT NULL, `respuestaServidor` TEXT, PRIMARY KEY(`referenceCC`))"

    /** 🔴 En el formato EXACTO que Room genera HOY para [CobroEnMaquinaEntity] (lo vigila `CobroEnMaquinaMigracionTest`). */
    const val CREAR_TABLA: String =
        "CREATE TABLE IF NOT EXISTS `cobros_en_maquina` (" +
            "`referenceCC` TEXT NOT NULL, `venueId` TEXT NOT NULL, `llaveDeVenta` TEXT NOT NULL, `pedidoJson` TEXT NOT NULL, " +
            "`saleCents` INTEGER NOT NULL, `tipCents` INTEGER NOT NULL, `dueCents` INTEGER NOT NULL, `saleShiftId` TEXT, " +
            "`quoteId` TEXT, `creadoEn` INTEGER NOT NULL, `estado` TEXT NOT NULL, `generacionPropia` INTEGER, " +
            "`ultimoCreditoEn` INTEGER, `creditCents` INTEGER NOT NULL, `changeDispensedCents` INTEGER NOT NULL, " +
            "`cancelPedido` TEXT, `cancelPedidoEn` INTEGER, `cierreEn` INTEGER, `cierreConocido` INTEGER NOT NULL, " +
            "`resueltoPor` TEXT, `subido` INTEGER NOT NULL, `dudaSubida` INTEGER NOT NULL, `conflicto` TEXT, " +
            "`ticketImpreso` INTEGER NOT NULL, `mensajesCrudos` TEXT NOT NULL, `respuestaServidor` TEXT, `bindingId` TEXT, " +
            "`renglonesJson` TEXT, `desenlaceDelServidor` TEXT, `cierreTardio` TEXT, `aDeberCents` INTEGER NOT NULL, " +
            "`versionDeLaDuda` INTEGER, PRIMARY KEY(`referenceCC`))"

    /** v21 (C4): la respuesta final del SERVIDOR, en columna propia y nula. Nada que migrar: una fila vieja no la tiene. */
    const val MIGRAR_20_21: String = "ALTER TABLE cobros_en_maquina ADD COLUMN respuestaServidor TEXT"

    /**
     * v22 (Codex código r1 y la adaptación al rediseño del SERVIDOR): seis columnas. Las nulas no migran nada. `aDeberCents` nace con lo
     * físico de lo que ya cerró (PAGADO: lo que entró − el adeudo − el cambio; CANCELADO: lo que no devolvió); el excedente de precio de
     * una fila vieja ya subida no se reconstruye (sigue en `respuestaServidor`).
     */
    val MIGRAR_21_22: List<String> = listOf(
        "ALTER TABLE cobros_en_maquina ADD COLUMN bindingId TEXT",
        "ALTER TABLE cobros_en_maquina ADD COLUMN renglonesJson TEXT",
        "ALTER TABLE cobros_en_maquina ADD COLUMN desenlaceDelServidor TEXT",
        "ALTER TABLE cobros_en_maquina ADD COLUMN cierreTardio TEXT",
        "ALTER TABLE cobros_en_maquina ADD COLUMN aDeberCents INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE cobros_en_maquina ADD COLUMN versionDeLaDuda INTEGER",
        "UPDATE cobros_en_maquina SET aDeberCents = CASE " +
            "WHEN estado = 'PAGADO' AND creditCents > dueCents + changeDispensedCents THEN creditCents - dueCents - changeDispensedCents " +
            "WHEN estado = 'CANCELADO' AND creditCents > changeDispensedCents THEN creditCents - changeDispensedCents ELSE 0 END",
    )

    const val POR_REFERENCIA: String = "SELECT * FROM cobros_en_maquina WHERE referenceCC = :referenceCC"

    /**
     * Lo que impide otro intento de la MISMA venta: uno abierto, uno en duda, uno pagado o uno que el SERVIDOR resolvió como pagado (o
     * sin saber cómo). Un `CANCELADO` o un `GUARDADO`, de la máquina o del gerente (Codex código r1 #8), dejan volver a intentar.
     */
    const val BLOQUEAN_LA_VENTA: String =
        "SELECT COUNT(*) FROM cobros_en_maquina WHERE llaveDeVenta = :llaveDeVenta " +
            "AND (estado IN ('EN_CURSO', 'ESPERANDO', 'PAGADO') OR (estado = 'RESUELTO' AND COALESCE(desenlaceDelServidor, 'PAGADO') = 'PAGADO'))"

    /** Codex código r1 #7: el intento abierto o en duda de esta venta, para que la pantalla de la máquina lo recupere tras reiniciar. */
    const val INTENTO_DE_LA_VENTA: String =
        "SELECT * FROM cobros_en_maquina WHERE llaveDeVenta = :llaveDeVenta AND estado IN ('EN_CURSO', 'ESPERANDO') " +
            "ORDER BY creadoEn DESC, referenceCC DESC LIMIT 1"

    /** Codex código r1 #7: las dudas de ESTA PC en la sucursal, para «Resolver con gerente» desde Cobrar y desde Ajustes. Acotada. */
    const val EN_ESPERA: String =
        "SELECT * FROM cobros_en_maquina WHERE venueId = :venueId AND estado = 'ESPERANDO' " +
            "ORDER BY creadoEn DESC, referenceCC DESC LIMIT 100"

    /** Sólo si el `paycash` no salió (`enviar` = false): nada llegó a la máquina, no hay evidencia que guardar. */
    const val BORRAR_SIN_ENVIAR: String =
        "DELETE FROM cobros_en_maquina WHERE referenceCC = :referenceCC AND estado = 'EN_CURSO' AND generacionPropia IS NULL"

    const val GANAR_PROPIEDAD: String =
        "UPDATE cobros_en_maquina SET generacionPropia = :generacion, mensajesCrudos = :crudos " +
            "WHERE referenceCC = :referenceCC AND estado = 'EN_CURSO'"

    /** Codex código r1 #6: el crédito y lo que vio la PC, juntos: la duda sube siempre lo último que quedó en disco. */
    const val ANOTAR_CREDITO: String =
        "UPDATE cobros_en_maquina SET creditCents = :creditCents, ultimoCreditoEn = :ahora, mensajesCrudos = :crudos " +
            "WHERE referenceCC = :referenceCC AND estado = 'EN_CURSO'"

    const val PEDIR_CANCEL: String =
        "UPDATE cobros_en_maquina SET cancelPedido = :cancelvalue, cancelPedidoEn = :ahora " +
            "WHERE referenceCC = :referenceCC AND estado = 'EN_CURSO'"

    /**
     * El desenlace y la marca de «por subir» en UN `UPDATE`: la fila es la cola (R4-1). Codex código r1 #15: `cierreEn` es la hora que
     * dice la máquina (`conocido`), o la de la PC sólo para mostrar (`conocido = 0`: sube sin hora). #13: lo físico que se le debe.
     */
    const val CERRAR: String =
        "UPDATE cobros_en_maquina SET estado = :estado, creditCents = :creditCents, " +
            "changeDispensedCents = :changeDispensedCents, cierreEn = :cierreEn, cierreConocido = :conocido, " +
            "resueltoPor = 'MAQUINA', subido = 0, mensajesCrudos = :crudos, aDeberCents = :aDeber " +
            "WHERE referenceCC = :referenceCC AND estado = 'EN_CURSO'"

    const val A_ESPERANDO: String =
        "UPDATE cobros_en_maquina SET estado = 'ESPERANDO', dudaSubida = 0, mensajesCrudos = :crudos " +
            "WHERE referenceCC = :referenceCC AND estado = 'EN_CURSO'"

    /** Al arrancar: un `EN_CURSO` que no es el cobro vivo quedó de un proceso que murió. No se sabe cómo terminó. */
    const val HUERFANOS_A_ESPERANDO: String =
        "UPDATE cobros_en_maquina SET estado = 'ESPERANDO', dudaSubida = 0 " +
            "WHERE estado = 'EN_CURSO' AND referenceCC != :salvo"

    /**
     * Lo que la máquina dice de un folio en duda se guarda; el estado no cambia (lo resuelve un gerente). Codex código r1 #6: es una
     * observación nueva y vuelve a subir (`dudaSubida = 0`): el SERVIDOR la agrega a la duda.
     */
    const val ANOTAR_EVIDENCIA: String =
        "UPDATE cobros_en_maquina SET mensajesCrudos = :crudos, dudaSubida = 0 WHERE referenceCC = :referenceCC AND estado = 'ESPERANDO'"

    /**
     * Codex código r1 #6: el cierre tardío de NUESTRO folio (por la conexión de la propiedad) en una fila que ya está en duda. Sólo el
     * primero; la fila sigue `ESPERANDO` (la tarjeta sigue frenada) hasta que el SERVIDOR conteste TERMINADO.
     */
    const val ANOTAR_CIERRE_TARDIO: String =
        "UPDATE cobros_en_maquina SET cierreTardio = :desenlace, creditCents = :creditCents, changeDispensedCents = :changeDispensedCents, " +
            "cierreEn = :cierreEn, cierreConocido = :conocido, subido = 0, mensajesCrudos = :crudos " +
            "WHERE referenceCC = :referenceCC AND estado = 'ESPERANDO' AND cierreTardio IS NULL"

    /**
     * R5#2: lo que frena cerrar sesión, cambiar de sucursal y cambiar la IP o desemparejar la máquina: lo que SÓLO está en esta PC. Un
     * intento abierto, una duda (o su cierre tardío) que el SERVIDOR todavía no tiene, o un cierre final sin acuse. Codex código r1 #9:
     * lo que el SERVIDOR ya guardó como incidencia (una duda subida, en conflicto o no) ya no frena: se sigue consultando por su folio.
     * `:venueId` null = todas.
     */
    const val PENDIENTES: String =
        "SELECT COUNT(*) FROM cobros_en_maquina WHERE (:venueId IS NULL OR venueId = :venueId) " +
            "AND (estado = 'EN_CURSO' " +
            "OR (estado = 'ESPERANDO' AND (dudaSubida = 0 OR (cierreTardio IS NOT NULL AND subido = 0 AND conflicto IS NULL))) " +
            "OR (estado IN ('PAGADO', 'CANCELADO', 'GUARDADO') AND subido = 0))"

    const val POR_SUBIR: String =
        "SELECT * FROM cobros_en_maquina " +
            "WHERE (estado IN ('PAGADO', 'CANCELADO', 'GUARDADO') AND subido = 0) OR (estado = 'ESPERANDO' AND dudaSubida = 0) " +
            "OR (estado = 'ESPERANDO' AND cierreTardio IS NOT NULL AND subido = 0) " +
            "ORDER BY creadoEn ASC, referenceCC ASC"

    // MARK: - La subida (C4)

    /**
     * Lo que la subida manda de [venueId]: [POR_SUBIR] sin lo que ya tiene `conflicto` (409, 422 o una respuesta con otros
     * importes): eso espera a un gerente y no se reintenta. Sigue contando en [PENDIENTES].
     */
    const val PARA_SUBIR: String =
        "SELECT * FROM cobros_en_maquina WHERE venueId = :venueId " +
            "AND ((estado IN ('PAGADO', 'CANCELADO', 'GUARDADO') AND subido = 0 AND conflicto IS NULL) " +
            // Codex código r1 #9: una duda en conflicto CON su incidencia (respuesta guardada) sí sube su observación nueva.
            "OR (estado = 'ESPERANDO' AND dudaSubida = 0 AND (conflicto IS NULL OR respuestaServidor IS NOT NULL))) " +
            "ORDER BY creadoEn ASC, referenceCC ASC"

    /** Codex código r1 #6: los cierres tardíos de dudas que el SERVIDOR ya tiene (la duda va primero: congela el carrito). */
    const val TARDIOS_PARA_SUBIR: String =
        "SELECT * FROM cobros_en_maquina WHERE venueId = :venueId AND estado = 'ESPERANDO' AND dudaSubida = 1 " +
            "AND cierreTardio IS NOT NULL AND subido = 0 AND conflicto IS NULL ORDER BY creadoEn ASC, referenceCC ASC"

    /** El SERVIDOR ya guardó el cierre tardío (o su incidencia): no se reenvía; la duda sigue consultándose. */
    const val MARCAR_TARDIO_SUBIDO: String =
        "UPDATE cobros_en_maquina SET subido = 1, conflicto = :conflicto, respuestaServidor = :respuesta " +
            "WHERE referenceCC = :referenceCC AND estado = 'ESPERANDO' AND cierreTardio IS NOT NULL"

    /**
     * Las dudas ya subidas que esperan la decisión del gerente (R5#6): se consultan cada 60 s. Codex código r1 #9: también las que el
     * SERVIDOR guardó en conflicto (con su incidencia): siguen consultándose por su folio hasta que se resuelvan.
     */
    const val ESPERAN_AL_GERENTE: String =
        "SELECT * FROM cobros_en_maquina WHERE venueId = :venueId AND estado = 'ESPERANDO' AND dudaSubida = 1 " +
            "ORDER BY creadoEn ASC, referenceCC ASC"

    /** Codex código r1 #9: el SERVIDOR guardó la duda en conflicto con su incidencia: ya la tiene (no frena), se ve y se consulta. */
    const val MARCAR_DUDA_EN_CONFLICTO: String =
        "UPDATE cobros_en_maquina SET dudaSubida = 1, conflicto = :conflicto, respuestaServidor = :respuesta " +
            "WHERE referenceCC = :referenceCC AND estado = 'ESPERANDO'"

    /**
     * El SERVIDOR repitió el folio y los importes (200), guardó el dinero como incidencia (202) o rechazó el cierre DEJANDO su
     * incidencia durable (409/422 con `incidentId`): ya no se reintenta ni frena cerrar sesión, y lo que dijo queda visible en
     * `conflicto` y completo en `respuestaServidor`.
     */
    const val MARCAR_SUBIDO: String =
        "UPDATE cobros_en_maquina SET subido = 1, conflicto = :conflicto, respuestaServidor = :respuesta, " +
            // Codex código r1 #13: lo que dice el SERVIDOR que se debe (cambio físico + diferencia de precio) manda sobre lo físico.
            "aDeberCents = COALESCE(:aDeber, aDeberCents) " +
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
            "respuestaServidor = :respuesta, desenlaceDelServidor = :desenlace, aDeberCents = COALESCE(:aDeber, aDeberCents) " +
            "WHERE referenceCC = :referenceCC AND estado = 'ESPERANDO'"

    /**
     * C7 y Codex código r1 #13: los cobros de [venueId] que le deben algo al cliente (cambio que no dio, dinero que no devolvió o la
     * diferencia de precio), filtrados EN la consulta y por páginas: el tope ya no deja fuera una deuda detrás de cobros que no deben.
     * Un GUARDADO no tiene comprobante (nadie está para recibirlo).
     */
    const val CON_COMPROBANTE: String =
        "SELECT * FROM cobros_en_maquina WHERE venueId = :venueId AND aDeberCents > 0 " +
            "AND (estado IN ('PAGADO', 'CANCELADO') OR (estado = 'RESUELTO' AND desenlaceDelServidor IN ('PAGADO', 'CANCELADO'))) " +
            "ORDER BY creadoEn DESC, referenceCC DESC LIMIT :limite"

    const val CONTAR_COMPROBANTES: String =
        "SELECT COUNT(*) FROM cobros_en_maquina WHERE venueId = :venueId AND aDeberCents > 0 " +
            "AND (estado IN ('PAGADO', 'CANCELADO') OR (estado = 'RESUELTO' AND desenlaceDelServidor IN ('PAGADO', 'CANCELADO')))"

    /**
     * Codex código r1 #12: las ventas pagadas (por la máquina, o las que un gerente resolvió como venta) cuyo ticket no salió: la PC se
     * cerró entre el PAGADO y la impresión, o la impresora falló. Se ofrecen con un botón; nunca se imprimen solas. Sin las que quedaron
     * como incidencia (no son venta).
     */
    const val TICKETS_SIN_IMPRIMIR: String =
        "SELECT * FROM cobros_en_maquina WHERE venueId = :venueId AND ticketImpreso = 0 " +
            "AND (estado = 'PAGADO' OR (estado = 'RESUELTO' AND desenlaceDelServidor = 'PAGADO' AND conflicto IS NULL)) " +
            "ORDER BY creadoEn ASC, referenceCC ASC LIMIT 50"

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
