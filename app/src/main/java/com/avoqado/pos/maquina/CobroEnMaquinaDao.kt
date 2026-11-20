package com.avoqado.pos.maquina

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface CobroEnMaquinaDao {

    @Query(CobroEnMaquinaSql.POR_REFERENCIA)
    suspend fun porReferencia(referenceCC: String): CobroEnMaquinaEntity?

    /** Para la caja (C5): mientras dé > 0, la misma venta tampoco se cobra con tarjeta (R2#1). */
    @Query(CobroEnMaquinaSql.BLOQUEAN_LA_VENTA)
    suspend fun bloqueanLaVenta(llaveDeVenta: String): Int

    @Insert
    suspend fun insertar(fila: CobroEnMaquinaEntity)

    /**
     * Un solo intento abierto (o en duda, o pagado) por venta. Se revisa y se inserta en una transacción: Room no sabe
     * declarar un índice único PARCIAL (`@Index` no lleva `WHERE`), y uno escrito a mano en la migración lo rechaza su
     * validación al abrir la base (lee los índices de la tabla y no coinciden con la entidad), en Android y en WINDOWS.
     */
    @Transaction
    suspend fun abrirIntento(fila: CobroEnMaquinaEntity): Boolean {
        if (bloqueanLaVenta(fila.llaveDeVenta) > 0 || porReferencia(fila.referenceCC) != null) return false
        insertar(fila)
        return true
    }

    @Query(CobroEnMaquinaSql.BORRAR_SIN_ENVIAR)
    suspend fun borrarSinEnviar(referenceCC: String): Int

    @Query(CobroEnMaquinaSql.GANAR_PROPIEDAD)
    suspend fun ganarPropiedad(referenceCC: String, generacion: Int, crudos: String): Int

    @Query(CobroEnMaquinaSql.ANOTAR_CREDITO)
    suspend fun anotarCredito(referenceCC: String, creditCents: Int, ahora: Long): Int

    @Query(CobroEnMaquinaSql.PEDIR_CANCEL)
    suspend fun pedirCancel(referenceCC: String, cancelvalue: String, ahora: Long): Int

    @Query(CobroEnMaquinaSql.CERRAR)
    suspend fun cerrar(referenceCC: String, estado: String, creditCents: Int, changeDispensedCents: Int, cierreEn: Long, crudos: String): Int

    @Query(CobroEnMaquinaSql.A_ESPERANDO)
    suspend fun aEsperando(referenceCC: String, crudos: String): Int

    @Query(CobroEnMaquinaSql.HUERFANOS_A_ESPERANDO)
    suspend fun huerfanosAEsperando(salvo: String): Int

    @Query(CobroEnMaquinaSql.ANOTAR_EVIDENCIA)
    suspend fun anotarEvidencia(referenceCC: String, crudos: String): Int

    /** Lo que la subida (C4) tiene que mandar: cierres finales sin acuse y dudas sin subir. */
    @Query(CobroEnMaquinaSql.POR_SUBIR)
    suspend fun porSubir(): List<CobroEnMaquinaEntity>
}
