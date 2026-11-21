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

    override fun close() = db.close()

    private fun preparar(sql: String, vararg valores: Pair<String, Any?>): PreparedStatement {
        val mapa = valores.toMap()
        val args = mutableListOf<Any?>()
        val expandido = Regex(":([A-Za-z]+)").replace(sql) { args.add(mapa.getValue(it.groupValues[1])); "?" }
        return db.prepareStatement(expandido).apply { args.forEachIndexed { i, v -> setObject(i + 1, v) } }
    }

    /** Sube con cada escritura: las consultas en vivo (`observarEnEspera`) vuelven a leer, como las de Room. */
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
        ticketImpreso = rs.getInt("ticketImpreso") == 1, mensajesCrudos = rs.getString("mensajesCrudos"),
        respuestaServidor = rs.getString("respuestaServidor"), bindingId = rs.getString("bindingId"),
        renglonesJson = rs.getString("renglonesJson"), desenlaceDelServidor = rs.getString("desenlaceDelServidor"),
        cierreTardio = rs.getString("cierreTardio"), aDeberCents = rs.getInt("aDeberCents"),
        versionDeLaDuda = rs.intONulo("versionDeLaDuda"),
    )

    /** Lectura directa, sin suspender (para revisar el disco desde un `enviar` falso). */
    fun fila(referenceCC: String): CobroEnMaquinaEntity? = filas(CobroEnMaquinaSql.POR_REFERENCIA, "referenceCC" to referenceCC).singleOrNull()

    fun todas(): List<CobroEnMaquinaEntity> = filas("SELECT * FROM cobros_en_maquina ORDER BY creadoEn, referenceCC")

    override suspend fun porReferencia(referenceCC: String) = fila(referenceCC)

    /** Una sola lectura: sin un reloj que sondee (con `runTest` un sondeo infinito no termina nunca). */
    override fun observar(referenceCC: String) = kotlinx.coroutines.flow.flowOf(fila(referenceCC))

    override suspend fun intentoDeLaVenta(llaveDeVenta: String) =
        filas(CobroEnMaquinaSql.INTENTO_DE_LA_VENTA, "llaveDeVenta" to llaveDeVenta).singleOrNull()

    override suspend fun enEspera(venueId: String) = filas(CobroEnMaquinaSql.EN_ESPERA, "venueId" to venueId)

    override fun observarEnEspera(venueId: String) = cambios.map { filas(CobroEnMaquinaSql.EN_ESPERA, "venueId" to venueId) }

    override suspend fun marcarDudaEnConflicto(referenceCC: String, conflicto: String, respuesta: String) = actualizar(
        CobroEnMaquinaSql.MARCAR_DUDA_EN_CONFLICTO, "referenceCC" to referenceCC, "conflicto" to conflicto, "respuesta" to respuesta,
    )

    override suspend fun bloqueanLaVenta(llaveDeVenta: String): Int =
        preparar(CobroEnMaquinaSql.BLOQUEAN_LA_VENTA, "llaveDeVenta" to llaveDeVenta).use { st ->
            st.executeQuery().let { it.next(); it.getInt(1) }
        }

    override suspend fun pendientes(venueId: String?): Int =
        preparar(CobroEnMaquinaSql.PENDIENTES, "venueId" to venueId).use { st -> st.executeQuery().let { it.next(); it.getInt(1) } }

    override suspend fun insertar(fila: CobroEnMaquinaEntity) {
        val f = fila
        val columnas = listOf(
            "referenceCC" to f.referenceCC, "venueId" to f.venueId, "llaveDeVenta" to f.llaveDeVenta, "pedidoJson" to f.pedidoJson,
            "saleCents" to f.saleCents, "tipCents" to f.tipCents, "dueCents" to f.dueCents, "saleShiftId" to f.saleShiftId,
            "quoteId" to f.quoteId, "creadoEn" to f.creadoEn, "estado" to f.estado, "generacionPropia" to f.generacionPropia,
            "ultimoCreditoEn" to f.ultimoCreditoEn, "creditCents" to f.creditCents, "changeDispensedCents" to f.changeDispensedCents,
            "cancelPedido" to f.cancelPedido, "cancelPedidoEn" to f.cancelPedidoEn, "cierreEn" to f.cierreEn,
            "cierreConocido" to if (f.cierreConocido) 1 else 0, "resueltoPor" to f.resueltoPor, "subido" to if (f.subido) 1 else 0,
            "dudaSubida" to if (f.dudaSubida) 1 else 0, "conflicto" to f.conflicto, "ticketImpreso" to if (f.ticketImpreso) 1 else 0,
            "mensajesCrudos" to f.mensajesCrudos, "respuestaServidor" to f.respuestaServidor, "bindingId" to f.bindingId,
            "renglonesJson" to f.renglonesJson, "desenlaceDelServidor" to f.desenlaceDelServidor, "cierreTardio" to f.cierreTardio,
            "aDeberCents" to f.aDeberCents, "versionDeLaDuda" to f.versionDeLaDuda,
        )
        val sql = "INSERT INTO cobros_en_maquina (${columnas.joinToString { it.first }}) VALUES (${columnas.joinToString { ":" + it.first }})"
        actualizar(sql, *columnas.toTypedArray())
    }

    override suspend fun borrarSinEnviar(referenceCC: String) =
        actualizar(CobroEnMaquinaSql.BORRAR_SIN_ENVIAR, "referenceCC" to referenceCC)

    override suspend fun ganarPropiedad(referenceCC: String, generacion: Int, crudos: String) =
        actualizar(CobroEnMaquinaSql.GANAR_PROPIEDAD, "referenceCC" to referenceCC, "generacion" to generacion, "crudos" to crudos)

    override suspend fun anotarCredito(referenceCC: String, creditCents: Int, ahora: Long, crudos: String) = actualizar(
        CobroEnMaquinaSql.ANOTAR_CREDITO, "referenceCC" to referenceCC, "creditCents" to creditCents, "ahora" to ahora, "crudos" to crudos,
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
        crudos: String,
        conocido: Boolean,
        aDeber: Int,
    ) = actualizar(
        CobroEnMaquinaSql.CERRAR, "referenceCC" to referenceCC, "estado" to estado, "creditCents" to creditCents,
        "changeDispensedCents" to changeDispensedCents, "cierreEn" to cierreEn, "crudos" to crudos, "conocido" to if (conocido) 1 else 0,
        "aDeber" to aDeber,
    )

    override suspend fun aEsperando(referenceCC: String, crudos: String) =
        actualizar(CobroEnMaquinaSql.A_ESPERANDO, "referenceCC" to referenceCC, "crudos" to crudos)

    override suspend fun huerfanosAEsperando(salvo: String) = actualizar(CobroEnMaquinaSql.HUERFANOS_A_ESPERANDO, "salvo" to salvo)

    override suspend fun anotarEvidencia(referenceCC: String, crudos: String) =
        actualizar(CobroEnMaquinaSql.ANOTAR_EVIDENCIA, "referenceCC" to referenceCC, "crudos" to crudos)

    override suspend fun anotarCierreTardio(
        referenceCC: String,
        desenlace: String,
        creditCents: Int,
        changeDispensedCents: Int,
        cierreEn: Long,
        conocido: Boolean,
        crudos: String,
    ) = actualizar(
        CobroEnMaquinaSql.ANOTAR_CIERRE_TARDIO, "referenceCC" to referenceCC, "desenlace" to desenlace, "creditCents" to creditCents,
        "changeDispensedCents" to changeDispensedCents, "cierreEn" to cierreEn, "conocido" to if (conocido) 1 else 0, "crudos" to crudos,
    )

    override suspend fun tardiosParaSubir(venueId: String) = filas(CobroEnMaquinaSql.TARDIOS_PARA_SUBIR, "venueId" to venueId)

    override suspend fun marcarTardioSubido(referenceCC: String, conflicto: String?, respuesta: String?) =
        actualizar(CobroEnMaquinaSql.MARCAR_TARDIO_SUBIDO, "referenceCC" to referenceCC, "conflicto" to conflicto, "respuesta" to respuesta)

    override suspend fun porSubir() = filas(CobroEnMaquinaSql.POR_SUBIR)

    override suspend fun paraSubir(venueId: String) = filas(CobroEnMaquinaSql.PARA_SUBIR, "venueId" to venueId)

    override suspend fun esperanAlGerente(venueId: String) = filas(CobroEnMaquinaSql.ESPERAN_AL_GERENTE, "venueId" to venueId)

    override suspend fun marcarSubido(referenceCC: String, conflicto: String?, respuesta: String?, aDeber: Int?) = actualizar(
        CobroEnMaquinaSql.MARCAR_SUBIDO, "referenceCC" to referenceCC, "conflicto" to conflicto, "respuesta" to respuesta, "aDeber" to aDeber,
    )

    override suspend fun anotarConflicto(referenceCC: String, conflicto: String) =
        actualizar(CobroEnMaquinaSql.ANOTAR_CONFLICTO, "referenceCC" to referenceCC, "conflicto" to conflicto)

    override suspend fun marcarDudaAbierta(referenceCC: String, version: Int?) =
        actualizar(CobroEnMaquinaSql.MARCAR_DUDA_ABIERTA, "referenceCC" to referenceCC, "version" to version)

    override suspend fun anotarVersionDeLaDuda(referenceCC: String, version: Int) =
        actualizar(CobroEnMaquinaSql.ANOTAR_VERSION_DE_LA_DUDA, "referenceCC" to referenceCC, "version" to version)

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
        aDeber: Int?,
    ) = actualizar(
        CobroEnMaquinaSql.RESOLVER, "referenceCC" to referenceCC, "resueltoPor" to resueltoPor, "creditCents" to creditCents,
        "changeDispensedCents" to changeDispensedCents, "conflicto" to conflicto, "respuesta" to respuesta, "desenlace" to desenlace,
        "aDeber" to aDeber,
    )

    override suspend fun conComprobante(venueId: String, limite: Int) =
        filas(CobroEnMaquinaSql.CON_COMPROBANTE, "venueId" to venueId, "limite" to limite)

    override suspend fun contarComprobantes(venueId: String): Int =
        preparar(CobroEnMaquinaSql.CONTAR_COMPROBANTES, "venueId" to venueId).use { st -> st.executeQuery().let { it.next(); it.getInt(1) } }

    override suspend fun ticketsSinImprimir(venueId: String) = filas(CobroEnMaquinaSql.TICKETS_SIN_IMPRIMIR, "venueId" to venueId)

    override fun observarTicketsSinImprimir(venueId: String) =
        cambios.map { filas(CobroEnMaquinaSql.TICKETS_SIN_IMPRIMIR, "venueId" to venueId) }

    override suspend fun marcarTicketImpreso(referenceCC: String) =
        actualizar(CobroEnMaquinaSql.MARCAR_TICKET_IMPRESO, "referenceCC" to referenceCC)
}
