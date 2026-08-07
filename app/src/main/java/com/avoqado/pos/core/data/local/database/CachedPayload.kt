package com.avoqado.pos.core.data.local.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Offline-first (Corte A — "catálogo indestructible"): espejo en disco de los
 * payloads de solo-lectura que el POS necesita para operar sin internet —
 * catálogo de productos, mesas/plano, menús, settings. Un renglón por payload
 * (`cache_key` = "<tipo>:<venueId>"), el JSON tal cual lo serializa cada
 * repositorio y `updated_at` para pintar frescura ("hace X min").
 *
 * NO es el outbox de escrituras (ese llega en el Corte B): esto solo garantiza
 * que un reinicio de app en modo avión cargue menú y plano completos.
 */
@Entity(tableName = "cached_payloads")
data class CachedPayloadEntity(
    @PrimaryKey
    @ColumnInfo(name = "cache_key")
    val cacheKey: String,
    @ColumnInfo(name = "venue_id")
    val venueId: String,
    @ColumnInfo(name = "json")
    val json: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Dao
interface CachedPayloadDao {
    @Query("SELECT * FROM cached_payloads WHERE cache_key = :cacheKey LIMIT 1")
    suspend fun get(cacheKey: String): CachedPayloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CachedPayloadEntity)

    /** Al cambiar de venue no arrastramos catálogo ajeno. */
    @Query("DELETE FROM cached_payloads WHERE venue_id != :venueId")
    suspend fun deleteOtherVenues(venueId: String)

    /**
     * Borrado de UN payload. Distinto de un fallo de red: se usa cuando el server
     * dice explícitamente que ese contenido ya no le corresponde al local (p. ej.
     * el candado de plan del upsell). Ahí sí hay que soltarlo, no conservarlo.
     */
    @Query("DELETE FROM cached_payloads WHERE cache_key = :cacheKey")
    suspend fun delete(cacheKey: String)
}
