package com.avoqado.pos.pos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Vale de área — formato `9 PP NNNNNN C`, contador monótono y resolución del escáner (§5.1).
 *
 * Lo que estos tests protegen, en orden de qué tan caro sale romperlo:
 *  1. Que dos vales JAMÁS compartan código (ni entre acuñados seguidos, ni entre particiones,
 *     ni entre hilos, ni después de una escritura fallida).
 *  2. Que el escáner nunca confunda un producto con un vale ni al revés.
 *  3. Que el verificador sea el GS1 estándar, para que server e iOS lo puedan espejar.
 */
class AreaTicketCodeTest {

    // MARK: - Verificador mod-10 (GS1), calculado a mano

    @Test
    fun `check digit for payload 900000001 is 0`() {
        // Pesos 3,1 desde la derecha del payload: 1×3 + 9×3 = 30 → 30 % 10 = 0 → verificador 0.
        assertEquals(0, checkDigit("900000001"))
    }

    @Test
    fun `check digit for payload 947000001 is 5`() {
        // 1×3 + 7×3 + 4×1 + 9×3 = 3 + 21 + 4 + 27 = 55 → 10 - 5 = 5.
        assertEquals(5, checkDigit("947000001"))
    }

    @Test
    fun `check digit for payload 947000123 is 4`() {
        // 3×3 + 2×1 + 1×3 + 7×3 + 4×1 + 9×3 = 9 + 2 + 3 + 21 + 4 + 27 = 66 → 10 - 6 = 4.
        assertEquals(4, checkDigit("947000123"))
    }

    @Test
    fun `check digit for the all-nines payload is 9`() {
        // 5 nueves con peso 3 (135) + 4 nueves con peso 1 (36) = 171 → 10 - 1 = 9.
        assertEquals(9, checkDigit("999999999"))
    }

    @Test
    fun `matches the real EAN-13 and UPC-A check digits`() {
        // Ancla contra el estándar: si alguien cambia el algoritmo (p.ej. a Luhn, que también se
        // llama "mod-10"), estos dos códigos reales del mundo dejan de cuadrar. Es la prueba de
        // que server e iOS pueden mirrorearlo con cualquier librería GS1.
        assertEquals(1, checkDigit("400638133393")) // 4006381333931
        assertEquals(2, checkDigit("03600029145")) // 036000291452
    }

    // MARK: - buildAreaTicketCode

    @Test
    fun `builds the 9-PP-NNNNNN-C layout with zero padding`() {
        assertEquals("9470000015", buildAreaTicketCode(partition = 47, counter = 1))
        assertEquals("9470001234", buildAreaTicketCode(partition = 47, counter = 123))
        assertEquals("9999999999", buildAreaTicketCode(partition = 99, counter = 999_999))
    }

    @Test
    fun `built codes are always 10 digits starting with 9`() {
        val code = buildAreaTicketCode(partition = 10, counter = 0)
        assertEquals(AREA_TICKET_CODE_LENGTH, code.length)
        assertEquals(AREA_TICKET_NAMESPACE, code[0])
        assertTrue(code.all { it.isDigit() })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `partition below range throws`() {
        buildAreaTicketCode(partition = 9, counter = 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `partition above range throws`() {
        buildAreaTicketCode(partition = 100, counter = 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative counter throws`() {
        buildAreaTicketCode(partition = 47, counter = -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `counter past six digits throws instead of truncating`() {
        // Truncar daría "9-47-000000" otra vez: el código del PRIMER vale de la partición.
        buildAreaTicketCode(partition = 47, counter = 1_000_000)
    }

    // MARK: - Ida y vuelta

    @Test
    fun `every built code resolves back to its own partition and counter`() {
        for (partition in MIN_AREA_TICKET_PARTITION..MAX_AREA_TICKET_PARTITION) {
            for (counter in listOf(0L, 1L, 42L, 999L, 123_456L, 999_999L)) {
                val code = buildAreaTicketCode(partition, counter)
                assertTrue("$code debería ser vale", isAreaTicketCode(code))
                val resolved = resolveScannedCode(code)
                assertTrue("$code debería resolver como vale", resolved is ScannedCode.AreaTicket)
                resolved as ScannedCode.AreaTicket
                assertEquals(partition, resolved.partition)
                assertEquals(counter, resolved.counter)
            }
        }
    }

    @Test
    fun `any single wrong digit is caught by the check digit`() {
        // El verificador es contra errores de dedo: que el cajero re-teclee un dígito mal y el
        // sistema resuelva la cuenta de OTRO cliente es justo lo que no puede pasar.
        val valid = buildAreaTicketCode(partition = 47, counter = 12_345)
        for (index in valid.indices) {
            for (digit in '0'..'9') {
                if (digit == valid[index]) continue
                val typo = valid.substring(0, index) + digit + valid.substring(index + 1)
                assertFalse("$typo no debería pasar como vale", isAreaTicketCode(typo))
            }
        }
    }

    // MARK: - Resolución del escáner (§5.1)

    @Test
    fun `ten digits starting with 9 and a valid check digit is a ticket`() {
        assertTrue(resolveScannedCode("9000000010") is ScannedCode.AreaTicket)

        val resolved = resolveScannedCode("9470000015")
        assertTrue(resolved is ScannedCode.AreaTicket)
        resolved as ScannedCode.AreaTicket
        assertEquals("9470000015", resolved.code)
        assertEquals(47, resolved.partition)
        assertEquals(1L, resolved.counter)
    }

    @Test
    fun `an EAN-8 is a product`() {
        // 8 dígitos: ni siquiera entra al espacio de nombres del vale.
        assertEquals(ScannedCode.Product("96385074"), resolveScannedCode("96385074"))
    }

    @Test
    fun `ten digits starting with 9 with an invalid check digit is a product`() {
        // EL caso que evita el falso positivo: mismo largo, mismo prefijo, verificador que no
        // cuadra → producto, y el catálogo decide. Los dos códigos difieren en UN dígito.
        assertTrue(resolveScannedCode("9012345673") is ScannedCode.AreaTicket) // verificador ok
        assertEquals(ScannedCode.Product("9012345670"), resolveScannedCode("9012345670"))

        // Ojo: "9000000015" aparece en el brief como ejemplo de vale válido, pero bajo mod-10 GS1
        // el verificador de "900000001" es 0, no 5 (ver el test de arriba, calculado a mano). Con
        // este algoritmo el código correcto es "9000000010" y "9000000015" es producto.
        assertEquals(ScannedCode.Product("9000000015"), resolveScannedCode("9000000015"))
    }

    @Test
    fun `UPC-A and EAN-13 are products even when they start with 9`() {
        assertTrue(resolveScannedCode("036000291452") is ScannedCode.Product) // UPC-A, 12
        assertTrue(resolveScannedCode("9781861972712") is ScannedCode.Product) // EAN-13, 13
    }

    @Test
    fun `ten digits with a valid check digit but no 9 prefix is a product`() {
        // El prefijo es el que reserva el espacio de nombres; sin él, es catálogo.
        assertTrue(isAreaTicketCode("9470000015"))
        assertEquals(ScannedCode.Product("1470000019"), resolveScannedCode("1470000019"))
    }

    @Test
    fun `alphanumeric SKUs and empty input are products`() {
        assertEquals(ScannedCode.Product("9ABCDEFGHI"), resolveScannedCode("9ABCDEFGHI"))
        assertEquals(ScannedCode.Product("VPZ1617070"), resolveScannedCode("VPZ1617070"))
        assertEquals(ScannedCode.Product(""), resolveScannedCode(""))
    }

    @Test
    fun `trailing carriage return from an HID scanner gun still resolves`() {
        val resolved = resolveScannedCode("9470000015\r\n")
        assertTrue(resolved is ScannedCode.AreaTicket)
        assertEquals("9470000015", (resolved as ScannedCode.AreaTicket).code)
    }

    // MARK: - Contador monótono

    @Test
    fun `a thousand consecutive mints never repeat and never go backwards`() {
        val store = storeWith(partition = 47)
        val seen = mutableSetOf<String>()
        var previous = 0L
        repeat(1_000) {
            val mint = store.mintNext() as AreaTicketMint.Minted
            assertTrue("El contador retrocedió: ${mint.counter} <= $previous", mint.counter > previous)
            assertTrue("Código repetido: ${mint.code}", seen.add(mint.code))
            previous = mint.counter
        }
        assertEquals(1_000, seen.size)
        assertEquals(1_000L, store.counter)
    }

    @Test
    fun `the first minted counter is 1`() {
        val store = storeWith(partition = 47)
        assertEquals("9470000015", (store.mintNext() as AreaTicketMint.Minted).code)
    }

    @Test
    fun `two different partitions never produce the same code`() {
        val a = storeWith(partition = 10)
        val b = storeWith(partition = 11)
        val codesA = (1..500).map { (a.mintNext() as AreaTicketMint.Minted).code }.toSet()
        val codesB = (1..500).map { (b.mintNext() as AreaTicketMint.Minted).code }.toSet()
        assertTrue("Particiones distintas colisionaron", codesA.intersect(codesB).isEmpty())

        // Y a nivel formato: el MISMO contador en las 90 particiones da 90 códigos distintos.
        val sameCounter = (MIN_AREA_TICKET_PARTITION..MAX_AREA_TICKET_PARTITION)
            .map { buildAreaTicketCode(it, counter = 7) }
        assertEquals(90, sameCounter.toSet().size)
    }

    @Test
    fun `concurrent mints from many threads never hand out the same code`() {
        // El fake abre a propósito la ventana entre leer y escribir (Thread.yield): sin el lock
        // de mintNext, dos hilos leerían el mismo contador y acuñarían el mismo vale.
        val store = storeWith(partition = 47, raceWindow = true)
        val threads = 8
        val perThread = 125
        val codes = ConcurrentHashMap.newKeySet<String>()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        repeat(threads) {
            pool.execute {
                start.await()
                repeat(perThread) {
                    val mint = store.mintNext()
                    if (mint is AreaTicketMint.Minted) codes.add(mint.code)
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue("Los hilos no terminaron a tiempo", pool.awaitTermination(30, TimeUnit.SECONDS))

        assertEquals(threads * perThread, codes.size)
        assertEquals((threads * perThread).toLong(), store.counter)
    }

    // MARK: - Límite de la partición

    @Test
    fun `the last code of the partition is minted and the next one is refused`() {
        val storage = FakeStorage(partition = 99, counter = MAX_AREA_TICKET_COUNTER - 1)
        val store = AreaTicketCodeStore(storage)

        val last = store.mintNext()
        assertTrue(last is AreaTicketMint.Minted)
        assertEquals("9999999999", (last as AreaTicketMint.Minted).code)
        assertEquals(MAX_AREA_TICKET_COUNTER, last.counter)
        assertEquals(0L, store.remainingCodes)

        // Agotada: se rechaza EXPLÍCITO, no se envuelve a 0 (envolver reviviría códigos de vales
        // que pueden seguir vivos) y no se desborda a 7 dígitos.
        assertEquals(AreaTicketMint.PartitionExhausted, store.mintNext())
        assertEquals(AreaTicketMint.PartitionExhausted, store.mintNext())
        assertEquals(MAX_AREA_TICKET_COUNTER, store.counter)
    }

    @Test
    fun `remaining codes counts down as it mints`() {
        val store = storeWith(partition = 47)
        assertEquals(MAX_AREA_TICKET_COUNTER, store.remainingCodes)
        store.mintNext()
        assertEquals(MAX_AREA_TICKET_COUNTER - 1, store.remainingCodes)
    }

    // MARK: - Partición: sin ella no se inventa nada

    @Test
    fun `without a cached partition minting fails explicitly`() {
        val store = AreaTicketCodeStore(FakeStorage(partition = null))
        assertEquals(AreaTicketMint.MissingPartition, store.mintNext())
    }

    @Test
    fun `a corrupt cached partition is treated as missing`() {
        // Preferimos pedir partición al server que reventar en medio de una venta.
        assertEquals(AreaTicketMint.MissingPartition, AreaTicketCodeStore(FakeStorage(partition = 0)).mintNext())
        assertEquals(AreaTicketMint.MissingPartition, AreaTicketCodeStore(FakeStorage(partition = 999)).mintNext())
    }

    @Test
    fun `re-login with the same partition keeps the counter`() {
        val store = storeWith(partition = 47)
        repeat(3) { store.mintNext() }
        assertTrue(store.setPartition(47))
        assertEquals(3L, store.counter)
        assertEquals(4L, (store.mintNext() as AreaTicketMint.Minted).counter)
    }

    @Test
    fun `a new partition starts its own counter`() {
        val store = storeWith(partition = 47)
        repeat(3) { store.mintNext() }
        assertTrue(store.setPartition(48))
        assertEquals(0L, store.counter)
        val mint = store.mintNext() as AreaTicketMint.Minted
        assertEquals(48, mint.partition)
        assertEquals(1L, mint.counter)
        // Espacio de nombres nuevo: no puede chocar con lo de la partición 47.
        assertNotEquals(buildAreaTicketCode(47, 1), mint.code)
    }

    @Test
    fun `an out of range partition from the server is rejected`() {
        val store = AreaTicketCodeStore(FakeStorage(partition = null))
        assertFalse(store.setPartition(9))
        assertFalse(store.setPartition(100))
        assertEquals(AreaTicketMint.MissingPartition, store.mintNext())
    }

    // MARK: - lastCounter del server (§5.2) — se toma el MÁXIMO, nunca el menor

    /**
     * El caso que motiva todo esto: `allowBackup=false` borra el contador al reinstalar.
     * Si el server devuelve la misma partición y arrancáramos de 0, el siguiente vale
     * repetiría un código YA IMPRESO y en manos de un cliente.
     */
    @Test
    fun `reinstall recovers the counter from the server`() {
        val store = AreaTicketCodeStore(FakeStorage(partition = null)) // disco borrado
        assertTrue(store.setPartition(47, serverLastCounter = 500L))
        assertEquals(500L, store.counter)
        assertEquals(501L, (store.mintNext() as AreaTicketMint.Minted).counter)
    }

    /**
     * El inverso: los vales se acuñan sin pedirle permiso al server, así que su
     * `lastCounter` puede ir ATRÁS del nuestro. Hacerle caso reacuñaría esos códigos.
     */
    @Test
    fun `a stale server counter never rewinds the local one`() {
        val store = storeWith(partition = 47)
        repeat(500) { store.mintNext() }
        assertTrue(store.setPartition(47, serverLastCounter = 300L))
        assertEquals(500L, store.counter)
        assertEquals(501L, (store.mintNext() as AreaTicketMint.Minted).counter)
    }

    @Test
    fun `a different partition takes the server counter and ignores the local one`() {
        val store = storeWith(partition = 47)
        repeat(900) { store.mintNext() }
        assertTrue(store.setPartition(48, serverLastCounter = 12L))
        assertEquals(12L, store.counter)
        val mint = store.mintNext() as AreaTicketMint.Minted
        assertEquals(48, mint.partition)
        assertEquals(13L, mint.counter)
    }

    @Test
    fun `an absurd server counter is clamped instead of bricking the device`() {
        val store = AreaTicketCodeStore(FakeStorage(partition = null))
        assertTrue(store.setPartition(47, serverLastCounter = Long.MAX_VALUE))
        assertEquals(MAX_AREA_TICKET_COUNTER, store.counter)
        // Queda agotado, que es correcto — pero no explota ni acuña un código inválido.
        assertEquals(AreaTicketMint.PartitionExhausted, store.mintNext())
    }

    @Test
    fun `a negative server counter is treated as zero`() {
        val store = AreaTicketCodeStore(FakeStorage(partition = null))
        assertTrue(store.setPartition(47, serverLastCounter = -99L))
        assertEquals(0L, store.counter)
        assertEquals(1L, (store.mintNext() as AreaTicketMint.Minted).counter)
    }

    // MARK: - Durabilidad: primero se graba, después se entrega

    @Test
    fun `a failed write hands out no code and burns no counter`() {
        val storage = FakeStorage(partition = 47)
        val store = AreaTicketCodeStore(storage)
        assertEquals("9470000015", (store.mintNext() as AreaTicketMint.Minted).code)

        storage.writesFail = true
        assertEquals(AreaTicketMint.PersistFailed, store.mintNext())
        assertEquals(1L, store.counter) // no avanzó: nada que quedara sin grabar

        // Al recuperarse, el siguiente vale toma el contador que no llegó a salir. Ni duplicado
        // (nadie recibió el 2) ni contador adelantado respecto de lo persistido.
        storage.writesFail = false
        assertEquals(2L, (store.mintNext() as AreaTicketMint.Minted).counter)
    }

    @Test
    fun `a code is persisted before it is returned`() {
        // Reservar → persistir → entregar. Si el proceso muriera entre grabar y devolver, ese
        // código simplemente nunca existió; al revés, el vale ya estaría impreso y en manos del
        // cliente cuando se pierde el contador.
        val storage = FakeStorage(partition = 47)
        val store = AreaTicketCodeStore(storage)
        val mint = store.mintNext() as AreaTicketMint.Minted
        assertEquals(mint.counter, storage.persistedCounter)
        assertEquals(1, storage.durableWrites)
    }

    // MARK: - Helpers

    private fun storeWith(partition: Int, raceWindow: Boolean = false) =
        AreaTicketCodeStore(FakeStorage(partition = partition, raceWindow = raceWindow))

    /**
     * Almacenamiento en memoria. Campos SIN sincronizar a propósito: si [AreaTicketCodeStore]
     * perdiera su lock, el test de concurrencia falla.
     */
    private class FakeStorage(
        private var partition: Int? = null,
        counter: Long = 0L,
        private val raceWindow: Boolean = false,
    ) : AreaTicketCodeStorage {
        var persistedCounter: Long = counter
            private set
        var writesFail = false
        var durableWrites = 0
            private set

        override fun readPartition(): Int? = partition

        override fun readCounter(): Long {
            if (raceWindow) Thread.yield() // ensancha la ventana read-modify-write
            return persistedCounter
        }

        override fun writeDurably(partition: Int, counter: Long): Boolean {
            if (writesFail) return false
            this.partition = partition
            persistedCounter = counter
            durableWrites++
            return true
        }
    }
}
