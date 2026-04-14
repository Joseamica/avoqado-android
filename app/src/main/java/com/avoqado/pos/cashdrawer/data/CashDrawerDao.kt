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

    @Query("SELECT * FROM cash_drawer_sessions WHERE venueId = :venueId AND status = 'OPEN' LIMIT 1")
    suspend fun getOpenSession(venueId: String): CashDrawerSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: CashDrawerSessionEntity)

    @Update
    suspend fun updateSession(session: CashDrawerSessionEntity)

    @Query("SELECT * FROM cash_drawer_sessions WHERE venueId = :venueId AND status = 'CLOSED' ORDER BY closedAt DESC")
    suspend fun getClosedSessions(venueId: String): List<CashDrawerSessionEntity>

    // MARK: - Events

    @Query("SELECT * FROM cash_drawer_events WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun getSessionEvents(sessionId: String): List<CashDrawerEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: CashDrawerEventEntity)

    @Query("SELECT COALESCE(SUM(amountCents), 0) FROM cash_drawer_events WHERE sessionId = :sessionId AND type = :type")
    suspend fun sumEventsByType(sessionId: String, type: String): Int
}
