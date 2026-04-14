package com.avoqado.pos.inventory.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface InventoryTransferDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(transfers: List<InventoryTransferEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transfer: InventoryTransferEntity)

    @Query("SELECT * FROM inventory_transfers WHERE venueId = :venueId ORDER BY updatedAt DESC")
    fun getByVenue(venueId: String): Flow<List<InventoryTransferEntity>>

    @Query("SELECT * FROM inventory_transfers WHERE id = :id")
    suspend fun getById(id: String): InventoryTransferEntity?

    @Query("DELETE FROM inventory_transfers WHERE venueId = :venueId")
    suspend fun deleteForVenue(venueId: String)

    @Query("DELETE FROM inventory_transfers")
    suspend fun deleteAll()
}
