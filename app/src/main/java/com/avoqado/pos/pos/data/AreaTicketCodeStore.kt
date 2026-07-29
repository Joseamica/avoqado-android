// Persistencia de la identidad de acuñado del vale de área: la PARTICIÓN (la asigna el server en
// el login, §5.2) y el CONTADOR monótono (§5.1).
//
// La regla que gobierna todo este archivo: **un código sólo sale de aquí después de que su
// contador quedó grabado en disco.** Si el contador se pierde, el mismo código se acuña dos veces
// y el vale de un cliente resuelve la cuenta de otro — que es exactamente el bug que el formato
// monótono vino a matar.
package com.avoqado.pos.pos.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Resultado de acuñar. Todo estado posible es explícito: el llamador no adivina. */
sealed interface AreaTicketMint {
    /** Código listo para imprimir. Su [counter] YA está persistido. */
    data class Minted(val code: String, val partition: Int, val counter: Long) : AreaTicketMint

    /**
     * No hay partición cacheada (o la cacheada es basura). El generador **falla explícito en vez
     * de inventarse una**: una partición inventada colisiona con la de otro dispositivo y los dos
     * acuñan la misma secuencia. La UI pide re-login para que el server la asigne (§5.2).
     */
    data object MissingPartition : AreaTicketMint

    /**
     * Se agotaron los 6 dígitos de esta partición. **No se da la vuelta**: envolver a 0 reviviría
     * códigos de vales que pueden seguir vivos. Hay que pedir partición nueva al server.
     * [AreaTicketCodeStore.remainingCodes] existe para avisar mucho antes de llegar aquí.
     */
    data object PartitionExhausted : AreaTicketMint

    /**
     * El disco no aceptó el contador (almacenamiento lleno, prefs corruptos). No se entrega
     * código: preferimos que el vale no salga a que salga uno que mañana se repite.
     */
    data object PersistFailed : AreaTicketMint
}

/**
 * Puerta de persistencia — existe para que la lógica de acuñado se pueda probar en JVM sin
 * Android, y para dejar el contrato de durabilidad escrito en un solo lugar.
 */
interface AreaTicketCodeStorage {
    /** Partición cacheada, o null si el server nunca la entregó. */
    fun readPartition(): Int?

    /** Último contador ENTREGADO. 0 = todavía no se acuñó nada en esta partición. */
    fun readCounter(): Long

    /**
     * Escribe partición y contador **juntos y de forma durable**: al volver `true`, el dato ya
     * está en disco (no en una cola de escritura). Devuelve `false` si no se pudo — nunca lanza.
     */
    fun writeDurably(partition: Int, counter: Long): Boolean
}

/**
 * Acuñador del código de vale.
 *
 * ## Cómo se resuelve la concurrencia y la caída a media escritura
 *
 * Un read-modify-write de `SharedPreferences` NO garantiza unicidad por dos motivos distintos, y
 * cada uno tiene su remedio:
 *
 * 1. **Carreras entre hilos.** `getLong` + `putLong` son dos operaciones: dos corrutinas (báscula
 *    y caja disparando a la vez) pueden leer el mismo valor y acuñar el mismo código. Remedio:
 *    [mintNext] es `@Synchronized` — todo el read-modify-write ocurre bajo el monitor de esta
 *    instancia, que es `@Singleton`, así que hay exactamente un monitor por proceso. La app corre
 *    en UN proceso (no hay `android:process` en el manifest); si algún día se agrega uno, esto
 *    DEBE migrar a Room o a un lock de archivo — `SharedPreferences` nunca fue seguro entre
 *    procesos.
 *
 * 2. **Caída/corte de luz.** `apply()` es asíncrono: actualiza memoria y escribe después. Si el
 *    proceso muere en ese hueco, el contador retrocede y el siguiente vale repite un código ya
 *    impreso. Remedio: `commit()` (síncrono; `SharedPreferencesImpl` escribe a un archivo temporal
 *    con `fsync` y renombra, dejando backup — o queda el valor viejo o queda el nuevo, jamás uno
 *    partido), y **se persiste ANTES de devolver el código**. El peor caso pasa a ser un código
 *    QUEMADO (un hueco en la secuencia), que a nadie le importa; lo intolerable era el duplicado.
 *
 * Por qué no Room: metería una entidad nueva en `AvoqadoDatabase` (bump de versión + migración
 * sobre infraestructura compartida) y tampoco regala durabilidad — SQLite en WAL con
 * `synchronous=NORMAL`, que es el default, también puede perder el último commit en un corte. Para
 * un solo entero por dispositivo, `commit()` da atomicidad y `fsync` con cero superficie nueva.
 *
 * Nada se cachea en memoria a propósito: cada acuñado lee del disco, así que un `commit()` fallido
 * jamás puede dejar la memoria adelantada respecto de lo persistido.
 */
@Singleton
class AreaTicketCodeStore internal constructor(
    private val storage: AreaTicketCodeStorage,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(SharedPrefsAreaTicketCodeStorage(context))

    /** Partición vigente, o null si el server aún no la asignó / la cacheada es inválida. */
    val partition: Int?
        get() = storage.readPartition()?.takeIf { it in MIN_AREA_TICKET_PARTITION..MAX_AREA_TICKET_PARTITION }

    /** Último contador entregado (0 = ninguno). Para diagnóstico y para la pantalla de soporte. */
    val counter: Long
        get() = storage.readCounter()

    /** Cuántos vales quedan antes de agotar la partición. Sirve para avisar ANTES de bloquear. */
    val remainingCodes: Long
        get() = (MAX_AREA_TICKET_COUNTER - storage.readCounter()).coerceAtLeast(0L)

    /**
     * Guarda la partición que devolvió el server en el login (`POST /mobile/devices/partition`),
     * junto con el `lastCounter` que ese mismo endpoint reporta.
     *
     * El contador que queda es **el MÁXIMO entre el local y el del server**, nunca el menor.
     * Cada fuente cubre un hueco de la otra:
     *
     * - **El server cubre la reinstalación.** `allowBackup=false` borra el contador local; si el
     *   server le devuelve la MISMA partición, arrancar de 0 repetiría todos los códigos que ya
     *   están impresos y en manos de clientes. El server sí recuerda hasta dónde llegó
     *   (`Terminal.areaTicketLastCounter`).
     * - **El local cubre al server desactualizado.** Los vales se acuñan sin pedirle permiso a
     *   nadie; si unos cuantos no han llegado al server todavía, su `lastCounter` va atrás del
     *   nuestro y hacerle caso reacuñaría esos códigos.
     *
     * Partición distinta → espacio de nombres nuevo: se ignora el contador local y se respeta el
     * del server (0 si es partición virgen). Ambos valores viajan en la MISMA escritura atómica —
     * la combinación venenosa sería partición vieja con contador reiniciado, y así no puede existir.
     *
     * @param serverLastCounter mayor contador que el server ha visto para esta partición.
     * @return `false` si la partición viene fuera de rango o si el disco no aceptó la escritura.
     *   El llamador debe tratarlo como "sin partición" y reintentar; jamás dar por hecho que quedó.
     */
    @Synchronized
    fun setPartition(partition: Int, serverLastCounter: Long = 0L): Boolean {
        if (partition !in MIN_AREA_TICKET_PARTITION..MAX_AREA_TICKET_PARTITION) return false
        val fromServer = serverLastCounter.coerceIn(0L, MAX_AREA_TICKET_COUNTER)
        val fromLocal = if (storage.readPartition() == partition) storage.readCounter() else 0L
        return storage.writeDurably(partition, maxOf(fromLocal, fromServer))
    }

    /**
     * Acuña el siguiente código, **persistiendo el contador antes de devolverlo**.
     *
     * Orden deliberado (reservar → persistir → entregar): si el proceso muere entre la escritura y
     * el `return`, ese código simplemente nunca existió para nadie. Al revés — entregar y después
     * guardar — el vale ya estaría impreso y en manos del cliente cuando el contador se pierde.
     */
    @Synchronized
    fun mintNext(): AreaTicketMint {
        val partition = this.partition ?: return AreaTicketMint.MissingPartition
        val next = storage.readCounter() + 1
        if (next > MAX_AREA_TICKET_COUNTER) return AreaTicketMint.PartitionExhausted
        if (!storage.writeDurably(partition, next)) return AreaTicketMint.PersistFailed
        return AreaTicketMint.Minted(
            code = buildAreaTicketCode(partition, next),
            partition = partition,
            counter = next,
        )
    }
}

/**
 * Implementación Android. Archivo de prefs propio (no `avoqado_prefs`) para que ningún
 * `clear()` de sesión se lleve el contador por delante: el contador sobrevive al logout, al cambio
 * de venue y al cambio de usuario — es identidad del DISPOSITIVO, no de la sesión.
 *
 * Con `allowBackup=false` (AndroidManifest.xml), una reinstalación sí lo borra; por eso el
 * dispositivo pide partición nueva al re-loguearse y las particiones NO se reciclan mientras haya
 * vales vivos (§5.2).
 */
private class SharedPrefsAreaTicketCodeStorage(context: Context) : AreaTicketCodeStorage {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun readPartition(): Int? = prefs.getInt(KEY_PARTITION, NO_PARTITION).takeIf { it != NO_PARTITION }

    override fun readCounter(): Long = prefs.getLong(KEY_COUNTER, 0L)

    override fun writeDurably(partition: Int, counter: Long): Boolean = try {
        // commit() y no apply(): tiene que estar en disco ANTES de que el código salga de aquí.
        prefs.edit()
            .putInt(KEY_PARTITION, partition)
            .putLong(KEY_COUNTER, counter)
            .commit()
    } catch (_: Throwable) {
        // Disco lleno / prefs corruptos: se degrada a "no se acuñó", nunca a "se acuñó sin grabar".
        false
    }

    private companion object {
        const val PREFS_NAME = "avoqado_area_ticket_code"
        const val KEY_PARTITION = "partition"
        const val KEY_COUNTER = "counter"
        const val NO_PARTITION = -1
    }
}
