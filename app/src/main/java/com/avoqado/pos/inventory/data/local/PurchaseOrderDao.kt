package com.avoqado.pos.inventory.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PurchaseOrderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(orders: List<PurchaseOrderEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(order: PurchaseOrderEntity)

    @Query("SELECT * FROM purchase_orders WHERE venueId = :venueId ORDER BY updatedAt DESC")
    fun getByVenue(venueId: String): Flow<List<PurchaseOrderEntity>>

    @Query("SELECT * FROM purchase_orders WHERE id = :id")
    suspend fun getById(id: String): PurchaseOrderEntity?

    @Query("DELETE FROM purchase_orders WHERE venueId = :venueId")
    suspend fun deleteForVenue(venueId: String)

    @Query("DELETE FROM purchase_orders")
    suspend fun deleteAll()
}
