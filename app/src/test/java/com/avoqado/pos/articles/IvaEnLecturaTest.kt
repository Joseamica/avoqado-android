package com.avoqado.pos.articles

import com.avoqado.pos.articles.data.model.ArticleProduct
import com.avoqado.pos.articles.data.model.IvaEnLectura
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** IVA por producto, plan 7 (D2): la app MUESTRA el IVA y no lo edita. Nunca adivina «16 %». Espejo de iOS. */
class IvaEnLecturaTest {

    @Test
    fun `P1 cada tratamiento dice su IVA y nunca adivina 16`() {
        assertEquals("IVA: 16 %", IvaEnLectura.titulo("IVA_16"))
        assertEquals("IVA: 8 %", IvaEnLectura.titulo("IVA_8"))
        assertEquals("IVA: 0 %", IvaEnLectura.titulo("IVA_0"))
        assertEquals("IVA: Exento", IvaEnLectura.titulo("EXENTO"))
        assertEquals("IVA: No objeto de impuesto", IvaEnLectura.titulo("NO_OBJETO"))
        assertEquals("IVA: Por revisar", IvaEnLectura.titulo("BLOQUEADO_03"))
        assertEquals("IVA: Por revisar", IvaEnLectura.titulo("BLOQUEADO_04"))
        assertEquals("IVA: consúltalo en el dashboard", IvaEnLectura.titulo(null))
        assertEquals("IVA: consúltalo en el dashboard", IvaEnLectura.titulo("IVA_FUTURO"))
        assertEquals("Se cambia en el dashboard", IvaEnLectura.AYUDA)
        assertEquals("IVA: 16 %", IvaEnLectura.TITULO_ALTA)
    }

    @Test
    fun `el producto del servidor trae ivaTratamiento y un servidor viejo no rompe`() {
        val json = Json { ignoreUnknownKeys = true }
        assertEquals("IVA_0", json.decodeFromString<ArticleProduct>("""{"id":"p1","name":"Café en grano","ivaTratamiento":"IVA_0"}""").ivaTratamiento)
        assertNull(json.decodeFromString<ArticleProduct>("""{"id":"p1","name":"Café"}""").ivaTratamiento)
    }
}
