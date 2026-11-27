package com.avoqado.pos.auth.presentation

import androidx.fragment.app.FragmentActivity
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.auth.data.AuthRepository
import com.avoqado.pos.auth.data.BiometricAuthManager
import com.avoqado.pos.auth.data.ConfigDeGoogle
import com.avoqado.pos.auth.data.EntrarConGoogle
import com.avoqado.pos.auth.data.model.GoogleConfigResponse
import com.avoqado.pos.auth.data.model.LoginResult
import com.avoqado.pos.auth.data.model.PaseDeGoogle
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.util.ConnectivityMonitor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Entrar con Google en el POS: un solo intento vivo entre Google, contraseña y huella (hz:6bfcd3a1:D1). */
class SignInViewModelGoogleTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val authRepository = mockk<AuthRepository>()
    private val biometricAuthManager = mockk<BiometricAuthManager>(relaxed = true)
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val entrarConGoogle = mockk<EntrarConGoogle>(relaxed = true)
    private val connectivityMonitor = mockk<ConnectivityMonitor>(relaxed = true)
    private val activity = mockk<FragmentActivity>(relaxed = true)
    private val config = GoogleConfigResponse(success = true, android = true, androidAudience = "aud", nonce = "n")
    private val exito = LoginResult.Success("u1", "cajera@avoqado.io", "Ana", null, emptyList())

    private fun vm() = SignInViewModel(authRepository, biometricAuthManager, secureStorage, entrarConGoogle, connectivityMonitor)

    private fun configLista() {
        coEvery { authRepository.configDeGoogle() } returns ConfigDeGoogle.Lista(config)
    }

    @Test
    fun `dos toques seguidos piden un solo pase`() {
        configLista()
        val espera = CompletableDeferred<PaseDeGoogle>()
        coEvery { entrarConGoogle.pedirPase(any(), any()) } coAnswers { espera.await() }
        val vm = vm()

        vm.loginWithGoogle(activity) {}
        vm.loginWithGoogle(activity) {}

        coVerify(exactly = 1) { entrarConGoogle.pedirPase(any(), any()) }
        assertTrue(vm.uiState.value.isLoading)
    }

    @Test
    fun `con Google en curso la contraseña no arranca`() {
        configLista()
        coEvery { entrarConGoogle.pedirPase(any(), any()) } coAnswers { CompletableDeferred<PaseDeGoogle>().await() }
        coEvery { authRepository.loginWithEmail(any(), any(), any()) } returns exito
        val vm = vm()
        vm.onEmailChange("cajera@avoqado.io")
        vm.onPasswordChange("contraseña-1")

        vm.loginWithGoogle(activity) {}
        vm.loginWithPassword {}

        coVerify(exactly = 0) { authRepository.loginWithEmail(any(), any(), any()) }
    }

    @Test
    fun `cancelarGoogle durante pedirPase no llama al repositorio y no deja error`() {
        configLista()
        coEvery { entrarConGoogle.pedirPase(any(), any()) } coAnswers { CompletableDeferred<PaseDeGoogle>().await() }
        val vm = vm()

        vm.loginWithGoogle(activity) {}
        vm.cancelarGoogle()

        verify(exactly = 1) { entrarConGoogle.cancelar() }
        coVerify(exactly = 0) { authRepository.loginWithGoogle(any(), any(), any()) }
        val estado = vm.uiState.value
        assertNull(estado.errorMessage)
        assertFalse(estado.isLoading)
        assertFalse(estado.esperandoNavegador)
    }

    @Test
    fun `el sello es el del toque aunque otra entrada cambie la generacion mientras espera`() {
        configLista()
        every { secureStorage.generacion } returns 5L
        val espera = CompletableDeferred<PaseDeGoogle>()
        coEvery { entrarConGoogle.pedirPase(any(), any()) } coAnswers { espera.await() }
        coEvery { authRepository.loginWithGoogle(any(), any(), any()) } returns exito
        val vm = vm()

        vm.loginWithGoogle(activity) {}
        every { secureStorage.generacion } returns 6L
        espera.complete(PaseDeGoogle.Token("t"))

        coVerify { authRepository.loginWithGoogle(PaseDeGoogle.Token("t"), 5L, any()) }
    }

    @Test
    fun `Cancelado deja la pantalla sin error`() {
        configLista()
        coEvery { entrarConGoogle.pedirPase(any(), any()) } returns PaseDeGoogle.Cancelado
        val vm = vm()

        vm.loginWithGoogle(activity) {}

        assertNull(vm.uiState.value.errorMessage)
        assertFalse(vm.uiState.value.isLoading)
        coVerify(exactly = 0) { authRepository.loginWithGoogle(any(), any(), any()) }
    }

    @Test
    fun `config sin red dice sin conexion`() {
        coEvery { authRepository.configDeGoogle() } returns ConfigDeGoogle.SinRed
        val vm = vm()

        vm.loginWithGoogle(activity) {}

        assertEquals(AuthRepository.SIN_CONEXION_PARA_GOOGLE, vm.uiState.value.errorMessage)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun `config no disponible dice que el servidor no tiene Google`() {
        coEvery { authRepository.configDeGoogle() } returns ConfigDeGoogle.NoDisponible
        val vm = vm()

        vm.loginWithGoogle(activity) {}

        assertEquals(
            "Entrar con Google no está disponible por ahora. Entra con tu correo y contraseña.",
            vm.uiState.value.errorMessage,
        )
    }

    @Test
    fun `Fallo con el aparato sin red dice sin conexion`() {
        configLista()
        every { connectivityMonitor.isOnline() } returns false
        coEvery { entrarConGoogle.pedirPase(any(), any()) } returns PaseDeGoogle.Fallo("texto de Google", "causa")
        val vm = vm()

        vm.loginWithGoogle(activity) {}

        assertEquals(AuthRepository.SIN_CONEXION_PARA_GOOGLE, vm.uiState.value.errorMessage)
    }

    @Test
    fun `Fallo con red muestra su texto`() {
        configLista()
        every { connectivityMonitor.isOnline() } returns true
        coEvery { entrarConGoogle.pedirPase(any(), any()) } returns PaseDeGoogle.Fallo("texto de Google", "causa")
        val vm = vm()

        vm.loginWithGoogle(activity) {}

        assertEquals("texto de Google", vm.uiState.value.errorMessage)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun `exito llama onSuccess y deja el estado inicial`() {
        configLista()
        coEvery { entrarConGoogle.pedirPase(any(), any()) } returns PaseDeGoogle.Token("t")
        coEvery { authRepository.loginWithGoogle(any(), any(), any()) } returns exito
        val vm = vm()

        var entro = false
        vm.loginWithGoogle(activity) { entro = true }

        assertTrue(entro)
        assertEquals(SignInUiState(), vm.uiState.value)
    }

    @Test
    fun `un error del servidor al entrar se muestra`() {
        configLista()
        coEvery { entrarConGoogle.pedirPase(any(), any()) } returns PaseDeGoogle.Token("t")
        coEvery { authRepository.loginWithGoogle(any(), any(), any()) } returns LoginResult.Error("Vuelve a intentarlo.")
        val vm = vm()

        vm.loginWithGoogle(activity) {}

        assertEquals("Vuelve a intentarlo.", vm.uiState.value.errorMessage)
        assertFalse(vm.uiState.value.isLoading)
    }
}
