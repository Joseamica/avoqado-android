package com.avoqado.pos.maquina

import kotlinx.coroutines.flow.map
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * El DAO de la máquina sobre SQLite en memoria (patrón `KdsLanSqlTest`): cada consulta corre la MISMA constante de
 * [CobroEnMaquinaSql] que Room compila, y `abrirIntento` es el `@Transaction` de la interfaz, heredado sin tocar. Así las
 * pruebas del cobro ejercen el SQL de producción, no un DAO inventado.
 */
internal open class CobroEnMaquinaSobreSqlite : CobroEnMaquinaDao, AutoCloseable {

    val db: Connection = DriverManager.getConnection("jdbc:sqlite::memory:").apply {
        createStatement().use { it.executeUpdate(CobroEnMaquinaSql.CREAR_TABLA) }
    }

    /** Para las pruebas de la carrera: corre justo después de guardar el modo del `cancel` y antes de mandarlo. */
    var alPedirCancel: () -> Unit = {}

    /** Codex código r2 H11: corre justo después de guardar un intento (la PC se pausa antes del `paycash`). */
    var alInsertar: () -> Unit = {}

    /** Codex código r3 #12: corre justo después de escribir un cierre (p. ej. la subida guarda su acuse antes del primer papel). */
    var alCerrar: (String) -> Unit = {}

    /** Codex código POS r4 #12: corre antes de cada lectura por folio (una lectura de disco que tarda). */
    var alLeer: suspend () -> Unit = {}

    override fun close() = db.close()

    private fun preparar(sql: String, vararg valores: Pair<String, Any?>): PreparedStatement {
        val mapa = valores.toMap()
        val args = mutableListOf<Any?>()
        val expandido = Regex(":([A-Za-z]+)").replace(sql) { args.add(mapa.getValue(it.groupValues[1])); "?" }
        return db.prepareStatement(expandido).apply { args.forEachIndexed { i, v -> setObject(i + 1, v) } }
    }

    /** Sube con cada escritura: las consultas en vivo (`observar`, los tickets sin imprimir) vuelven a leer, como las de Room. */
    private val cambios = kotlinx.coroutines.flow.MutableStateFlow(0)

    private fun actualizar(sql: String, vararg valores: Pair<String, Any?>): Int =
        preparar(sql, *valores).use { it.executeUpdate() }.also { cambios.value++ }

    private fun filas(sql: String, vararg valores: Pair<String, Any?>): List<CobroEnMaquinaEntity> = preparar(sql, *valores).use { st ->
        val rs = st.executeQuery()
        generateSequence { if (rs.next()) leer(rs) else null }.toList()
    }

    private fun ResultSet.intONulo(c: String): Int? = getInt(c).takeUnless { wasNull() }
    private fun ResultSet.largoONulo(c: String): Long? = getLong(c).takeUnless { wasNull() }

    private fun leer(rs: ResultSet) = CobroEnMaquinaEntity(
        referenceCC = rs.getString("referenceCC"), venueId = rs.getString("venueId"), llaveDeVenta = rs.getString("llaveDeVenta"),
        pedidoJson = rs.getString("pedidoJson"), saleCents = rs.getInt("saleCents"), tipCents = rs.getInt("tipCents"),
        dueCents = rs.getInt("dueCents"), saleShiftId = rs.getString("saleShiftId"), quoteId = rs.getString("quoteId"),
        creadoEn = rs.getLong("creadoEn"), estado = rs.getString("estado"), generacionPropia = rs.intONulo("generacionPropia"),
        ultimoCreditoEn = rs.largoONulo("ultimoCreditoEn"), creditCents = rs.getInt("creditCents"),
        changeDispensedCents = rs.getInt("changeDispensedCents"), cancelPedido = rs.getString("cancelPedido"),
        cancelPedidoEn = rs.largoONulo("cancelPedidoEn"), cierreEn = rs.largoONulo("cierreEn"),
        cierreConocido = rs.getInt("cierreConocido") == 1, resueltoPor = rs.getString("resueltoPor"),
        subido = rs.getInt("subido") == 1, dudaSubida = rs.getInt("dudaSubida") == 1, conflicto = rs.getString("conflicto"),
        ticketImpreso = rs.getInt("ticketImpreso"), mensajesCrudos = rs.getString("mensajesCrudos"),
        respuestaServidor = rs.getString("respuestaServidor"), bindingId = rs.getString("bindingId"),
        renglonesJson = rs.getString("renglonesJson"), desenlaceDelServidor = rs.getString("desenlaceDelServidor"),
        cierreTardio = rs.getString("cierreTardio"), aDeberCents = rs.getInt("aDeberCents"),
        versionDeLaDuda = rs.intONulo("versionDeLaDuda"), huellasDelDiario = rs.getString("huellasDelDiario"),
        comandaJson = rs.getString("comandaJson"), creditoFisicoCents = rs.getInt("creditoFisicoCents"),
        cierreFisico = rs.getString("cierreFisico"), cambioFisicoCents = rs.intONulo("cambioFisicoCents"),
        maquinaSeContradijo = rs.getInt("maquinaSeContradijo") == 1,
    )

    /** Lectura directa, sin suspender (para revisar el disco desde un `enviar` falso). */
    fun fila(referenceCC: String): CobroEnMaquinaEntity? = filas(CobroEnMaquinaSql.POR_REFERENCIA, "referenceCC" to referenceCC).singleOrNull()

    fun todas(): List<CobroEnMaquinaEntity> = filas("SELECT * FROM cobros_en_maquina ORDER BY creadoEn, referenceCC")

    override suspend fun porReferencia(referenceCC: String): CobroEnMaquinaEntity? {
        alLeer()
        return fila(referenceCC)
    }

    /** En vivo como Room: vuelve a leer con cada escritura (sin reloj que sondee: con `runTest` un sondeo infinito no termina nunca). */
    override fun observar(referenceCC: String) = cambios.map { fila(referenceCC) }

    override suspend fun intentoDeLaVenta(llaveDeVenta: String) =
        filas(CobroEnMaquinaSql.INTENTO_DE_LA_VENTA, "llaveDeVenta" to llaveDeVenta).singleOrNull()

    override suspend fun marcarEvidenciaEntregada(referenceCC: String, crudos: String, creditoEnviado: Int) = actualizar(
        CobroEnMaquinaSql.MARCAR_EVIDENCIA_ENTREGADA, "referenceCC" to referenceCC, "crudos" to crudos, "creditoEnviado" to creditoEnviado,
    )

    override suspend fun marcarDudaEnConflicto(referenceCC: String, conflicto: String, respuesta: String, crudos: String, creditoEnviado: Int) =
        actualizar(
            CobroEnMaquinaSql.MARCAR_DUDA_EN_CONFLICTO, "referenceCC" to referenceCC, "conflicto" to conflicto, "respuesta" to respuesta,
            "crudos" to crudos, "creditoEnviado" to creditoEnviado,
        )

    override suspend fun marcarDeOtraPc(referenceCC: String, conflicto: String, respuesta: String, crudos: String, creditoEnviado: Int) = actualizar(
        CobroEnMaquinaSql.MARCAR_DE_OTRA_PC, "referenceCC" to referenceCC, "conflicto" to conflicto, "respuesta" to respuesta,
        "crudos" to crudos, "creditoEnviado" to creditoEnviado,
    )

    /** `pendientes` es el de la interfaz (la regla en Kotlin), heredado sin tocar: corre sobre esta consulta. */
    override suspend fun puedenFrenar() = filas(CobroEnMaquinaSql.PUEDEN_FRENAR)

    /** `bloqueanLaVenta` es el de la interfaz (la regla en Kotlin, [bloqueaLaVenta]), heredado sin tocar: corre sobre esta consulta. */
    override suspend fun filasDeLaVenta(llaveDeVenta: String) = filas(CobroEnMaquinaSql.FILAS_DE_LA_VENTA, "llaveDeVenta" to llaveDeVenta)

    override suspend fun insertar(fila: CobroEnMaquinaEntity) {
        val f = fila
        val columnas = listOf(
            "referenceCC" to f.referenceCC, "venueId" to f.venueId, "llaveDeVenta" to f.llaveDeVenta, "pedidoJson" to f.pedidoJson,
            "saleCents" to f.saleCents, "tipCents" to f.tipCents, "dueCents" to f.dueCents, "saleShiftId" to f.saleShiftId,
            "quoteId" to f.quoteId, "creadoEn" to f.creadoEn, "estado" to f.estado, "generacionPropia" to f.generacionPropia,
            "ultimoCreditoEn" to f.ultimoCreditoEn, "creditCents" to f.creditCents, "changeDispensedCents" to f.changeDispensedCents,
            "cancelPedido" to f.cancelPedido, "cancelPedidoEn" to f.cancelPedidoEn, "cierreEn" to f.cierreEn,
            "cierreConocido" to if (f.cierreConocido) 1 else 0, "resueltoPor" to f.resueltoPor, "subido" to if (f.subido) 1 else 0,
            "dudaSubida" to if (f.dudaSubida) 1 else 0, "conflicto" to f.conflicto, "ticketImpreso" to f.ticketImpreso,
            "mensajesCrudos" to f.mensajesCrudos, "respuestaServidor" to f.respuestaServidor, "bindingId" to f.bindingId,
            "renglonesJson" to f.renglonesJson, "desenlaceDelServidor" to f.desenlaceDelServidor, "cierreTardio" to f.cierreTardio,
            "aDeberCents" to f.aDeberCents, "versionDeLaDuda" to f.versionDeLaDuda, "huellasDelDiario" to f.huellasDelDiario,
            "comandaJson" to f.comandaJson, "creditoFisicoCents" to f.creditoFisicoCents, "cierreFisico" to f.cierreFisico,
            "cambioFisicoCents" to f.cambioFisicoCents, "maquinaSeContradijo" to if (f.maquinaSeContradijo) 1 else 0,
        )
        val sql = "INSERT INTO cobros_en_maquina (${columnas.joinToString { it.first }}) VALUES (${columnas.joinToString { ":" + it.first }})"
        actualizar(sql, *columnas.toTypedArray())
        alInsertar()
    }

    override suspend fun borrarSinEnviar(referenceCC: String) =
        actualizar(CobroEnMaquinaSql.BORRAR_SIN_ENVIAR, "referenceCC" to referenceCC)

    override suspend fun ganarPropiedad(referenceCC: String, generacion: Int) =
        actualizar(CobroEnMaquinaSql.GANAR_PROPIEDAD, "referenceCC" to referenceCC, "generacion" to generacion)

    override suspend fun escribirDiario(referenceCC: String, anterior: String, nuevo: String, huellas: String, creditoAnterior: Int, credito: Int) =
        actualizar(
            CobroEnMaquinaSql.ESCRIBIR_DIARIO, "referenceCC" to referenceCC, "anterior" to anterior, "nuevo" to nuevo, "huellas" to huellas,
            "creditoAnterior" to creditoAnterior, "credito" to credito,
        )

    override suspend fun aDudaSinCierreConfiable(referenceCC: String?) =
        actualizar(CobroEnMaquinaSql.A_DUDA_SIN_CIERRE_CONFIABLE, "referenceCC" to referenceCC)

    override suspend fun anotarLoFisico(referenceCC: String, credito: Int, cierre: String?, cambio: Int?, imposible: Boolean) = actualizar(
        CobroEnMaquinaSql.ANOTAR_LO_FISICO, "referenceCC" to referenceCC, "credito" to credito, "cierre" to cierre, "cambio" to cambio,
        "imposible" to if (imposible) 1 else 0,
    )

    override suspend fun pedirCancel(referenceCC: String, cancelvalue: String, ahora: Long): Int =
        actualizar(CobroEnMaquinaSql.PEDIR_CANCEL, "referenceCC" to referenceCC, "cancelvalue" to cancelvalue, "ahora" to ahora)
            .also { alPedirCancel() }

    override suspend fun cerrar(
        referenceCC: String,
        estado: String,
        creditCents: Int,
        changeDispensedCents: Int,
        cierreEn: Long,
        conocido: Boolean,
        aDeber: Int,
    ) = actualizar(
        CobroEnMaquinaSql.CERRAR, "referenceCC" to referenceCC, "estado" to estado, "creditCents" to creditCents,
        "changeDispensedCents" to changeDispensedCents, "cierreEn" to cierreEn, "conocido" to if (conocido) 1 else 0,
        "aDeber" to aDeber,
    ).also { alCerrar(referenceCC) }

    override suspend fun aEsperando(referenceCC: String) = actualizar(CobroEnMaquinaSql.A_ESPERANDO, "referenceCC" to referenceCC)

    override suspend fun huerfanosAEsperando(salvo: String) = actualizar(CobroEnMaquinaSql.HUERFANOS_A_ESPERANDO, "salvo" to salvo)

    override suspend fun porSubir() = filas(CobroEnMaquinaSql.POR_SUBIR)

    override suspend fun paraSubir(venueId: String) = filas(CobroEnMaquinaSql.PARA_SUBIR, "venueId" to venueId)

    override suspend fun paraSubirElFolio(venueId: String, referenceCC: String) =
        filas(CobroEnMaquinaSql.PARA_SUBIR_EL_FOLIO, "venueId" to venueId, "referenceCC" to referenceCC).singleOrNull()

    override suspend fun esperanAlGerente(venueId: String) = filas(CobroEnMaquinaSql.ESPERAN_AL_GERENTE, "venueId" to venueId)

    override suspend fun esperaAlGerenteElFolio(venueId: String, referenceCC: String) =
        filas(CobroEnMaquinaSql.ESPERA_AL_GERENTE_EL_FOLIO, "venueId" to venueId, "referenceCC" to referenceCC).singleOrNull()

    override suspend fun marcarSubido(referenceCC: String, conflicto: String?, respuesta: String?, crudos: String, creditoEnviado: Int, aDeber: Int?) =
        actualizar(
            CobroEnMaquinaSql.MARCAR_SUBIDO, "referenceCC" to referenceCC, "conflicto" to conflicto, "respuesta" to respuesta, "aDeber" to aDeber,
            "crudos" to crudos, "creditoEnviado" to creditoEnviado,
        )

    override suspend fun anotarConflicto(referenceCC: String, conflicto: String, crudos: String, creditoEnviado: Int) = actualizar(
        CobroEnMaquinaSql.ANOTAR_CONFLICTO, "referenceCC" to referenceCC, "conflicto" to conflicto, "crudos" to crudos,
        "creditoEnviado" to creditoEnviado,
    )

    override suspend fun marcarDudaAbierta(referenceCC: String, version: Int?, crudos: String, creditoEnviado: Int) = actualizar(
        CobroEnMaquinaSql.MARCAR_DUDA_ABIERTA, "referenceCC" to referenceCC, "version" to version, "crudos" to crudos,
        "creditoEnviado" to creditoEnviado,
    )

    override suspend fun marcarDudaSubida(referenceCC: String, subida: Boolean) =
        actualizar(CobroEnMaquinaSql.MARCAR_DUDA_SUBIDA, "referenceCC" to referenceCC, "subida" to if (subida) 1 else 0)

    override suspend fun resolver(
        referenceCC: String,
        resueltoPor: String,
        creditCents: Int,
        changeDispensedCents: Int,
        conflicto: String?,
        respuesta: String?,
        desenlace: String?,
        crudos: String,
        creditoEnviado: Int,
        aDeber: Int?,
    ) = actualizar(
        CobroEnMaquinaSql.RESOLVER, "referenceCC" to referenceCC, "resueltoPor" to resueltoPor, "creditCents" to creditCents,
        "changeDispensedCents" to changeDispensedCents, "conflicto" to conflicto, "respuesta" to respuesta, "desenlace" to desenlace,
        "aDeber" to aDeber, "crudos" to crudos, "creditoEnviado" to creditoEnviado,
    )

    override suspend fun conComprobante(venueId: String, limite: Int) =
        filas(CobroEnMaquinaSql.CON_COMPROBANTE, "venueId" to venueId, "limite" to limite)

    override suspend fun contarComprobantes(venueId: String): Int =
        preparar(CobroEnMaquinaSql.CONTAR_COMPROBANTES, "venueId" to venueId).use { st -> st.executeQuery().let { it.next(); it.getInt(1) } }

    override suspend fun ticketsSinImprimir(venueId: String) = filas(CobroEnMaquinaSql.TICKETS_SIN_IMPRIMIR, "venueId" to venueId)

    override fun observarTicketsSinImprimir(venueId: String) =
        cambios.map { filas(CobroEnMaquinaSql.TICKETS_SIN_IMPRIMIR, "venueId" to venueId) }

    override suspend fun reclamarTicket(referenceCC: String) = actualizar(CobroEnMaquinaSql.RECLAMAR_TICKET, "referenceCC" to referenceCC)

    override suspend fun terminarTicket(referenceCC: String, valor: Int) =
        actualizar(CobroEnMaquinaSql.TERMINAR_TICKET, "referenceCC" to referenceCC, "valor" to valor)
}

/**
 * Codex código POS r7: la fila como la deja un `status` de cierre que dice lo mismo que su registro —las columnas físicas que escribe
 * [CobroEnMaquinaDao.anotarMensaje] antes de cerrar—. Para las pruebas que insertan a mano un cobro ya cerrado por la máquina (o resuelto con
 * lo mismo que dijo la máquina): sin ellas, el papel lo trata como «sin cierre físico» y dice «Revisar en el panel».
 */
internal fun CobroEnMaquinaEntity.conSuCierreFisico(): CobroEnMaquinaEntity {
    val resuelto = estado == EstadoDelIntento.RESUELTO
    val credito = if (resuelto) runCatching { org.json.JSONObject(respuestaServidor!!).getInt("creditCents") }.getOrDefault(creditCents) else creditCents
    val pagado = if (resuelto) desenlace == Desenlace.PAGADO else estado == EstadoDelIntento.PAGADO
    return copy(creditoFisicoCents = credito, cierreFisico = if (pagado) "PAGADO" else "CANCELADO", cambioFisicoCents = changeDispensedCents)
}

/** [CobroEnMaquinaDao.insertar] de un cobro ya cerrado, con su cierre físico ([conSuCierreFisico]). */
internal suspend fun CobroEnMaquinaDao.insertarCerrada(fila: CobroEnMaquinaEntity) = insertar(fila.conSuCierreFisico())
