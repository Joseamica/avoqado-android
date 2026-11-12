package com.avoqado.pos.core.data.sync

import android.content.Context
import com.avoqado.pos.tables.data.OrderDetail
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
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
 * El almacén de las fotos y los dos puntos de paso que las matan (mejor esfuerzo, no garantía: el residual de una escritura propia
 * con desenlace incierto lo detecta y avisa el SERVIDOR). Un ARCHIVO POR FOTO (`<sucursal>/<orden>.json`): matar una foto es
 * `File.delete()`, que funciona aunque el aparato esté lleno (decisión A, Codex b2b-r2).
 *
 * - ① [PuertaDeLaFoto]: toda escritura HTTP que mencione una orden vigilada (con foto o con una lectura en curso), su alias o su
 *   mesa borra la foto ANTES de salir ([empezarEscritura]); la ronda, al volver con 2xx o 4xx ([terminarEscritura]).
 * - ② la cola de mesas: lo que entra y no es ronda, cobro ni cocina, y «Marcar resuelta» ([borrarAntesDe]); un rechazo (R5-1) y
 *   una lectura que ya no es simple ([invalidar]).
 *
 * Invalidar NO depende del interruptor; tomar y usar, sí (la pantalla). Un borrado que falla ANTES de que algo salga frena la
 * petición sólo con el piloto prendido; apagado, la foto queda muerta en memoria y la petición sigue como hoy. Un borrado que falla
 * DESPUÉS de que algo pasó deja la foto muerta en memoria y nunca frena (ya pasó). [alLeerElPlan] con el piloto apagado borra las
 * de ESA sucursal. Sin fotos ni lecturas, nada lee un cuerpo ni toca el disco.
 *
 * Una foto sólo nace ([crearSi]) si nadie escribió sobre la orden desde que se pidió la lectura, nada de ella estaba en vuelo al
 * pedirla y ninguna escritura sin identificar volaba en la tablet en ese momento.
 */
@Singleton
class FotosDeMesas(
    private val directory: File,
    /** El interruptor del piloto en la sucursal ACTUAL: sólo decide si un borrado que falla antes de salir frena o no, y la purga. */
    private val prendido: () -> Boolean = { true },
    private val reloj: () -> Long = System::currentTimeMillis,
) {
    @Inject constructor(@ApplicationContext context: Context, planManager: com.avoqado.pos.core.domain.PlanManager) :
        this(File(context.filesDir, "fotos-de-mesas"), { planManager.concedidaPorAvoqado(CODIGO_PILOTO) })

    /** Una lectura de la cuenta en curso: cualquier escritura que la mencione desde [desde] la ensucia. */
    class Lectura internal constructor(internal val ids: Set<String>, internal val desde: Long, internal val limpia: Boolean)

    /** Una escritura en vuelo: [ids] vacío = no mencionaba nada vigilado (cuenta para que ninguna lectura empiece «limpia»). */
    class Escritura internal constructor(internal val ids: Set<String>)

    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    /** (sucursal, orden) → foto. Las [muertas] siguen vigiladas (su borrado se reintenta) pero nunca se usan. */
    private var cargadas: MutableMap<Pair<String, String>, FotoDeMesa>? = null
    private val muertas = mutableSetOf<Pair<String, String>>()
    private val lecturas = mutableListOf<Lectura>()
    private var version = 0L
    private val marcas = HashMap<String, Long>()
    private val enVuelo = HashMap<String, Int>()
    private var ajenasEnVuelo = 0

    /** La foto vigente de la orden: viva, de la sucursal y con menos de 24 h. */
    fun foto(venueId: String, orderId: String): FotoDeMesa? = synchronized(lock) {
        val llave = venueId to orderId
        fotos()[llave]?.takeIf { llave !in muertas && vigente(it) }
    }

    fun empezarLectura(ids: Set<String>): Lectura = synchronized(lock) {
        val limpia = ajenasEnVuelo == 0 && ids.none { (enVuelo[it] ?: 0) > 0 }
        Lectura(ids, version, limpia).also { lecturas += it }
    }

    fun terminarLectura(lectura: Lectura) = synchronized(lock) { lecturas.remove(lectura); Unit }

    /** Guarda [foto] sólo si [lectura] sigue limpia (ver la clase). Un disco que falla la deja sin foto nueva, nunca a medias. */
    fun crearSi(foto: FotoDeMesa, lectura: Lectura): Boolean = synchronized(lock) {
        val ids = foto.ids + lectura.ids
        if (lectura !in lecturas || !lectura.limpia ||
            ids.any { (marcas[it] ?: 0) > lectura.desde || (enVuelo[it] ?: 0) > 0 }) return@synchronized false
        try { escribir(foto) } catch (_: Exception) { return@synchronized false }
        val llave = foto.venueId to foto.orderId
        fotos()[llave] = foto
        muertas.remove(llave)
        true
    }

    /**
     * ① al salir: [texto] = URL + cuerpo (null = cuerpo ilegible ⇒ mencionaría cualquier cosa). Sin [diferida], borra las fotos
     * mencionadas ANTES de devolver ([borrarAntesDe]).
     */
    fun empezarEscritura(diferida: Boolean, texto: () -> String?): Escritura = synchronized(lock) {
        // El cuerpo se lee bajo el candado (es memoria): así ninguna lectura empieza entre «nada vigilado» y contar la escritura.
        val ids = if (vigilado()) mencionadas(runCatching(texto).getOrNull()) else emptySet()
        if (ids.isEmpty()) { ajenasEnVuelo++; return@synchronized Escritura(emptySet()) }
        if (!diferida) borrar(ids, antes = true)
        ids.forEach { enVuelo[it] = (enVuelo[it] ?: 0) + 1 }
        Escritura(ids)
    }

    /** ① al volver. [borrarAhora]: la ronda que volvió con 2xx o 4xx. Nunca lanza: si el disco falla, la foto queda muerta. */
    fun terminarEscritura(escritura: Escritura, borrarAhora: Boolean = false) = synchronized(lock) {
        if (escritura.ids.isEmpty()) { ajenasEnVuelo--; return@synchronized }
        escritura.ids.forEach { id -> val quedan = (enVuelo[id] ?: 1) - 1; if (quedan > 0) enVuelo[id] = quedan else enVuelo.remove(id) }
        if (borrarAhora) borrar(escritura.ids, antes = false)
    }

    /** ② antes de que algo pase (encolar, «Marcar resuelta»): con el piloto prendido, si no se puede borrar lanza y no pasa. */
    fun borrarAntesDe(texto: String) = synchronized(lock) {
        if (vigilado()) mencionadas(texto).takeIf { it.isNotEmpty() }?.let { borrar(it, antes = true) }
        Unit
    }

    /** Después de saber algo que la invalida (un rechazo, una lectura que ya no es simple). Nunca lanza: si falla, queda muerta. */
    fun invalidar(texto: String) = synchronized(lock) {
        if (vigilado()) mencionadas(texto).takeIf { it.isNotEmpty() }?.let { borrar(it, antes = false) }
        Unit
    }

    /**
     * La tablet guardó el plan de [venueId]. Con el piloto APAGADO, sus fotos (y sólo las suyas) se borran del disco; si alguna no se
     * puede, queda muerta en memoria y se reintenta en la siguiente lectura del plan. Prendido, nada.
     */
    fun alLeerElPlan(venueId: String) = synchronized(lock) {
        if (prendido()) return@synchronized
        val dir = carpeta(venueId)
        val mias = fotos().keys.filter { it.first == venueId }
        if (mias.isEmpty() && !dir.isDirectory) return@synchronized
        for (llave in mias) if (borrarArchivo(llave)) { fotos().remove(llave); muertas.remove(llave) } else muertas += llave
        dir.listFiles().orEmpty().forEach { runCatching { it.delete() } }
    }

    /** Para la bitácora (B3: «cada borrado de foto con su ruta»): las órdenes con foto que [texto] menciona. No toca nada. */
    fun conFotoEn(texto: String): Set<String> = synchronized(lock) {
        fotos().values.filter { f -> f.ids.any { it in texto } }.map { it.orderId }.toSet()
    }

    /** Hay algo que vigilar (fotos vivas o muertas, o lecturas). No depende del interruptor. */
    private fun vigilado(): Boolean = lecturas.isNotEmpty() || fotos().isNotEmpty()

    /** Los ids de toda foto o lectura que [texto] menciona (por id, alias o mesa). Ilegible ⇒ todos. */
    private fun mencionadas(texto: String?): Set<String> {
        val grupos = fotos().values.map { it.ids } + lecturas.map { it.ids }
        return grupos.filter { grupo -> texto == null || grupo.any { it in texto } }.flatten().toSet()
    }

    /**
     * Marca [ids] (una lectura en curso ya no es limpia) y borra sus fotos. Si un archivo no se puede borrar: [antes] y piloto
     * prendido ⇒ la foto sigue viva (nada salió) y lanza [IOException]; si no, queda muerta en memoria y se sigue.
     */
    private fun borrar(ids: Set<String>, antes: Boolean) {
        version++
        ids.forEach { marcas[it] = version }
        val fallidas = fotos().filterValues { it.ids.any(ids::contains) }.keys.filterNot { llave ->
            borrarArchivo(llave).also { if (it) { fotos().remove(llave); muertas.remove(llave) } }
        }
        if (fallidas.isEmpty()) return
        if (antes && prendido()) throw IOException("No se pudo actualizar la cuenta guardada en este aparato")
        muertas += fallidas
    }

    private fun borrarArchivo(llave: Pair<String, String>): Boolean = archivo(llave).let { !it.exists() || it.delete() || !it.exists() }

    private fun vigente(foto: FotoDeMesa): Boolean = (reloj() - foto.tomadaEn) in 0 until VIGENCIA_MS

    private fun hash(texto: String) = MessageDigest.getInstance("SHA-256").digest(texto.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun carpeta(venueId: String) = File(directory, hash(venueId))
    private fun archivo(llave: Pair<String, String>) = File(carpeta(llave.first), hash(llave.second) + ".json")

    private fun fotos(): MutableMap<Pair<String, String>, FotoDeMesa> = cargadas ?: mutableMapOf<Pair<String, String>, FotoDeMesa>().also { mapa ->
        // El formato viejo (un archivo por sucursal en la raíz) se descarta: aún no tenía usuarios.
        directory.listFiles { f -> f.isFile }.orEmpty().forEach { runCatching { it.delete() } }
        directory.listFiles { f -> f.isDirectory }.orEmpty().flatMap { it.listFiles { f -> f.extension == "json" }.orEmpty().toList() }.forEach { a ->
            // Un archivo ilegible o ajeno a su nombre no aporta foto: sin foto, la mesa queda en gris (nunca un saldo inventado).
            val foto = runCatching { json.decodeFromString(FotoDeMesa.serializer(), a.readText()) }.getOrNull()
                ?.takeIf { archivo(it.venueId to it.orderId) == a }
            if (foto != null && vigente(foto)) mapa[foto.venueId to foto.orderId] = foto else runCatching { a.delete() }
        }
        cargadas = mapa
    }

    private fun escribir(foto: FotoDeMesa) {
        val destino = archivo(foto.venueId to foto.orderId)
        val dir = destino.parentFile!!
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("No se pudo guardar la cuenta en este aparato")
        val temporal = File.createTempFile("foto-", ".tmp", dir)
        try {
            FileOutputStream(temporal).use { it.write(json.encodeToString(FotoDeMesa.serializer(), foto).toByteArray()); it.fd.sync() }
            Files.move(temporal.toPath(), destino.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporal.delete() }
    }

    companion object {
        const val VIGENCIA_MS = 24L * 60 * 60 * 1000
        /** El código del piloto «mesas sin red» (espejo EXACTO de la `Feature` del SERVIDOR). */
        const val CODIGO_PILOTO = "MESAS_SIN_RED"
    }
}
