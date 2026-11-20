package com.avoqado.pos.maquina

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

    private fun actualizar(sql: String, vararg valores: Pair<String, Any?>): Int = preparar(sql, *valores).use { it.executeUpdate() }

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
    )

    /** Lectura directa, sin suspender (para revisar el disco desde un `enviar` falso). */
    fun fila(referenceCC: String): CobroEnMaquinaEntity? = filas(CobroEnMaquinaSql.POR_REFERENCIA, "referenceCC" to referenceCC).singleOrNull()

    fun todas(): List<CobroEnMaquinaEntity> = filas("SELECT * FROM cobros_en_maquina ORDER BY creadoEn, referenceCC")

    override suspend fun porReferencia(referenceCC: String) = fila(referenceCC)

    override suspend fun bloqueanLaVenta(llaveDeVenta: String): Int =
        preparar(CobroEnMaquinaSql.BLOQUEAN_LA_VENTA, "llaveDeVenta" to llaveDeVenta).use { st ->
            st.executeQuery().let { it.next(); it.getInt(1) }
        }

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
            "mensajesCrudos" to f.mensajesCrudos,
        )
        val sql = "INSERT INTO cobros_en_maquina (${columnas.joinToString { it.first }}) VALUES (${columnas.joinToString { ":" + it.first }})"
        actualizar(sql, *columnas.toTypedArray())
    }

    override suspend fun borrarSinEnviar(referenceCC: String) =
        actualizar(CobroEnMaquinaSql.BORRAR_SIN_ENVIAR, "referenceCC" to referenceCC)

    override suspend fun ganarPropiedad(referenceCC: String, generacion: Int, crudos: String) =
        actualizar(CobroEnMaquinaSql.GANAR_PROPIEDAD, "referenceCC" to referenceCC, "generacion" to generacion, "crudos" to crudos)

    override suspend fun anotarCredito(referenceCC: String, creditCents: Int, ahora: Long) =
        actualizar(CobroEnMaquinaSql.ANOTAR_CREDITO, "referenceCC" to referenceCC, "creditCents" to creditCents, "ahora" to ahora)

    override suspend fun pedirCancel(referenceCC: String, cancelvalue: String, ahora: Long): Int =
        actualizar(CobroEnMaquinaSql.PEDIR_CANCEL, "referenceCC" to referenceCC, "cancelvalue" to cancelvalue, "ahora" to ahora)
            .also { alPedirCancel() }

    override suspend fun cerrar(referenceCC: String, estado: String, creditCents: Int, changeDispensedCents: Int, cierreEn: Long, crudos: String) =
        actualizar(
            CobroEnMaquinaSql.CERRAR, "referenceCC" to referenceCC, "estado" to estado, "creditCents" to creditCents,
            "changeDispensedCents" to changeDispensedCents, "cierreEn" to cierreEn, "crudos" to crudos,
        )

    override suspend fun aEsperando(referenceCC: String, crudos: String) =
        actualizar(CobroEnMaquinaSql.A_ESPERANDO, "referenceCC" to referenceCC, "crudos" to crudos)

    override suspend fun huerfanosAEsperando(salvo: String) = actualizar(CobroEnMaquinaSql.HUERFANOS_A_ESPERANDO, "salvo" to salvo)

    override suspend fun anotarEvidencia(referenceCC: String, crudos: String) =
        actualizar(CobroEnMaquinaSql.ANOTAR_EVIDENCIA, "referenceCC" to referenceCC, "crudos" to crudos)

    override suspend fun porSubir() = filas(CobroEnMaquinaSql.POR_SUBIR)
}
