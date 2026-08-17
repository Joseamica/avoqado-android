package com.avoqado.pos.cashdrawer

import com.avoqado.pos.cashdrawer.data.CashDrawerSql
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * 🔴 EL SQL QUE BORRA DINERO, EJECUTADO DE VERDAD.
 *
 * Todo lo demás de esta suite corre contra [FakeCashDrawerDao], una réplica en memoria
 * escrita a mano. Sirve —es rápida y modela la semántica hostil de SQLite a
 * propósito— pero **no es el `@Query` que corre en la tablet**. Mientras nadie ejecute
 * la cadena real, aflojar su `WHERE` sale VERDE: la réplica sigue borrando bien y la
 * consulta de verdad ya no.
 *
 * Este test cierra ese hueco. Toma la MISMA constante que Room compila
 * ([CashDrawerSql.DELETE_UNCONFIRMED_EVENTS], no una copia), expande sus parámetros
 * como lo hace Room —un `?` por elemento de cada lista— y la corre contra SQLite en
 * memoria. Lo que se prueba es la semántica del motor, que es justo lo que una réplica
 * no puede prometer:
 *
 *  - `NOT IN ()` con lista VACÍA. SQLite la acepta y la evalúa como verdadera (es una
 *    extensión suya; otros motores ni siquiera parsean la sintaxis). De eso depende
 *    que el barrido siga funcionando cuando no hay nada encolado.
 *  - `id NOT IN (…)` sobre la llave primaria, que nunca es NULL — a diferencia del
 *    `orderId NOT IN (…)` de antes, donde un NULL hacía la comparación NULA y por eso
 *    hacía falta el `orderId IS NULL OR …`. Si alguien vuelve a colgar el filtro de una
 *    columna anulable, este test es el que lo ve.
 *
 * No reemplaza al fake: lo ancla. Todo lo que aquí se afirma tiene su gemelo en
 * [CashDrawerPendingCashSaleTest], que asserta el número que ve el cajero.
 */
class CashDrawerDeleteSqlTest {

    private lateinit var db: Connection

    @Before
    fun abrirBase() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use {
            // Sólo las columnas que el DELETE mira. `orderId` sigue aquí a propósito:
            // es la que era anulable y llevaba el filtro viejo.
            it.executeUpdate(
                """
                CREATE TABLE cash_drawer_events (
                  id TEXT NOT NULL PRIMARY KEY,
                  sessionId TEXT NOT NULL,
                  type TEXT NOT NULL,
                  orderId TEXT,
                  amountCents INTEGER NOT NULL
                )
                """.trimIndent(),
            )
        }
    }

    @After
    fun cerrarBase() = db.close()

    // MARK: - Los tres casos que el fake no puede prometer

    /** Sin nada protegido ni confirmado: las copias locales se van, como siempre. */
    @Test
    fun `con las listas vacias el barrido borra las copias locales`() {
        insertar("local-venta", "CASH_SALE", orderId = null)
        insertar("local-open", "OPEN", orderId = null)
        insertar("pay-out", "PAY_OUT", orderId = null)

        borrarNoConfirmados(confirmedIds = listOf("srv-1"), protectedIds = emptyList())

        assertEquals(
            "SQLite no evaluó `id NOT IN ()` como verdadero y el barrido dejó de limpiar",
            listOf("pay-out"),
            idsVivos(),
        )
    }

    /**
     * 🔴 EL CASO DEL DEFECTO: una venta con `orderId` NULO que sí está protegida. Con
     * el filtro viejo —colgado de `orderId`— no había forma de salvarla; con el nuevo
     * —colgado del `id`, que nunca es NULL— sobrevive.
     */
    @Test
    fun `una venta protegida sobrevive aunque su orderId sea NULO`() {
        insertar("local-mostrador", "CASH_SALE", orderId = null)
        insertar("local-otra", "CASH_SALE", orderId = null)

        borrarNoConfirmados(confirmedIds = listOf("srv-1"), protectedIds = listOf("local-mostrador"))

        assertEquals(
            "el SQL borró una venta protegida cuya orden es nula",
            listOf("local-mostrador"),
            idsVivos(),
        )
    }

    /** Lo confirmado por el server tampoco se toca, con o sin protegidos. */
    @Test
    fun `lo confirmado por el server nunca se borra`() {
        insertar("srv-venta", "CASH_SALE", orderId = "order-9")
        insertar("local-venta", "CASH_SALE", orderId = "order-9")

        borrarNoConfirmados(confirmedIds = listOf("srv-venta"), protectedIds = emptyList())

        assertEquals(
            "el barrido se llevó la fila que el server acababa de confirmar",
            listOf("srv-venta"),
            idsVivos(),
        )
    }

    // MARK: - Andamio

    private fun insertar(id: String, type: String, orderId: String?, amountCents: Int = 30_000) {
        db.prepareStatement(
            "INSERT INTO cash_drawer_events (id, sessionId, type, orderId, amountCents) VALUES (?,?,?,?,?)",
        ).use {
            it.setString(1, id)
            it.setString(2, "srv-1")
            it.setString(3, type)
            it.setString(4, orderId)
            it.setInt(5, amountCents)
            it.executeUpdate()
        }
    }

    private fun idsVivos(): List<String> = db.createStatement().use { st ->
        st.executeQuery("SELECT id FROM cash_drawer_events ORDER BY id").use { rs ->
            generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
        }
    }

    /**
     * Corre la constante REAL, expandiendo sus parámetros igual que Room: cada lista se
     * vuelve tantos `?` como elementos tenga (cero incluido), en el orden en que
     * aparecen en la cadena.
     */
    private fun borrarNoConfirmados(
        confirmedIds: List<String>,
        protectedIds: List<String>,
        sessionId: String = "srv-1",
        serverOwnedTypes: List<String> = listOf("OPEN", "CASH_SALE"),
    ) {
        val sql = CashDrawerSql.DELETE_UNCONFIRMED_EVENTS
            .replace(":sessionId", "?")
            .replace(":serverOwnedTypes", marcadores(serverOwnedTypes))
            .replace(":confirmedIds", marcadores(confirmedIds))
            .replace(":protectedIds", marcadores(protectedIds))
        db.prepareStatement(sql).use { st ->
            var i = 1
            st.setString(i++, sessionId)
            (serverOwnedTypes + confirmedIds + protectedIds).forEach { st.setString(i++, it) }
            st.executeUpdate()
        }
    }

    private fun marcadores(valores: List<String>) = valores.joinToString(",") { "?" }
}
