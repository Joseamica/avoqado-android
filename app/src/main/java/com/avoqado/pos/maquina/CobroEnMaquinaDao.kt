package com.avoqado.pos.maquina

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface CobroEnMaquinaDao {

    @Query(CobroEnMaquinaSql.POR_REFERENCIA)
    suspend fun porReferencia(referenceCC: String): CobroEnMaquinaEntity?

    /** C5: la fila en vivo (Room la vuelve a emitir cuando cambia la tabla): la pantalla de resultado, sin sondear. */
    @Query(CobroEnMaquinaSql.POR_REFERENCIA)
    fun observar(referenceCC: String): kotlinx.coroutines.flow.Flow<CobroEnMaquinaEntity?>

    /** Para la caja (C5): mientras dé > 0, la misma venta tampoco se cobra con tarjeta (R2#1). */
    @Query(CobroEnMaquinaSql.BLOQUEAN_LA_VENTA)
    suspend fun bloqueanLaVenta(llaveDeVenta: String): Int

    @Query(CobroEnMaquinaSql.INTENTO_DE_LA_VENTA)
    suspend fun intentoDeLaVenta(llaveDeVenta: String): CobroEnMaquinaEntity?

    @Query(CobroEnMaquinaSql.EN_ESPERA)
    suspend fun enEspera(venueId: String): List<CobroEnMaquinaEntity>

    /** Codex código r1 #7: las dudas de la sucursal en vivo (la franja de Cobrar). */
    @Query(CobroEnMaquinaSql.EN_ESPERA)
    fun observarEnEspera(venueId: String): kotlinx.coroutines.flow.Flow<List<CobroEnMaquinaEntity>>

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
    suspend fun ganarPropiedad(referenceCC: String, generacion: Int): Int

    @Query(CobroEnMaquinaSql.ESCRIBIR_DIARIO)
    suspend fun escribirDiario(referenceCC: String, anterior: String, nuevo: String, creditoAnterior: Int, credito: Int): Int

    /**
     * Codex código r2, bloque A: un mensaje de la máquina al diario de SU folio ([referenceCC]), ANTES de decidir nada. Sólo una fila de
     * esta PC abierta o en duda; un mensaje físico repetido no cambia nada (H13). [creditCents] cuenta sólo si el mensaje es del `Due`
     * de la fila ([dueCents]): lo que entró de otra operación nunca se le atribuye. true = evidencia nueva en disco.
     */
    @Transaction
    suspend fun anotarMensaje(referenceCC: String, generacion: Int, crudo: String, creditCents: Int?, dueCents: Int?): Boolean {
        val fila = porReferencia(referenceCC) ?: return false
        if (fila.estado != EstadoDelIntento.EN_CURSO && fila.estado != EstadoDelIntento.ESPERANDO) return false
        val diario = DiarioDelFolio.agregar(fila.mensajesCrudos, generacion, crudo)
        val credito = if (creditCents != null && dueCents == fila.dueCents) maxOf(fila.creditCents, creditCents) else fila.creditCents
        if (diario == null && credito == fila.creditCents) return false
        return escribirDiario(referenceCC, fila.mensajesCrudos, diario ?: fila.mensajesCrudos, fila.creditCents, credito) > 0
    }

    @Query(CobroEnMaquinaSql.PEDIR_CANCEL)
    suspend fun pedirCancel(referenceCC: String, cancelvalue: String, ahora: Long): Int

    @Query(CobroEnMaquinaSql.CERRAR)
    suspend fun cerrar(
        referenceCC: String,
        estado: String,
        creditCents: Int,
        changeDispensedCents: Int,
        cierreEn: Long,
        conocido: Boolean = true,
        aDeber: Int = 0,
    ): Int

    @Query(CobroEnMaquinaSql.A_ESPERANDO)
    suspend fun aEsperando(referenceCC: String): Int

    @Query(CobroEnMaquinaSql.HUERFANOS_A_ESPERANDO)
    suspend fun huerfanosAEsperando(salvo: String): Int

    /** R5#2: cuántos frenan ([CobroEnMaquinaSql.PENDIENTES]) en [venueId], o en todas con null. */
    @Query(CobroEnMaquinaSql.PENDIENTES)
    suspend fun pendientes(venueId: String?): Int

    /** Lo que la subida (C4) tiene que mandar: cierres finales sin acuse y dudas sin subir. */
    @Query(CobroEnMaquinaSql.POR_SUBIR)
    suspend fun porSubir(): List<CobroEnMaquinaEntity>

    // MARK: - La subida (C4, [SubidaDeLaMaquina])

    @Query(CobroEnMaquinaSql.PARA_SUBIR)
    suspend fun paraSubir(venueId: String): List<CobroEnMaquinaEntity>

    @Query(CobroEnMaquinaSql.ESPERAN_AL_GERENTE)
    suspend fun esperanAlGerente(venueId: String): List<CobroEnMaquinaEntity>

    @Query(CobroEnMaquinaSql.MARCAR_SUBIDO)
    suspend fun marcarSubido(referenceCC: String, conflicto: String?, respuesta: String?, aDeber: Int? = null): Int

    @Query(CobroEnMaquinaSql.ANOTAR_CONFLICTO)
    suspend fun anotarConflicto(referenceCC: String, conflicto: String): Int

    /** [crudos] y [creditCents]: la evidencia que se MANDÓ (Codex código r2 H1). */
    @Query(CobroEnMaquinaSql.MARCAR_DUDA_EN_CONFLICTO)
    suspend fun marcarDudaEnConflicto(referenceCC: String, conflicto: String, respuesta: String, crudos: String, creditCents: Int): Int

    /** [crudos] y [creditCents]: la evidencia que se MANDÓ (Codex código r2 H1). */
    @Query(CobroEnMaquinaSql.MARCAR_DUDA_ABIERTA)
    suspend fun marcarDudaAbierta(referenceCC: String, version: Int?, crudos: String, creditCents: Int): Int

    @Query(CobroEnMaquinaSql.MARCAR_DE_OTRA_PC)
    suspend fun marcarDeOtraPc(referenceCC: String, conflicto: String, respuesta: String): Int

    @Query(CobroEnMaquinaSql.FRENAN_LA_MAQUINA)
    suspend fun frenanLaMaquina(): Int

    @Query(CobroEnMaquinaSql.MARCAR_DUDA_SUBIDA)
    suspend fun marcarDudaSubida(referenceCC: String, subida: Boolean): Int

    @Query(CobroEnMaquinaSql.RESOLVER)
    suspend fun resolver(
        referenceCC: String,
        resueltoPor: String,
        creditCents: Int,
        changeDispensedCents: Int,
        conflicto: String?,
        respuesta: String?,
        desenlace: String?,
        aDeber: Int? = null,
    ): Int

    @Query(CobroEnMaquinaSql.CON_COMPROBANTE)
    suspend fun conComprobante(venueId: String, limite: Int): List<CobroEnMaquinaEntity>

    @Query(CobroEnMaquinaSql.CONTAR_COMPROBANTES)
    suspend fun contarComprobantes(venueId: String): Int

    @Query(CobroEnMaquinaSql.TICKETS_SIN_IMPRIMIR)
    suspend fun ticketsSinImprimir(venueId: String): List<CobroEnMaquinaEntity>

    /** Codex código r1 #12: los tickets pendientes en vivo (la franja de Cobrar). */
    @Query(CobroEnMaquinaSql.TICKETS_SIN_IMPRIMIR)
    fun observarTicketsSinImprimir(venueId: String): kotlinx.coroutines.flow.Flow<List<CobroEnMaquinaEntity>>

    @Query(CobroEnMaquinaSql.RECLAMAR_TICKET)
    suspend fun reclamarTicket(referenceCC: String): Int

    @Query(CobroEnMaquinaSql.TERMINAR_TICKET)
    suspend fun terminarTicket(referenceCC: String, valor: Int): Int
}

/**
 * R5#2 y Codex código r2 H1: ¿hay cobros de la máquina sin subir, abiertos o en duda, de cualquier sucursal? Frena desemparejar y
 * cambiar la IP o la sucursal en Ajustes (C6): con el dinero de una venta en duda, la PC conserva su máquina.
 */
suspend fun CobroEnMaquinaDao.hayPendientesDeLaMaquina(): Boolean = frenanLaMaquina() > 0
