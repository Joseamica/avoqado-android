package com.avoqado.pos.giftcards

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GiftCardsSinSecretosEnLogsTest {
    @Test fun `ningun log del paquete expone secretos o cuerpos`() {
        val raiz = File("src/main/java/com/avoqado/pos/giftcards").takeIf { it.isDirectory }
            ?: File("app/src/main/java/com/avoqado/pos/giftcards")
        assertTrue("el paquete debe existir", raiz.isDirectory)
        val peligrosos = raiz.walkTopDown().filter { it.extension == "kt" }.flatMap { file -> file.readLines().asSequence().filter { Regex("Log\\.[dewiv]\\(").containsMatchIn(it) && Regex("codigo|code|pageToken|proofToken|cuerpo|body").containsMatchIn(it) } }.toList()
        assertTrue(peligrosos.joinToString(), peligrosos.isEmpty())
    }
}
