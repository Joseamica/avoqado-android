package com.avoqado.pos.core.data.sync

import android.content.Context
import com.avoqado.pos.tables.data.OrderDetail
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * D5 · B2b (piloto `MESAS_SIN_RED`): la última cuenta COMPLETA y simple que esta tablet leyó del servidor de una mesa que ella
 * tomó. Sin internet, la mesa se cobra con esta foto + sus rondas posteriores − su efectivo posterior (`saldoDeMesaDelServidor`).
 */
@Serializable
data class FotoDeMesa(
    val venueId: String,
    val orderId: String,
    val alias: List<String> = emptyList(),
    val tableId: String? = null,
    val detalle: OrderDetail,
    /** La secuencia de la cola al PEDIR la lectura: lo que entró a la cola después tiene una mayor. */
    val seq: Long,
    /** Las llaves de ronda que ya existían al tomarla (borrador y renglones `sync:<llave>:<i>` de la cuenta). */
    val llavesPrevias: List<String> = emptyList(),
    val tomadaEn: Long,
) {
    val ids: Set<String> get() = (alias + orderId + listOfNotNull(tableId)).filter { it.isNotBlank() }.toSet()
}

/**
 * El almacén de las fotos, en disco por sucursal, y los dos puntos de paso que las matan (mejor esfuerzo, no garantía: el residual
 * de una escritura propia con desenlace incierto lo detecta y avisa el SERVIDOR).
 *
 * - ① [PuertaDeLaFoto]: toda escritura HTTP que mencione una orden vigilada (con foto o con una lectura en curso), su alias o su
 *   mesa borra la foto del disco ANTES de salir ([empezarEscritura]); la ronda, al volver con 2xx o 4xx ([terminarEscritura]).
 * - ② la cola de mesas ([borrarSiMenciona]): lo que entra y no es ronda, cobro ni cocina; y una ronda rechazada o descartada (R5-1).
 *
 * Una foto sólo nace ([crearSi]) si nadie escribió sobre la orden desde que se pidió la lectura, nada de ella estaba en vuelo al
 * pedirla y ninguna escritura sin identificar volaba en la tablet en ese momento. Sin fotos ni lecturas, ① no lee el cuerpo ni toca el disco.
 */
@Singleton
class FotosDeMesas(private val directory: File, private val reloj: () -> Long = System::currentTimeMillis) {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.filesDir, "fotos-de-mesas"))

    /** Una lectura de la cuenta en curso: cualquier escritura que la mencione desde [desde] la ensucia. */
    class Lectura internal constructor(internal val ids: Set<String>, internal val desde: Long, internal val limpia: Boolean)

    /** Una escritura en vuelo: [ids] vacío = no mencionaba nada vigilado (cuenta para que ninguna lectura empiece «limpia»). */
    class Escritura internal constructor(internal val ids: Set<String>)

    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var cargadas: MutableMap<String, MutableMap<String, FotoDeMesa>>? = null
    private val lecturas = mutableListOf<Lectura>()
    private var version = 0L
    private val marcas = HashMap<String, Long>()
    private val enVuelo = HashMap<String, Int>()
    private var ajenasEnVuelo = 0

    /** La foto vigente de la orden: la de la sucursal, con menos de 24 h. */
    fun foto(venueId: String, orderId: String): FotoDeMesa? = synchronized(lock) {
        fotos()[venueId]?.get(orderId)?.takeIf { vigente(it) }
    }

    fun empezarLectura(ids: Set<String>): Lectura = synchronized(lock) {
        val limpia = ajenasEnVuelo == 0 && ids.none { (enVuelo[it] ?: 0) > 0 }
        Lectura(ids, version, limpia).also { lecturas += it }
    }

    fun terminarLectura(lectura: Lectura) = synchronized(lock) { lecturas.remove(lectura); Unit }

    /** Guarda [foto] sólo si [lectura] sigue limpia (ver la clase). Un disco que falla la deja sin foto (gris), nunca a medias. */
    fun crearSi(foto: FotoDeMesa, lectura: Lectura): Boolean = synchronized(lock) {
        val ids = foto.ids + lectura.ids
        if (lectura !in lecturas || !lectura.limpia ||
            ids.any { (marcas[it] ?: 0) > lectura.desde || (enVuelo[it] ?: 0) > 0 }) return@synchronized false
        val sucursal = fotos().getOrPut(foto.venueId) { mutableMapOf() }
        val antes = sucursal[foto.orderId]
        sucursal[foto.orderId] = foto
        try { escribir(foto.venueId); true } catch (_: Exception) {
            if (antes == null) sucursal.remove(foto.orderId) else sucursal[foto.orderId] = antes
            false
        }
    }

    /**
     * ① al salir: [texto] = URL + cuerpo (null = cuerpo ilegible ⇒ mencionaría cualquier cosa). Sin [diferida], borra del disco
     * las fotos mencionadas ANTES de devolver; si el disco falla lanza [IOException] y la petición no sale.
     */
    fun empezarEscritura(diferida: Boolean, texto: () -> String?): Escritura = synchronized(lock) {
        // El cuerpo se lee bajo el candado (es memoria): así ninguna lectura empieza entre «nada vigilado» y contar la escritura.
        val ids = if (vigilado()) mencionadas(runCatching(texto).getOrNull()) else emptySet()
        if (ids.isEmpty()) { ajenasEnVuelo++; return@synchronized Escritura(emptySet()) }
        if (!diferida) borrar(ids)
        ids.forEach { enVuelo[it] = (enVuelo[it] ?: 0) + 1 }
        Escritura(ids)
    }

    /** ① al volver. [borrarAhora]: la ronda que volvió con 2xx o 4xx. Si el disco falla, lanza [IOException]. */
    fun terminarEscritura(escritura: Escritura, borrarAhora: Boolean = false) = synchronized(lock) {
        if (escritura.ids.isEmpty()) { ajenasEnVuelo--; return@synchronized }
        escritura.ids.forEach { id -> val quedan = (enVuelo[id] ?: 1) - 1; if (quedan > 0) enVuelo[id] = quedan else enVuelo.remove(id) }
        if (borrarAhora) borrar(escritura.ids)
    }

    /** ②: [texto] (un payload de la cola) menciona una orden vigilada ⇒ su foto se borra del disco antes de seguir. */
    fun borrarSiMenciona(texto: String) = synchronized(lock) {
        if (vigilado()) mencionadas(texto).takeIf { it.isNotEmpty() }?.let(::borrar)
        Unit
    }

    private fun vigilado(): Boolean = lecturas.isNotEmpty() || fotos().values.any { it.isNotEmpty() }

    /** Los ids de toda foto o lectura que [texto] menciona (por id, alias o mesa). Ilegible ⇒ todos. */
    private fun mencionadas(texto: String?): Set<String> {
        val grupos = fotos().values.flatMap { it.values }.map { it.ids } + lecturas.map { it.ids }
        return grupos.filter { grupo -> texto == null || grupo.any { it in texto } }.flatten().toSet()
    }

    /** Marca [ids] (una lectura en curso ya no es limpia) y borra del disco sus fotos; si el disco falla, se reponen y lanza. */
    private fun borrar(ids: Set<String>) {
        version++
        ids.forEach { marcas[it] = version }
        val porSucursal = fotos().mapNotNull { (venue, sucursal) ->
            val quitadas = sucursal.filterValues { it.ids.any(ids::contains) }
            if (quitadas.isEmpty()) null else Triple(venue, sucursal, quitadas)
        }
        porSucursal.forEach { (_, sucursal, quitadas) -> quitadas.keys.forEach(sucursal::remove) }
        try { porSucursal.forEach { (venue, _, _) -> escribir(venue) } }
        catch (e: Exception) {
            porSucursal.forEach { (_, sucursal, quitadas) -> sucursal.putAll(quitadas) }
            throw if (e is IOException) e else IOException("No se pudo actualizar la cuenta guardada en este aparato", e)
        }
    }

    private fun vigente(foto: FotoDeMesa): Boolean = (reloj() - foto.tomadaEn) in 0 until VIGENCIA_MS

    private fun fotos(): MutableMap<String, MutableMap<String, FotoDeMesa>> = cargadas ?: mutableMapOf<String, MutableMap<String, FotoDeMesa>>().also { mapa ->
        directory.listFiles { f -> f.extension == "json" }.orEmpty().forEach { archivo ->
            // Un archivo ilegible no aporta fotos: sin foto, la mesa queda en gris (nunca un saldo inventado).
            runCatching { json.decodeFromString(ListSerializer(FotoDeMesa.serializer()), archivo.readText()) }.getOrNull()
                ?.forEach { mapa.getOrPut(it.venueId) { mutableMapOf() }[it.orderId] = it }
        }
        cargadas = mapa
    }

    private fun escribir(venueId: String) {
        val vivas = fotos()[venueId]?.values?.filter(::vigente).orEmpty()
        val destino = File(directory, MessageDigest.getInstance("SHA-256").digest(venueId.toByteArray()).joinToString("") { "%02x".format(it) } + ".json")
        if (vivas.isEmpty()) { if (destino.exists() && !destino.delete()) throw IOException("No se pudo borrar la cuenta guardada"); return }
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("No se pudo guardar la cuenta en este aparato")
        val temporal = File.createTempFile("foto-", ".tmp", directory)
        try {
            FileOutputStream(temporal).use { it.write(json.encodeToString(ListSerializer(FotoDeMesa.serializer()), vivas).toByteArray()); it.fd.sync() }
            Files.move(temporal.toPath(), destino.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporal.delete() }
    }

    companion object { const val VIGENCIA_MS = 24L * 60 * 60 * 1000 }
}
