package com.avoqado.pos.auth.data

import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.NoCredentialException
import com.avoqado.pos.auth.data.model.PaseDeGoogle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Qué le decimos al cajero cuando el pase de Google no llega (hz:6bfcd3a1:D1). */
class EntrarConGoogleFallosTest {
    private val textoAparato = "Entrar con Google no está disponible en este aparato. Entra con tu correo y contraseña."

    @Test
    fun `cancelar la ventana de Google es Cancelado`() {
        assertEquals(PaseDeGoogle.Cancelado, traducirFallo(GetCredentialCancellationException()))
    }

    @Test
    fun `sin cuentas de Google en el aparato es Fallo con el texto del aparato`() {
        val r = traducirFallo(NoCredentialException()) as PaseDeGoogle.Fallo
        assertEquals(textoAparato, r.texto)
        assertTrue(r.causa.contains("NoCredentialException"))
    }

    @Test
    fun `aparato sin proveedor de credenciales es Fallo con el texto del aparato`() {
        val r = traducirFallo(GetCredentialProviderConfigurationException()) as PaseDeGoogle.Fallo
        assertEquals(textoAparato, r.texto)
    }
}
