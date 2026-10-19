package com.avoqado.pos.customerdisplay

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** Lo que el acuse cuenta del logo: listo en disco, todavía bajando (con reintento) o fallido. */
enum class ImageStatus { READY, PENDING, FAILED }

/**
 * El logo de la pantalla del cliente en disco, por hash. Pintar NUNCA toca la red ([cachedFile]); [ensure] baja lo que falte.
 *
 * - `assets/<sha256>.img`: el logo subido en el panel; se verifica contra su hash y no caduca nunca.
 * - `venue/<sha256(url)>.img`: el logo legado del negocio (sin hash); se revalida con `If-None-Match` cuando cambia su
 *   `version` o a las 24 h, y cualquier fallo conserva la copia vigente.
 * - A cada `.img` su `.json` con el estado (versión, etag, validación, intentos, siguiente intento, fallo permanente).
 *
 * 🔴 Seguridad: sólo hosts de la lista blanca, sin seguir redirecciones, tope de 5 MB mientras se lee, lados ≤ 4096 leídos
 * del encabezado ANTES de decodificar entera, y el hash se verifica antes del movimiento atómico: una descarga a medias o
 * adulterada nunca queda como logo.
 */
@Singleton
class CustomerDisplayImageCache internal constructor(
    private val dir: File,
    private val client: OkHttpClient,
    private val httpsOnly: Boolean,
    private val wallClock: () -> Long,
) {
    // Cliente PROPIO y pelón, como ReceiptLogoCache: el logo vive en el Storage (otro host) y los interceptores de auth de la
    // app no pintan nada aquí. El tope de 5 MB limita el tamaño; el callTimeout, la duración.
    @Inject constructor(@ApplicationContext context: Context) : this(
        dir = File(context.filesDir, "customer-display-images"),
        client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(30, TimeUnit.SECONDS)
            .build(),
        httpsOnly = true,
        wallClock = System::currentTimeMillis,
    )

    @Serializable
    private data class Meta(
        val version: String? = null,
        val etag: String? = null,
        val validatedAtMs: Long? = null,
        val attempts: Int = 0,
        val nextAttemptAtMs: Long? = null,
        val permanent: Boolean = false,
    )

    private sealed interface Outcome {
        class Fresh(val tmp: File, val etag: String?) : Outcome
        data object NotModified : Outcome
        class Permanent(val reason: String) : Outcome
        class Transient(val reason: String) : Outcome
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    /** Sólo disco, nunca red. `null` si ese logo todavía no está bajado y verificado. */
    fun cachedFile(ref: LogoRef): File? = imageFile(ref)?.takeIf { it.isFile }

    /** Sólo disco. Legado: cambió la `version` o la última validación tiene ≥ 24 h. Un asset (por hash) nunca caduca. */
    fun revalidationDue(ref: LogoRef): Boolean {
        if (ref !is LogoRef.Venue) return false
        val meta = imageFile(ref)?.let(::readMeta)
        val validatedAt = meta?.validatedAtMs ?: return true
        return meta.version != ref.version || wallClock() - validatedAt >= DAY_MS
    }

    suspend fun ensure(ref: LogoRef, origins: List<String>): ImageStatus = withContext(Dispatchers.IO) {
        mutex.withLock { ensureLocked(ref, origins) }
    }

    private fun ensureLocked(ref: LogoRef, origins: List<String>): ImageStatus {
        val image = imageFile(ref) ?: return ImageStatus.FAILED
        val hasCopy = image.isFile
        if (hasCopy && !revalidationDue(ref)) return ImageStatus.READY
        // Con copia, cualquier tropiezo de aquí en adelante responde READY: se sigue pintando la vigente.
        val failed = if (hasCopy) ImageStatus.READY else ImageStatus.FAILED
        if (!isAllowed(ref.url, origins)) return failed

        val stored = readMeta(image)
        // Sin copia, el estado de fallos es de la versión que se intentó: otra versión es otra referencia y empieza de cero.
        val meta = if (!hasCopy && ref is LogoRef.Venue && stored?.version != ref.version) null else stored
        val now = wallClock()
        if (meta?.permanent == true) return failed
        if ((meta?.nextAttemptAtMs ?: 0L) > now) return if (hasCopy) ImageStatus.READY else pendingOrFailed(meta!!.attempts)

        val outcome = try {
            download(ref, image, etag = if (hasCopy) stored?.etag else null)
        } catch (e: IOException) {
            Outcome.Transient(e.message ?: e.javaClass.simpleName)   // sin red, timeout, disco lleno
        }
        val version = (ref as? LogoRef.Venue)?.version
        return when (outcome) {
            is Outcome.Fresh -> try {
                Files.move(outcome.tmp.toPath(), image.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                writeMeta(image, Meta(version = version, etag = outcome.etag, validatedAtMs = now))
                ImageStatus.READY
            } catch (e: IOException) {
                outcome.tmp.delete()
                transient(image, meta, version, now, hasCopy, e.message ?: "no se pudo guardar")
            }
            // ponytail: con copia, el permanente/los intentos son de la URL (la llave del archivo), no de cada versión:
            // una URL que ya redirige o creció deja de pedirse; si cambia la URL, es otro archivo y empieza de cero.
            Outcome.NotModified -> {
                writeMeta(image, (stored ?: Meta()).copy(version = version, validatedAtMs = now, attempts = 0, nextAttemptAtMs = null))
                ImageStatus.READY
            }
            is Outcome.Permanent -> {
                Log.w(TAG, "Logo descartado sin reintento (${outcome.reason}): ${ref.url.substringBefore('?')}")   // sin el token
                writeMeta(image, (meta ?: Meta(version = version)).copy(permanent = true))
                failed
            }
            is Outcome.Transient -> transient(image, meta, version, now, hasCopy, outcome.reason)
        }
    }

    /** Suma un intento y agenda el siguiente con espera `min(2^intentos, 60)` minutos. Sigue reintentando aun ya «fallida». */
    private fun transient(image: File, meta: Meta?, version: String?, now: Long, hasCopy: Boolean, reason: String): ImageStatus {
        val attempts = (meta?.attempts ?: 0) + 1
        val waitMinutes = minOf(1L shl attempts.coerceAtMost(6), MAX_WAIT_MINUTES)
        writeMeta(image, (meta ?: Meta(version = version)).copy(attempts = attempts, nextAttemptAtMs = now + TimeUnit.MINUTES.toMillis(waitMinutes)))
        Log.w(TAG, "Logo no disponible (intento $attempts, siguiente en $waitMinutes min): $reason")
        return if (hasCopy) ImageStatus.READY else pendingOrFailed(attempts)
    }

    private fun pendingOrFailed(attempts: Int) = if (attempts >= FAILED_AFTER_ATTEMPTS) ImageStatus.FAILED else ImageStatus.PENDING

    private fun download(ref: LogoRef, image: File, etag: String?): Outcome {
        val request = Request.Builder().url(ref.url).apply { if (etag != null) header("If-None-Match", etag) }.build()
        client.newCall(request).execute().use { response ->
            val code = response.code
            if (code == 304 && etag != null) return Outcome.NotModified
            if (code in 300..399) return Outcome.Permanent("redirección $code")
            // 5xx, 408, 429 y cualquier otro: se reintenta con espera.
            if (code !in 200..299) return Outcome.Transient("HTTP $code")
            val body = response.body ?: return Outcome.Transient("respuesta sin cuerpo")
            if (body.contentLength() > MAX_BYTES) return Outcome.Permanent("más de 5 MB")

            val folder = image.parentFile!!
            if (!folder.isDirectory && !folder.mkdirs()) throw IOException("no se pudo crear $folder")
            val tmp = File.createTempFile("img-", ".tmp", folder)
            var keep = false
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                FileOutputStream(tmp).use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_BYTES) return Outcome.Permanent("más de 5 MB")
                            digest.update(buffer, 0, read)
                            out.write(buffer, 0, read)
                        }
                    }
                    out.fd.sync()
                }
                if (ref is LogoRef.Asset && hex(digest.digest()) != ref.sha256) return Outcome.Permanent("hash distinto")
                rejection(tmp)?.let { return Outcome.Permanent(it) }
                keep = true
                return Outcome.Fresh(tmp, response.header("ETag"))
            } finally {
                if (!keep) tmp.delete()
            }
        }
    }

    /** `null` = imagen válida. Los lados se leen del encabezado ANTES de decodificar entera (una bomba de pixeles no entra). */
    private fun rejection(file: File): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return "no es imagen"
        if (bounds.outWidth > MAX_SIDE || bounds.outHeight > MAX_SIDE) return "lados ${bounds.outWidth}×${bounds.outHeight}"
        val bitmap = BitmapFactory.decodeFile(file.path) ?: return "no es imagen"
        bitmap.recycle()
        return null
    }

    /**
     * Si la carpeta pasa de 100 MB, borra lo NO referenciado, lo más viejo primero. Cada `.img` se va con su `.json`.
     */
    fun prune(referenced: Collection<LogoRef>) {
        val keep = referenced.mapNotNull(::imageFile).map { it.absolutePath.removeSuffix(".img") }.toSet()
        val groups = listOf("assets", "venue")
            .flatMap { File(dir, it).listFiles()?.filter(File::isFile).orEmpty() }
            .groupBy { File(it.parentFile, it.nameWithoutExtension).absolutePath }
        var total = groups.values.sumOf { files -> files.sumOf(File::length) }
        if (total <= MAX_DIR_BYTES) return
        val candidates = groups.filterKeys { it !in keep }.values.sortedBy { files -> files.maxOf(File::lastModified) }
        for (files in candidates) {
            if (total <= MAX_DIR_BYTES) break
            files.forEach { file ->
                val size = file.length()
                if (file.delete()) total -= size
            }
        }
    }

    private fun isAllowed(url: String, origins: List<String>): Boolean =
        if (httpsOnly) isAllowedImageUrl(url, origins) else origins.any { url.startsWith("$it/") }   // sólo las pruebas (http local)

    private fun imageFile(ref: LogoRef): File? = when (ref) {
        // El hash viene validado del server, pero es nombre de archivo: aquí se vuelve a exigir (nada de `../`).
        is LogoRef.Asset -> if (SHA256_HEX.matches(ref.sha256)) File(dir, "assets/${ref.sha256}.img") else null
        is LogoRef.Venue -> File(dir, "venue/${hex(MessageDigest.getInstance("SHA-256").digest(ref.url.toByteArray()))}.img")
    }

    private val LogoRef.url get() = when (this) {
        is LogoRef.Asset -> url
        is LogoRef.Venue -> url
    }

    private fun metaFile(image: File) = File(image.parentFile, image.nameWithoutExtension + ".json")

    private fun readMeta(image: File): Meta? = metaFile(image).takeIf { it.isFile }?.let { file ->
        runCatching { json.decodeFromString(Meta.serializer(), file.readText()) }.getOrNull()
    }

    /** Temporal + movimiento atómico, como el `.img`. Si el disco no deja, se sigue: la siguiente llamada vuelve a decidir. */
    private fun writeMeta(image: File, meta: Meta) {
        runCatching {
            val folder = image.parentFile!!
            if (!folder.isDirectory && !folder.mkdirs()) throw IOException("no se pudo crear $folder")
            val tmp = File.createTempFile("meta-", ".tmp", folder)
            try {
                FileOutputStream(tmp).use { it.write(json.encodeToString(Meta.serializer(), meta).toByteArray()); it.fd.sync() }
                Files.move(tmp.toPath(), metaFile(image).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "No se pudo guardar el estado del logo: ${it.message}") }
    }

    private companion object {
        const val TAG = "CustomerDisplayImage"
        const val MAX_BYTES = 5L * 1024 * 1024
        const val MAX_SIDE = 4096
        const val MAX_DIR_BYTES = 100L * 1024 * 1024
        const val FAILED_AFTER_ATTEMPTS = 5
        const val MAX_WAIT_MINUTES = 60L
        val DAY_MS = TimeUnit.HOURS.toMillis(24)
        val SHA256_HEX = Regex("^[0-9a-f]{64}$")

        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    }
}
