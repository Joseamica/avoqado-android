package com.avoqado.pos.tables.data

import android.content.Context
import com.avoqado.pos.tables.presentation.TableOrderViewModel.PendingLine
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Scoped durable drafts. Prepared lines cannot also be restored as new, unsent products. */
@Singleton
class TableRoundDraftStore(private val directory: File) {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.filesDir, "table-round-drafts"))
    @Serializable data class Snapshot(
        val pending: List<PendingLine> = emptyList(),
        val rounds: Map<String, List<PendingLine>> = emptyMap(),
        val createdAtLocal: Map<String, Long> = emptyMap(),
        val cachedCheck: OrderDetail? = null,
        val redirectOrderId: String? = null,
        val preparationRouting: Map<String, com.avoqado.pos.printing.routing.PrintConfig> = emptyMap(),
    )
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun file(venueId: String, orderId: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest("$venueId\u0000$orderId".toByteArray()).joinToString("") { "%02x".format(it) }
        return File(directory, "$hash.json")
    }
    private fun read(venueId: String, orderId: String): Snapshot {
        val file = file(venueId, orderId)
        return if (file.exists()) json.decodeFromString(file.readText()) else Snapshot()
    }
    private fun resolved(venueId: String, orderId: String): Pair<String, Snapshot> {
        var current = orderId
        val visited = mutableSetOf<String>()
        repeat(8) {
            check(visited.add(current)) { "No se pudo recuperar el pedido guardado" }
            val snapshot = read(venueId, current)
            val redirect = snapshot.redirectOrderId ?: return current to snapshot
            check(redirect.isNotBlank()) { "No se pudo recuperar el pedido guardado" }
            current = redirect
        }
        error("No se pudo recuperar el pedido guardado")
    }
    fun load(venueId: String, orderId: String): Snapshot = synchronized(diskLock) { resolved(venueId, orderId).second }
    fun loadResolved(venueId: String, orderId: String): Pair<String, Snapshot> = synchronized(diskLock) { resolved(venueId, orderId) }
    fun freezePreparationRouting(venueId: String, orderId: String, roundKey: String,
        config: com.avoqado.pos.printing.routing.PrintConfig): com.avoqado.pos.printing.routing.PrintConfig = synchronized(diskLock) {
        val (targetOrder, snapshot) = resolved(venueId, orderId)
        require(snapshot.rounds.containsKey(roundKey)) { "Primero guarda la ronda en este pedido" }
        snapshot.preparationRouting[roundKey]?.let { return@synchronized it }
        write(venueId, targetOrder, snapshot.copy(preparationRouting = snapshot.preparationRouting + (roundKey to config)))
        config
    }
    fun save(venueId: String, orderId: String, pending: List<PendingLine>, rounds: Map<String, List<PendingLine>>, cachedCheck: OrderDetail? = null,
        preparationRouting: Map<String, com.avoqado.pos.printing.routing.PrintConfig> = emptyMap()): Snapshot = synchronized(diskLock) {
        val (targetOrder, previous) = resolved(venueId, orderId)
        require(cachedCheck == null || cachedCheck.id == orderId || cachedCheck.id == targetOrder) { "La cuenta no pertenece a este pedido" }
        require(preparationRouting.keys.all { it in rounds }) { "Primero guarda la ronda en este pedido" }
        val stagedIds = rounds.values.flatten().map { it.item.id }.toSet()
        val now = System.currentTimeMillis()
        val snapshot = Snapshot(pending.filterNot { it.item.id in stagedIds }, rounds,
            rounds.keys.associateWith { previous.createdAtLocal[it] ?: now }, cachedCheck?.copy(id = targetOrder) ?: previous.cachedCheck,
            preparationRouting = (preparationRouting + previous.preparationRouting).filterKeys { it in rounds })
        write(venueId, targetOrder, snapshot)
        snapshot
    }

    /** Destination is durable BEFORE the alias and the terminal OPEN_TABLE acknowledgement. */
    fun promote(venueId: String, localOrderId: String, orderId: String) = synchronized(diskLock) {
        require(localOrderId.isNotBlank() && orderId.isNotBlank())
        val (sourceOrder, source) = resolved(venueId, localOrderId)
        val (targetOrder, target) = resolved(venueId, orderId)
        if (sourceOrder == targetOrder) return@synchronized
        check(sourceOrder == localOrderId) { "El pedido ya pertenece a otra cuenta" }
        require(source.cachedCheck == null || source.cachedCheck.id == localOrderId)
        require(target.cachedCheck == null || target.cachedCheck.id == targetOrder)
        val rounds = source.rounds + target.rounds
        val stagedIds = rounds.values.flatten().map { it.item.id }.toSet()
        val pending = (source.pending + target.pending).associateBy { it.item.id }.values.filterNot { it.item.id in stagedIds }
        val dates = rounds.keys.associateWith { key ->
            listOfNotNull(source.createdAtLocal[key], target.createdAtLocal[key]).minOrNull() ?: System.currentTimeMillis()
        }
        val routing = (source.preparationRouting + target.preparationRouting).filterKeys { it in rounds }
        write(venueId, targetOrder, Snapshot(pending, rounds, dates, target.cachedCheck ?: source.cachedCheck?.copy(id = targetOrder),
            preparationRouting = routing))
        write(venueId, localOrderId, Snapshot(redirectOrderId = targetOrder))
    }
    private fun write(venueId: String, orderId: String, snapshot: Snapshot) {
        check(directory.isDirectory || directory.mkdirs()) { "No se pudo guardar el pedido en este aparato" }
        val target = file(venueId, orderId)
        val temporary = File.createTempFile("draft-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { it.write(json.encodeToString(snapshot).toByteArray()); it.fd.sync() }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
    companion object {
        // The outbox and the screen have separate store instances over the same files.
        private val diskLock = Any()
    }
}
