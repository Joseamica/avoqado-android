package com.avoqado.pos.cashdrawer.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Update
import androidx.room.Query
import com.avoqado.pos.cashdrawer.data.model.CashDrawerEventEntity
import com.avoqado.pos.cashdrawer.data.model.CashDrawerSessionEntity

@Dao
interface CashDrawerDao {

    // MARK: - Sessions

    /**
     * La caja abierta del local.
     *
     * 🔴 El `ORDER BY` no es adorno. Con `LIMIT 1` a secas, QUÉ CAJA está abierta lo
     * decidía SQLite: devolvía el rowid más bajo, que es un detalle de
     * implementación —un `VACUUM` o un índice nuevo lo cambian sin avisar— y que
     * además favorecía sistemáticamente a la fila provisional del dispositivo por
     * encima de la confirmada por el server.
     *
     * Criterio: la más RECIENTE por `openedAt`, con `id` como desempate para que el
     * resultado no dependa del azar ni cuando dos filas comparten instante. Una caja
     * vieja ganando pegaría las ventas de hoy al fondo de ayer. Es el mismo orden
     * que ya usa iOS (`CashDrawerStore`, `.order(Column("openedAt").desc)`).
     *
     * Cinturón y tirantes: `adoptServerSession` ya evita que existan dos filas
     * abiertas; esto sostiene el caso en que alguna aparezca igual.
     */
    @Query(
        "SELECT * FROM cash_drawer_sessions WHERE venueId = :venueId AND status = 'OPEN' " +
            "ORDER BY openedAt DESC, id DESC LIMIT 1",
    )
    suspend fun getOpenSession(venueId: String): CashDrawerSessionEntity?

    /**
     * TODAS las cajas abiertas del local — normalmente una. Existe para la
     * reconciliación: cuando el server dice cuál es la suya, cualquier otra fila
     * abierta es una provisional que hay que promover (ver
     * `CashDrawerRepository.adoptServerSession`).
     */
    @Query("SELECT * FROM cash_drawer_sessions WHERE venueId = :venueId AND status = 'OPEN'")
    suspend fun getOpenSessions(venueId: String): List<CashDrawerSessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: CashDrawerSessionEntity)

    @Update
    suspend fun updateSession(session: CashDrawerSessionEntity)

    @Query("DELETE FROM cash_drawer_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: String)

    @Query("SELECT * FROM cash_drawer_sessions WHERE venueId = :venueId AND status = 'CLOSED' ORDER BY closedAt DESC")
    suspend fun getClosedSessions(venueId: String): List<CashDrawerSessionEntity>

    // MARK: - Events

    @Query("SELECT * FROM cash_drawer_events WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun getSessionEvents(sessionId: String): List<CashDrawerEventEntity>

    @Query("SELECT * FROM cash_drawer_events WHERE id = :eventId")
    suspend fun getEvent(eventId: String): CashDrawerEventEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: CashDrawerEventEntity)

    @Query("DELETE FROM cash_drawer_events WHERE id = :eventId")
    suspend fun deleteEvent(eventId: String)

    /**
     * Los eventos se mudan con su sesión cuando ésta adopta el id del server. Sin
     * esto la promoción dejaría el dinero registrado contra un id que ya no existe
     * — que es exactamente el bug que la promoción viene a arreglar.
     *
     * 🔴 `sinceMillis` ACOTA la mudanza a la ventana de la caja del server, y no es
     * un adorno: mudar TODO se llevaba también los movimientos de un turno anterior
     * que este aparato nunca vio cerrar. El retiro a mano de ayer aterrizaba en la
     * caja de hoy y le inventaba al cajero un sobrante del tamaño exacto de ese
     * retiro. La cota es `openedAt` de la caja del server — la MISMA ventana con la
     * que el server calcula su esperado (`calculateExpectedAmount` suma los eventos
     * de la sesión, y un evento anterior a su apertura no puede estar colgado de
     * ella), así que cliente y server no pueden divergir por construcción.
     */
    @Query(
        "UPDATE cash_drawer_events SET sessionId = :toSessionId " +
            "WHERE sessionId = :fromSessionId AND createdAt >= :sinceMillis",
    )
    suspend fun repointEventsFrom(fromSessionId: String, toSessionId: String, sinceMillis: Long)

    /**
     * Borra las copias locales de los eventos que el SERVER escribe por su cuenta
     * (`OPEN` al abrir, `CASH_SALE` al cobrar) y que ya llegaron confirmadas con su
     * id real. Sin esto, la copia local y la confirmada conviven bajo la misma
     * sesión y la venta se cuenta DOS VECES.
     *
     * 🔴 Sólo esos tipos. `PAY_IN`/`PAY_OUT` los escribe el cliente: uno que el
     * server todavía no conoce (registrado sin red) tiene que sobrevivir, o el
     * cajero cierra con un faltante que sí existe.
     *
     * 🔴 `pendingOrderIds` son las órdenes cuyo cobro SIGUE EN LA COLA — la señal la
     * da la cola, no una corazonada sobre la antigüedad de la fila. Una venta cobrada
     * sin red todavía no puede venir confirmada, así que borrarla le desaparecía al
     * cajero dinero que sí está en el cajón, justo al abrir la pantalla para cerrar su
     * turno. Mientras el cobro esté encolado el gemelo del server no existe por
     * construcción, así que conservar la copia no duplica nada; en cuanto se
     * reproduce, sale de esta lista y se borra como siempre. Ver `PendingCashSales`.
     *
     * Una fila con `orderId` nulo (la apertura provisional, o una venta de mostrador
     * cobrada antes de que existiera la orden) no tiene con qué emparejarse y se
     * comporta como hasta hoy.
     */
    @Query(
        "DELETE FROM cash_drawer_events WHERE sessionId = :sessionId " +
            "AND type IN (:serverOwnedTypes) AND id NOT IN (:confirmedIds) " +
            "AND (orderId IS NULL OR orderId NOT IN (:pendingOrderIds))",
    )
    suspend fun deleteUnconfirmedEvents(
        sessionId: String,
        serverOwnedTypes: List<String>,
        confirmedIds: List<String>,
        pendingOrderIds: List<String>,
    )

    @Query("SELECT COALESCE(SUM(amountCents), 0) FROM cash_drawer_events WHERE sessionId = :sessionId AND type = :type")
    suspend fun sumEventsByType(sessionId: String, type: String): Int
}
