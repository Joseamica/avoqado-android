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
    /** Ya no se escribe (Codex código r2 H13: la hora de recepción no es evidencia); la columna se queda por las bases v20–v22. */
    val ultimoCreditoEn: Long? = null,
    /** Lo más que la PC vio entrar de este folio (del diario: su `status` o el `updateCredit` atribuible); al cerrar, lo de la máquina. */
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
    /**
     * El papel del folio ([TicketDelCobro]: pendiente, impreso o imprimiéndose). La columna es INTEGER NOT NULL desde la v20 (antes se
     * leía como Boolean): el 2 de «imprimiéndose» no cambia el esquema (Codex código r2 H9).
     */
    val ticketImpreso: Int = TicketDelCobro.PENDIENTE,
    /**
     * El DIARIO del folio (Codex código r2, bloque A): cada mensaje físico de la máquina que es de ESTE folio, tal cual llegó y una sola
     * vez (`g<generación> <json>`, ≤ 8 KB, [DiarioDelFolio]). Se escribe ANTES de decidir nada y por el folio del mensaje, nunca por el
     * intento en memoria. Es la evidencia para el gerente y lo que sube la duda.
     */
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
     * v22, ya no se escribe (Codex código r2 H2): un cierre que llega con la fila en duda es sólo evidencia en el diario, nunca un
     * desenlace. La columna se queda por las bases v22.
     */
    val cierreTardio: String? = null,
    /** v22 (Codex código r1 #13): lo que la máquina le debe al cliente (lo físico al cerrar; lo del SERVIDOR al subir). */
    val aDeberCents: Int = 0,
    /** v22 (adaptación, r6-4): la `version` de la duda que contestó el SERVIDOR; el gerente la manda al resolver. */
    val versionDeLaDuda: Int? = null,
    /**
     * v23 (Codex código r3 #13): la huella de CADA mensaje físico que llegó de este folio, sin recorte ([DiarioDelFolio]). El texto
     * del diario se recorta a 8 KB; esto no: un mensaje que APS repite nunca vuelve a contar como evidencia nueva.
     */
    val huellasDelDiario: String = "",
    /** v23, ya no se escribe ni se lee (recorte R3 de la 1a, 21-nov: sin comanda a cocina). La columna se queda por las bases v23. */
    val comandaJson: String? = null,
    /**
     * v24 (Codex código POS r7, decisión del coordinador): la EVIDENCIA FÍSICA del folio, en columnas fijas que sólo crecen
     * ([CobroEnMaquinaSql.ANOTAR_LO_FISICO]). Se escriben con CADA mensaje físico de este folio, en cualquier estado de la fila (también
     * PAGADO, RESUELTO o en conflicto: nunca «0 filas»). El papel lee sólo esto y el acuse del SERVIDOR ([loQueContoLaMaquina],
     * [porRevisarEnElPanel]); el diario ya sólo sube, y recortarlo no cambia nada de lo impreso.
     *
     * Lo más que la máquina dijo haber contado de este folio con su `Due`.
     */
    val creditoFisicoCents: Int = 0,
    /** v24: el desenlace del PRIMER cierre físico posible de este folio y su `Due` ([Cierre]: `PAGADO` o `CANCELADO`); null = ninguno. */
    val cierreFisico: String? = null,
    /** v24: el cambio que confirmó ese cierre. */
    val cambioFisicoCents: Int? = null,
    /**
     * v24, PEGAJOSA: la máquina se contradijo sobre este folio —otro cierre con otro desenlace u otro cambio, un cierre imposible o de otro
     * total, o (ya terminado aquí) más dinero del registrado—. Nunca vuelve a false: ni otro mensaje ni recortar el diario la borran.
     */
    val maquinaSeContradijo: Boolean = false,
)

/**
 * El SQL de la tabla, en constantes, para que las pruebas lo EJECUTEN contra SQLite de verdad (patrón `KdsLanSql`). Cada
 * escritura de un desenlace sólo toca una fila `EN_CURSO`: un cierre repetido o tardío no reescribe lo que ya terminó.
 */
internal object CobroEnMaquinaSql {
    /**
     * Codex código r2 H1 y POS r9 (F5): la evidencia de la fila sigue siendo la que se MANDÓ (su diario y lo que entró, de la lectura con que
     * se armó el envío). TODO escritor que aplica una respuesta del SERVIDOR a una fila la lleva: si mientras tanto la máquina dijo algo nuevo
     * (el diario sólo crece: otro texto = otra observación), la respuesta no toca la fila (0 filas) y lo nuevo sube en la pasada siguiente.
     */
    private const val LA_MISMA_EVIDENCIA = "mensajesCrudos = :crudos AND creditCents = :creditoEnviado"

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

    /** 🔴 La tabla como la dejó la v22 (`MIGRATION_21_22`), congelada: la v23 le agrega [MIGRAR_22_23]. */
    const val CREAR_TABLA_V22: String =
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

    /** 🔴 La tabla como la dejó la v23 (`MIGRATION_22_23`), congelada: la v24 le agrega [MIGRAR_23_24]. */
    const val CREAR_TABLA_V23: String =
        "CREATE TABLE IF NOT EXISTS `cobros_en_maquina` (" +
            "`referenceCC` TEXT NOT NULL, `venueId` TEXT NOT NULL, `llaveDeVenta` TEXT NOT NULL, `pedidoJson` TEXT NOT NULL, " +
            "`saleCents` INTEGER NOT NULL, `tipCents` INTEGER NOT NULL, `dueCents` INTEGER NOT NULL, `saleShiftId` TEXT, " +
            "`quoteId` TEXT, `creadoEn` INTEGER NOT NULL, `estado` TEXT NOT NULL, `generacionPropia` INTEGER, " +
            "`ultimoCreditoEn` INTEGER, `creditCents` INTEGER NOT NULL, `changeDispensedCents` INTEGER NOT NULL, " +
            "`cancelPedido` TEXT, `cancelPedidoEn` INTEGER, `cierreEn` INTEGER, `cierreConocido` INTEGER NOT NULL, " +
            "`resueltoPor` TEXT, `subido` INTEGER NOT NULL, `dudaSubida` INTEGER NOT NULL, `conflicto` TEXT, " +
            "`ticketImpreso` INTEGER NOT NULL, `mensajesCrudos` TEXT NOT NULL, `respuestaServidor` TEXT, `bindingId` TEXT, " +
            "`renglonesJson` TEXT, `desenlaceDelServidor` TEXT, `cierreTardio` TEXT, `aDeberCents` INTEGER NOT NULL, " +
            "`versionDeLaDuda` INTEGER, `huellasDelDiario` TEXT NOT NULL, `comandaJson` TEXT, PRIMARY KEY(`referenceCC`))"

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
            "`versionDeLaDuda` INTEGER, `huellasDelDiario` TEXT NOT NULL, `comandaJson` TEXT, `creditoFisicoCents` INTEGER NOT NULL, " +
            "`cierreFisico` TEXT, `cambioFisicoCents` INTEGER, `maquinaSeContradijo` INTEGER NOT NULL, PRIMARY KEY(`referenceCC`))"

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

    /**
     * v23 (Codex código r3 #13 y #6): las huellas del diario (vacías: un folio de antes se compara además con su texto, y la primera
     * escritura siembra las huellas de lo que ya tenía) y la comanda congelada (nula: un cobro de antes no despacha nada nuevo).
     */
    val MIGRAR_22_23: List<String> = listOf(
        "ALTER TABLE cobros_en_maquina ADD COLUMN huellasDelDiario TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE cobros_en_maquina ADD COLUMN comandaJson TEXT",
    )

    /**
     * v24 (Codex código POS r7): la evidencia física en columnas. Un cierre de la máquina de antes ya la tenía en su fila (el motor la
     * escribió de un `status` de cierre): ésa se copia; lo demás arranca vacío (una duda resuelta de antes sale «Revisar en el panel»).
     * ponytail: un segundo cierre que sólo estaba en el diario de una fila de antes no se reconstruye (la 1a no está en producción).
     */
    val MIGRAR_23_24: List<String> = listOf(
        "ALTER TABLE cobros_en_maquina ADD COLUMN creditoFisicoCents INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE cobros_en_maquina ADD COLUMN cierreFisico TEXT",
        "ALTER TABLE cobros_en_maquina ADD COLUMN cambioFisicoCents INTEGER",
        "ALTER TABLE cobros_en_maquina ADD COLUMN maquinaSeContradijo INTEGER NOT NULL DEFAULT 0",
        "UPDATE cobros_en_maquina SET creditoFisicoCents = creditCents WHERE estado != 'RESUELTO'",
        "UPDATE cobros_en_maquina SET cierreFisico = CASE estado WHEN 'PAGADO' THEN 'PAGADO' ELSE 'CANCELADO' END, " +
            "cambioFisicoCents = changeDispensedCents " +
            "WHERE estado IN ('PAGADO', 'CANCELADO', 'GUARDADO') AND resueltoPor = 'MAQUINA'",
    )

    /**
     * Codex código POS r7: lo físico de un mensaje de ESTE folio, en CUALQUIER estado de la fila. Todo sólo crece: el crédito con MAX, el
     * primer cierre posible se queda y la contradicción nunca se borra. SQLite evalúa cada SET con la fila de ANTES: se compara contra el
     * primer cierre. Ya terminado aquí (PAGADO, CANCELADO o GUARDADO), más dinero que el registrado también es contradicción.
     */
    const val ANOTAR_LO_FISICO: String =
        "UPDATE cobros_en_maquina SET creditoFisicoCents = MAX(creditoFisicoCents, :credito), " +
            "cierreFisico = COALESCE(cierreFisico, :cierre), " +
            "cambioFisicoCents = CASE WHEN cierreFisico IS NULL THEN :cambio ELSE cambioFisicoCents END, " +
            "maquinaSeContradijo = CASE WHEN maquinaSeContradijo = 1 OR :imposible = 1 " +
            "OR (cierreFisico IS NOT NULL AND :cierre IS NOT NULL AND (cierreFisico != :cierre OR cambioFisicoCents != :cambio)) " +
            "OR (estado IN ('PAGADO', 'CANCELADO', 'GUARDADO') AND :credito > creditCents) THEN 1 ELSE 0 END " +
            "WHERE referenceCC = :referenceCC"

    /**
     * Codex código POS r7: la máquina se contradijo DESPUÉS de que el SERVIDOR ya tenía el cierre de este folio. Esa evidencia sólo está
     * en esta PC: sube como duda (el SERVIDOR la anota para el gerente, «TRAS_RESOLVER») y frena salir hasta que la tenga.
     */
    private const val EVIDENCIA_TARDIA =
        "(estado IN ('PAGADO', 'CANCELADO', 'GUARDADO') AND subido = 1 AND maquinaSeContradijo = 1 AND dudaSubida = 0)"

    const val POR_REFERENCIA: String = "SELECT * FROM cobros_en_maquina WHERE referenceCC = :referenceCC"

    /** Las filas de una venta que PUEDEN impedir cobrarla otra vez; cuáles la frenan lo decide UNA regla ([bloqueaLaVenta]). */
    const val FILAS_DE_LA_VENTA: String =
        "SELECT * FROM cobros_en_maquina WHERE llaveDeVenta = :llaveDeVenta " +
            "AND (estado IN ('EN_CURSO', 'ESPERANDO', 'PAGADO', 'RESUELTO') OR maquinaSeContradijo = 1)"

    /** Codex código r1 #7: el intento abierto o en duda de esta venta, para que la pantalla de la máquina lo recupere tras reiniciar. */
    const val INTENTO_DE_LA_VENTA: String =
        "SELECT * FROM cobros_en_maquina WHERE llaveDeVenta = :llaveDeVenta AND estado IN ('EN_CURSO', 'ESPERANDO') " +
            "ORDER BY creadoEn DESC, referenceCC DESC LIMIT 1"

    /** Sólo si el `paycash` no salió (`enviar` = false): nada llegó a la máquina, no hay evidencia que guardar. */
    const val BORRAR_SIN_ENVIAR: String =
        "DELETE FROM cobros_en_maquina WHERE referenceCC = :referenceCC AND estado = 'EN_CURSO' AND generacionPropia IS NULL"

    const val GANAR_PROPIEDAD: String =
        "UPDATE cobros_en_maquina SET generacionPropia = :generacion WHERE referenceCC = :referenceCC AND estado = 'EN_CURSO'"

    /**
     * Codex código r2, bloque A: el diario de un folio crece con [CobroEnMaquinaDao.anotarMensaje], con la condición de que nadie lo
     * haya cambiado desde que se leyó (sin carrera aunque dos escriban). Sólo SUBE (Codex código POS r7: el papel no lo lee). Lo que no
     * sea un intento abierto es evidencia nueva y vuelve a subir (`dudaSubida = 0`): la duda, la resuelta (Codex código r3 #1) y la ya
     * terminada aquí en la que la máquina se contradijo ([CobroEnMaquinaDao.anotarMensaje] sólo la escribe entonces).
     *
     * Codex código POS r8 #3 (F2): un rechazo SIN incidencia (`conflicto` sin `respuestaServidor`: un 400, otros importes) era de la evidencia
     * de antes; con evidencia nueva se suelta y la fila vuelve a subir una vez. Con incidencia (hay respuesta) el conflicto se queda: esa fila ya
     * sube por su propia regla.
     */
    const val ESCRIBIR_DIARIO: String =
        "UPDATE cobros_en_maquina SET mensajesCrudos = :nuevo, huellasDelDiario = :huellas, " +
            // Lo registrado de un cierre ya terminado aquí no cambia: su evidencia nueva vive en las columnas físicas.
            "creditCents = CASE WHEN estado IN ('EN_CURSO', 'ESPERANDO', 'RESUELTO') THEN :credito ELSE creditCents END, " +
            "dudaSubida = CASE WHEN estado != 'EN_CURSO' THEN 0 ELSE dudaSubida END, " +
            "conflicto = CASE WHEN estado != 'EN_CURSO' AND respuestaServidor IS NULL THEN NULL ELSE conflicto END " +
            "WHERE referenceCC = :referenceCC AND mensajesCrudos = :anterior AND creditCents = :creditoAnterior"

    /**
     * Codex código POS r8 (F1): el cierre de la fila NO es confiable —la máquina se contradijo, o no hay cierre físico—. La misma condición que
     * [loQueContoLaMaquina] devuelve sin `cierre` (lo vigila `MaquinaDeEfectivoTest`).
     */
    const val SIN_CIERRE_CONFIABLE: String = "(maquinaSeContradijo = 1 OR cierreFisico IS NULL OR cambioFisicoCents IS NULL)"

    /**
     * Codex código POS r8 #3 y #4 (F1, F2): un cierre terminado aquí que el SERVIDOR todavía no tiene (`subido = 0`: por subir, de OTRA PC o
     * rechazado) y cuyo cierre ya no es confiable deja de ser un desenlace: vuelve a «Esperando a la máquina» —lo mismo que hace el motor con un
     * cierre contradictorio— y sube como duda con lo que contó la máquina. La venta limpia del primer cierre nunca se registra; el gerente lo
     * resuelve en el panel. El conflicto anterior era de otra evidencia: se suelta (la duda lo vuelve a saber). [referenceCC] null = todas.
     */
    const val A_DUDA_SIN_CIERRE_CONFIABLE: String =
        "UPDATE cobros_en_maquina SET estado = 'ESPERANDO', dudaSubida = 0, conflicto = NULL, resueltoPor = NULL " +
            "WHERE estado IN ('PAGADO', 'CANCELADO', 'GUARDADO') AND subido = 0 AND $SIN_CIERRE_CONFIABLE " +
            "AND (:referenceCC IS NULL OR referenceCC = :referenceCC)"

    const val PEDIR_CANCEL: String =
        "UPDATE cobros_en_maquina SET cancelPedido = :cancelvalue, cancelPedidoEn = :ahora " +
            "WHERE referenceCC = :referenceCC AND estado = 'EN_CURSO'"

    /**
     * El desenlace y la marca de «por subir» en UN `UPDATE`: la fila es la cola (R4-1). Codex código r1 #15: `cierreEn` es la hora que
     * dice la máquina (`conocido`), o la de la PC sólo para mostrar (`conocido = 0`: sube sin hora). #13: lo físico que se le debe. El
     * mensaje del cierre ya está en el diario (se anota antes de decidir).
     */
    const val CERRAR: String =
        "UPDATE cobros_en_maquina SET estado = :estado, creditCents = :creditCents, " +
            "changeDispensedCents = :changeDispensedCents, cierreEn = :cierreEn, cierreConocido = :conocido, " +
            "resueltoPor = 'MAQUINA', subido = 0, aDeberCents = :aDeber " +
            "WHERE referenceCC = :referenceCC AND estado = 'EN_CURSO'"

    const val A_ESPERANDO: String =
        "UPDATE cobros_en_maquina SET estado = 'ESPERANDO', dudaSubida = 0 WHERE referenceCC = :referenceCC AND estado = 'EN_CURSO'"

    /** Al arrancar: un `EN_CURSO` que no es el cobro vivo quedó de un proceso que murió. No se sabe cómo terminó. */
    const val HUERFANOS_A_ESPERANDO: String =
        "UPDATE cobros_en_maquina SET estado = 'ESPERANDO', dudaSubida = 0 " +
            "WHERE estado = 'EN_CURSO' AND referenceCC != :salvo"

    /**
     * R5#2 y Codex código r3 #2, #3: las filas que PUEDEN frenar salir (cerrar sesión, cambiar de sucursal, desemparejar o cambiar la IP).
     * Cuáles frenan lo decide UNA regla ([frenaLaSalida]), la misma para las cuatro salidas.
     */
    const val PUEDEN_FRENAR: String =
        "SELECT * FROM cobros_en_maquina WHERE estado IN ('EN_CURSO', 'ESPERANDO') " +
            "OR (estado IN ('PAGADO', 'CANCELADO', 'GUARDADO') AND subido = 0) OR (estado = 'RESUELTO' AND dudaSubida = 0) " +
            "OR $EVIDENCIA_TARDIA"

    const val POR_SUBIR: String =
        "SELECT * FROM cobros_en_maquina " +
            "WHERE (estado IN ('PAGADO', 'CANCELADO', 'GUARDADO') AND subido = 0) OR (estado IN ('ESPERANDO', 'RESUELTO') AND dudaSubida = 0) " +
            "OR $EVIDENCIA_TARDIA ORDER BY creadoEn ASC, referenceCC ASC"

    // MARK: - La subida (C4)

    /**
     * Lo que la subida manda de [venueId]: [POR_SUBIR] sin lo que ya tiene `conflicto` (409, 422 o una respuesta con otros
     * importes): eso espera a un gerente y no se reintenta. Sigue contando en [PENDIENTES].
     */
    private const val SE_SUBE: String =
        "((estado IN ('PAGADO', 'CANCELADO', 'GUARDADO') AND subido = 0 AND conflicto IS NULL) " +
            // Codex código r1 #9: una duda en conflicto CON su incidencia (respuesta guardada) sí sube su observación nueva.
            "OR (estado = 'ESPERANDO' AND dudaSubida = 0 AND (conflicto IS NULL OR respuestaServidor IS NOT NULL)) " +
            // Codex código r3 #1: lo que la máquina dijo DESPUÉS de que un gerente lo resolviera sube como duda (el SERVIDOR abre la
            // revisión «TRAS_RESOLVER»).
            "OR (estado = 'RESUELTO' AND dudaSubida = 0) OR $EVIDENCIA_TARDIA)"

    const val PARA_SUBIR: String =
        "SELECT * FROM cobros_en_maquina WHERE venueId = :venueId AND $SE_SUBE ORDER BY creadoEn ASC, referenceCC ASC"

    /**
     * Codex código POS r9 #1 (F5): el folio [referenceCC] tal como está AHORA, si todavía es de los que suben ([PARA_SUBIR]). Cada envío se
     * arma de esta lectura, justo antes de mandarlo, nunca de la foto del lote (la máquina pudo corregirlo mientras subía otro).
     */
    const val PARA_SUBIR_EL_FOLIO: String =
        "SELECT * FROM cobros_en_maquina WHERE venueId = :venueId AND referenceCC = :referenceCC AND $SE_SUBE"

    /**
     * Las dudas ya subidas que esperan la decisión del gerente (R5#6): se consultan cada 60 s. Codex código r1 #9: también las que el
     * SERVIDOR guardó en conflicto (con su incidencia): siguen consultándose por su folio hasta que se resuelvan.
     */
    const val ESPERAN_AL_GERENTE: String =
        "SELECT * FROM cobros_en_maquina WHERE venueId = :venueId AND estado = 'ESPERANDO' AND dudaSubida = 1 " +
            "ORDER BY creadoEn ASC, referenceCC ASC"

    /** Codex código POS r9 (F5): la duda [referenceCC] tal como está AHORA, si todavía espera al gerente ([ESPERAN_AL_GERENTE]). */
    const val ESPERA_AL_GERENTE_EL_FOLIO: String =
        "SELECT * FROM cobros_en_maquina WHERE venueId = :venueId AND referenceCC = :referenceCC AND estado = 'ESPERANDO' AND dudaSubida = 1"

    /**
     * Codex código r1 #9: el SERVIDOR guardó la duda en conflicto con su incidencia: ya la tiene (no frena), se ve. Codex código r2 H1: el
     * acuse es de la evidencia que SE MANDÓ: si mientras tanto llegó otra al diario, ésa sigue por subir. Codex código POS r9 (F5): y entonces
     * la respuesta no toca nada (tampoco el conflicto): la evidencia nueva sube y trae su propia respuesta.
     */
    const val MARCAR_DUDA_EN_CONFLICTO: String =
        "UPDATE cobros_en_maquina SET dudaSubida = 1, conflicto = :conflicto, respuestaServidor = :respuesta " +
            "WHERE referenceCC = :referenceCC AND estado = 'ESPERANDO' AND $LA_MISMA_EVIDENCIA"


    /** Codex código r2 H8: el SERVIDOR atribuye este folio a OTRA PC: su incidencia queda visible y no se reintenta; NO es subido. */
    const val MARCAR_DE_OTRA_PC: String =
        "UPDATE cobros_en_maquina SET conflicto = :conflicto, respuestaServidor = :respuesta " +
            "WHERE referenceCC = :referenceCC AND estado IN ('PAGADO', 'CANCELADO', 'GUARDADO') AND subido = 0 AND $LA_MISMA_EVIDENCIA"

    /**
     * El SERVIDOR repitió el folio y los importes (200), guardó el dinero como incidencia (202) o rechazó el cierre DEJANDO su
     * incidencia durable (409/422 con `incidentId`): ya no se reintenta ni frena cerrar sesión, y lo que dijo queda visible en
     * `conflicto` y completo en `respuestaServidor`.
     */
    const val MARCAR_SUBIDO: String =
        "UPDATE cobros_en_maquina SET subido = 1, conflicto = :conflicto, respuestaServidor = :respuesta, " +
            // Codex código r1 #13: lo que dice el SERVIDOR que se debe (cambio físico + diferencia de precio) manda sobre lo físico.
            "aDeberCents = COALESCE(:aDeber, aDeberCents) " +
            "WHERE referenceCC = :referenceCC AND estado IN ('PAGADO', 'CANCELADO', 'GUARDADO') AND subido = 0 AND $LA_MISMA_EVIDENCIA"

    /**
     * Un rechazo SIN incidencia en el SERVIDOR (400) o una respuesta con otros importes: visible, y deja de reintentarse. Codex código POS r9
     * #2: sólo de la evidencia que se mandó; un 400 del envío de $0 nunca saca de la cola la fila que ya trae $50.
     */
    const val ANOTAR_CONFLICTO: String =
        "UPDATE cobros_en_maquina SET conflicto = :conflicto WHERE referenceCC = :referenceCC " +
            "AND ((estado IN ('PAGADO', 'CANCELADO', 'GUARDADO') AND subido = 0) OR estado = 'ESPERANDO') AND $LA_MISMA_EVIDENCIA"

    /**
     * Rediseño del SERVIDOR (r6-4): la duda ABIERTA, con la `version` de su evidencia. Codex código r2 H1: sólo si en la fila sigue la
     * evidencia que se mandó; si llegó una observación nueva mientras tanto, la fila sigue por subir (un acuse viejo no la tapa).
     */
    const val MARCAR_DUDA_ABIERTA: String =
        "UPDATE cobros_en_maquina SET dudaSubida = 1, versionDeLaDuda = COALESCE(:version, versionDeLaDuda) " +
            "WHERE referenceCC = :referenceCC AND estado = 'ESPERANDO' AND $LA_MISMA_EVIDENCIA"

    /**
     * Codex código r3 #1 y POS r7: el SERVIDOR ya tiene la evidencia que llegó DESPUÉS de la resolución o del cierre que ya tenía (la que
     * se MANDÓ): deja de subir y de frenar salir. Si mientras tanto llegó otra, sigue por subir.
     */
    const val MARCAR_EVIDENCIA_ENTREGADA: String =
        "UPDATE cobros_en_maquina SET dudaSubida = 1 WHERE referenceCC = :referenceCC " +
            "AND (estado = 'RESUELTO' OR (estado IN ('PAGADO', 'CANCELADO', 'GUARDADO') AND subido = 1)) AND $LA_MISMA_EVIDENCIA"

    /** `1` = el SERVIDOR ya tiene la duda; `0` = la perdió (404 al consultarla): se vuelve a subir. */
    const val MARCAR_DUDA_SUBIDA: String =
        "UPDATE cobros_en_maquina SET dudaSubida = :subida WHERE referenceCC = :referenceCC AND estado = 'ESPERANDO'"

    /**
     * La decisión del gerente llegó del SERVIDOR (R4-4). `cancelPedido` NO se toca: sigue siendo sólo lo que pidió la PC. El
     * desenlace que decidió el gerente queda en `respuestaServidor` ([desenlace]). Codex código r3 #1: si la fila tiene evidencia que el
     * SERVIDOR todavía no recibió (`dudaSubida = 0`), la resolución NO la borra: lo que entró nunca baja y la evidencia sigue por subir.
     * Codex código POS r9 (F5): sólo si la fila sigue con la evidencia de la petición que trajo esta respuesta; si no, la duda sigue y lo nuevo
     * sube (el SERVIDOR vuelve a contestar la resolución, ya con eso).
     */
    const val RESOLVER: String =
        "UPDATE cobros_en_maquina SET estado = 'RESUELTO', resueltoPor = :resueltoPor, " +
            "creditCents = CASE WHEN dudaSubida = 0 THEN MAX(creditCents, :creditCents) ELSE :creditCents END, " +
            "changeDispensedCents = :changeDispensedCents, subido = 1, conflicto = :conflicto, " +
            "respuestaServidor = :respuesta, desenlaceDelServidor = :desenlace, aDeberCents = COALESCE(:aDeber, aDeberCents) " +
            "WHERE referenceCC = :referenceCC AND estado = 'ESPERANDO' AND $LA_MISMA_EVIDENCIA"

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
     * Codex código r1 #12 y r2 H9/H10: los cobros pagados (por la máquina, o los que un gerente resolvió como PAGADO) cuyo papel no salió:
     * la PC se cerró entre el PAGADO y la impresión, la impresora falló, o la PC murió imprimiéndolo (2: se da como copia). Se ofrecen con
     * un botón; nunca se imprimen solos. El papel lo decide [papelDeLaFila]: con incidencia es el comprobante del dinero, no un ticket.
     */
    const val TICKETS_SIN_IMPRIMIR: String =
        "SELECT * FROM cobros_en_maquina WHERE venueId = :venueId AND ticketImpreso IN (0, 2) " +
            "AND (estado = 'PAGADO' OR (estado = 'RESUELTO' AND desenlaceDelServidor = 'PAGADO')) " +
            "ORDER BY creadoEn ASC, referenceCC ASC LIMIT 50"

    /**
     * Codex código r2 H9: quien imprime el papel ORIGINAL de un folio lo reclama primero; sólo uno gana (1 = ganó). Si la PC muere entre
     * el papel y la marca, el folio queda «imprimiéndose» y se ofrece como reimpresión (copia), nunca como otro original.
     */
    const val RECLAMAR_TICKET: String =
        "UPDATE cobros_en_maquina SET ticketImpreso = 2 WHERE referenceCC = :referenceCC AND ticketImpreso = 0"

    /** El resultado REAL de la impresora para un papel reclamado: 1 salió, 0 no salió (otra vez pendiente), 2 se queda como copia por dar. */
    const val TERMINAR_TICKET: String =
        "UPDATE cobros_en_maquina SET ticketImpreso = :valor WHERE referenceCC = :referenceCC AND ticketImpreso = 2"
}

/**
 * Codex código r3 #2, #3, R5#2 y Codex código POS r4 #4, #5: LA regla de qué impide salir de esta PC —cerrar sesión, cambiar de
 * sucursal, desemparejar o cambiar la IP de la máquina—. La misma para las cuatro.
 * - Frena lo que sólo esta PC puede terminar: un intento abierto, una duda PROPIA que espera su desenlace (aunque el SERVIDOR ya la
 *   tenga: mientras siga abierta, el dinero de esa venta no tiene dueño fuera de aquí), un cierre sin acuse ni incidencia allá, y la
 *   evidencia que sólo está en esta PC: la que llegó después de resolver, o la nueva de un folio de OTRA PC o de una duda que el
 *   SERVIDOR guardó como incidencia (422).
 * - Nunca frena lo que el SERVIDOR ya tiene (resuelto con su evidencia, subido o guardado como incidencia).
 */
fun CobroEnMaquinaEntity.frenaLaSalida(): Boolean = when (estado) {
    EstadoDelIntento.EN_CURSO -> true
    EstadoDelIntento.ESPERANDO -> if (esDeOtraPc() || esIncidenciaSinDuda()) !dudaSubida else true
    EstadoDelIntento.PAGADO, EstadoDelIntento.CANCELADO, EstadoDelIntento.GUARDADO ->
        (!subido && !(conflicto != null && respuestaServidor != null)) || evidenciaTardiaPorSubir()
    EstadoDelIntento.RESUELTO -> !dudaSubida
    else -> false
}

/** Codex código POS r7: ya terminada aquí y el SERVIDOR con su cierre, la máquina se contradijo y eso sólo está en esta PC. */
fun CobroEnMaquinaEntity.evidenciaTardiaPorSubir(): Boolean = subido && maquinaSeContradijo && !dudaSubida && terminadaAqui()

private fun CobroEnMaquinaEntity.terminadaAqui() =
    estado == EstadoDelIntento.PAGADO || estado == EstadoDelIntento.CANCELADO || estado == EstadoDelIntento.GUARDADO

/**
 * Lo que sube como duda aunque ya tenga desenlace: lo que la máquina dijo después de que un gerente lo resolviera (Codex código r3 #1), o
 * después de un cierre que el SERVIDOR ya tiene (Codex código POS r7). El acuse sólo lo marca como entregado.
 */
fun CobroEnMaquinaEntity.subeTrasTerminar(): Boolean = estado == EstadoDelIntento.RESUELTO || (subido && terminadaAqui())

/**
 * Codex código r1 #8, r3 #1 y Codex código POS r4 #1: LA regla de qué impide cobrar otra vez la MISMA venta: un intento abierto, en duda o
 * pagado, o uno que el SERVIDOR resolvió como pagado (o sin saber cómo). Un `CANCELADO` o un `GUARDADO` de un gerente deja volver a
 * intentar sólo si su evidencia ya está allá y la resolución cubre todo lo que la máquina contó en esta PC: el eco de una resolución
 * anterior nunca suelta dinero que entró después.
 */
fun CobroEnMaquinaEntity.bloqueaLaVenta(): Boolean = when (estado) {
    EstadoDelIntento.EN_CURSO, EstadoDelIntento.ESPERANDO, EstadoDelIntento.PAGADO -> true
    EstadoDelIntento.RESUELTO -> (desenlaceDelServidor ?: Desenlace.PAGADO.name) == Desenlace.PAGADO.name || !dudaSubida ||
        creditCents > creditoDeLaResolucion()
    // Codex código POS r7: un CANCELADO o GUARDADO del que la máquina dijo otra cosa (otro cierre, más dinero) pudo quedarse con el dinero.
    else -> maquinaSeContradijo
}

/** Lo que entró según la resolución del SERVIDOR (su eco en `respuestaServidor`); sin saberlo, 0. */
private fun CobroEnMaquinaEntity.creditoDeLaResolucion(): Int =
    respuestaServidor?.let { runCatching { (JSONObject(it).opt("creditCents") as? Number)?.toInt() }.getOrNull() } ?: 0

/**
 * Codex código POS r7 y r8 (F1): LO QUE CONTÓ LA MÁQUINA de un folio. [recibido]: lo más que contó con su `Due`. [cierre]: lo recibido y el
 * cambio de su cierre, sólo si es confiable (hay cierre físico y la máquina no se contradijo: otro cierre, uno imposible o de otro total, o
 * más dinero del registrado); null = no consta.
 */
data class LoQueContoLaMaquina(val recibido: Int, val cierre: Pair<Int, Int>?)

/**
 * Codex código POS r8 (F1): la ÚNICA lectura de lo que contó la máquina, de las columnas físicas y de nada más —nunca del diario (que se
 * recorta), de un cierre que el motor descartó ni de lo que tecleó un gerente—. La usan TODOS los que dicen «lo que contó la máquina»: el papel
 * ([porRevisarEnElPanel] y el ticket), la subida (el cierre, la duda y el eco) y el motor al registrar un cierre. Su `cierre == null` es
 * [CobroEnMaquinaSql.SIN_CIERRE_CONFIABLE] en SQL.
 */
fun CobroEnMaquinaEntity.loQueContoLaMaquina(): LoQueContoLaMaquina = LoQueContoLaMaquina(
    recibido = creditoFisicoCents,
    cierre = cambioFisicoCents?.takeIf { !maquinaSeContradijo && cierreFisico != null }?.let { creditoFisicoCents to it },
)

/**
 * Codex código POS r5 #3, r6 y r7: el papel no afirma una deuda que lo físico no respalda. «Revisar en el panel» (ni comprobante ni «Te
 * debemos») si lo físico no consta ([loQueContoLaMaquina]) o no coincide con lo registrado —el cierre que aceptó el motor, o la
 * resolución del gerente—: otro desenlace, otro recibido u otro cambio. Sólo lee columnas que no se recortan.
 */
fun CobroEnMaquinaEntity.porRevisarEnElPanel(): Boolean {
    val (recibido, cambio) = loQueContoLaMaquina().cierre ?: return true
    val resuelto = estado == EstadoDelIntento.RESUELTO
    val credito = if (resuelto) creditoDeLaResolucion() else creditCents
    val d = desenlace
    val mismoDesenlace = if (cierreFisico == Cierre.PAGADO.name) d == Desenlace.PAGADO else d != null && d != Desenlace.PAGADO
    return !mismoDesenlace || recibido != credito || cambio != changeDispensedCents
}

/**
 * Codex código POS r4 #5: el SERVIDOR guardó esta duda SÓLO como incidencia (422 `CASH_MACHINE_CLOSE_IMPOSSIBLE` con su `incidentId`):
 * no abrió duda que consultar. Ya es suya: no se consulta ni frena salir, salvo que llegue evidencia nueva (que sí vuelve a subir).
 */
fun CobroEnMaquinaEntity.esIncidenciaSinDuda(): Boolean =
    conflicto != null && respuestaServidor?.let { runCatching { JSONObject(it).optString("code") }.getOrNull() } == CODIGO_IMPOSIBLE

internal const val CODIGO_IMPOSIBLE = "CASH_MACHINE_CLOSE_IMPOSSIBLE"

/** Codex código r2 H8: el SERVIDOR atribuye este folio a OTRA PC (409 `OTRA_PC` con su incidencia). */
fun CobroEnMaquinaEntity.esDeOtraPc(): Boolean = conflicto != null && motivoDelConflicto(respuestaServidor) == MOTIVO_OTRA_PC

internal const val MOTIVO_OTRA_PC = "OTRA_PC"

/** El `motivo` del 409 de conflicto guardado en la fila. */
internal fun motivoDelConflicto(respuesta: String?): String? =
    respuesta?.let { runCatching { JSONObject(it).optJSONObject("details")?.optString("motivo") }.getOrNull() }

/** El papel de un folio (columna `ticketImpreso`). */
object TicketDelCobro {
    const val PENDIENTE = 0
    const val IMPRESO = 1
    /** Reclamado por quien lo imprime; si la PC murió aquí, el papel pudo salir: se ofrece como copia. */
    const val IMPRIMIENDO = 2
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

/**
 * Codex código r2, bloque A: el diario de UN folio. Cada mensaje físico va una sola vez: lo que se compara es el mensaje tal cual lo
 * mandó la máquina, sin la hora en que llegó ni la conexión (H13: APS repite el mismo `status` y eso no es evidencia nueva ni sube la
 * versión de la duda). Se guarda con su generación (`g<n> <json>`) para quien lo revise. Tope de 8 KB al TEXTO: se van primero los más
 * viejos, y sólo si ya llegaron al SERVIDOR (Codex código POS r4 #15). Codex código r3 #13: la comparación es contra TODA la historia del
 * folio, por las huellas, que no se recortan.
 */
internal object DiarioDelFolio {
    const val MAXIMO = 8_192

    /** El diario y las huellas con un mensaje nuevo. */
    data class Anotado(val diario: String, val huellas: String)

    /** SHA-256 del mensaje físico, 16 hex (64 bits): sobra para distinguir los mensajes de un folio. */
    fun huella(fisico: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(fisico.toByteArray(Charsets.UTF_8))
            .take(8).joinToString("") { "%02x".format(it) }

    /**
     * El diario con [crudo] agregado, o null si ese mensaje ya llegó alguna vez a este folio (aunque su texto ya no quepa). [entregado]: el
     * SERVIDOR ya tiene todo [diario]; sin eso no se recorta nada (Codex código POS r4 #15). ponytail: mientras no se entrega, el diario
     * crece sin tope; lo acota que sólo entra cada mensaje físico DISTINTO de un folio (un billete o moneda más). Si un folio llegara a
     * pesar de más, partirlo por lo entregado aquí.
     */
    fun agregar(diario: String, huellas: String, generacion: Int, crudo: String, entregado: Boolean): Anotado? {
        val fisico = crudo.replace('\r', ' ').replace('\n', ' ')
        val lineas = diario.lines().filter { it.isNotBlank() }
        // Un folio de antes de la v23 no tiene huellas: se siembran con lo que su texto todavía guarda.
        val conocidas = huellas.ifEmpty { lineas.joinToString(" ") { huella(it.substringAfter(' ')) } }
        val nueva = huella(fisico)
        if (nueva in conocidas.split(' ') || lineas.any { it.substringAfter(' ') == fisico }) return null
        val nuevas = ArrayDeque(lineas + "g$generacion $fisico")
        while (entregado && nuevas.size > 1 && nuevas.sumOf { it.length + 1 } > MAXIMO) nuevas.removeFirst()
        return Anotado(nuevas.joinToString("\n"), if (conocidas.isEmpty()) nueva else "$conocidas $nueva")
    }
}
