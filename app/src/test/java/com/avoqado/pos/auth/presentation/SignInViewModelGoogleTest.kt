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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import java.lang.reflect.Field
import java.util.regex.Pattern
import org.junit.Test

/** Entrar con Google en el POS: un solo intento vivo entre Google, contraseña y huella (hz:6bfcd3a1:D1). */
class SignInViewModelGoogleTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val despachador = UnconfinedTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(despachador)

    private val authRepository = mockk<AuthRepository>()
    private val biometricAuthManager = mockk<BiometricAuthManager>(relaxed = true)
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val entrarConGoogle = mockk<EntrarConGoogle>(relaxed = true)
    private val connectivityMonitor = mockk<ConnectivityMonitor>(relaxed = true)
    private val activity = mockk<FragmentActivity>(relaxed = true)
    private val config = GoogleConfigResponse(success = true, android = true, androidAudience = "aud", nonce = "n")
    private val exito = LoginResult.Success("u1", "cajera@avoqado.io", "Ana", null, emptyList())

    @Before
    fun patronDeCorreo() {
        // El android.jar de las pruebas JVM trae `Patterns.EMAIL_ADDRESS` en null (su `static {}` está vacío) y es
        // `static final`: se le pone el patrón real de AOSP para que `isEmailValid` funcione como en el aparato.
        // `sun.misc.Unsafe` va por reflexión porque el classpath de compilación es el de Android, que no lo trae.
        val campo = android.util.Patterns::class.java.getField("EMAIL_ADDRESS")
        if (campo.get(null) != null) return
        val patron = Pattern.compile(
            "[a-zA-Z0-9+._%\\-]{1,256}@[a-zA-Z0-9][a-zA-Z0-9\\-]{0,64}(\\.[a-zA-Z0-9][a-zA-Z0-9\\-]{0,25})+",
        )
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val base = unsafeClass.getMethod("staticFieldBase", Field::class.java).invoke(unsafe, campo)
        val offset = unsafeClass.getMethod("staticFieldOffset", Field::class.java).invoke(unsafe, campo) as Long
        unsafeClass.getMethod("putObject", Any::class.java, Long::class.javaPrimitiveType, Any::class.java)
            .invoke(unsafe, base, offset, patron)
    }

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
    fun `cancelarGoogle durante pedirPase no llama al repositorio ni entra y no deja error`() {
        configLista()
        val espera = CompletableDeferred<PaseDeGoogle>()
        coEvery { entrarConGoogle.pedirPase(any(), any()) } coAnswers { espera.await() }
        coEvery { authRepository.loginWithGoogle(any(), any(), any()) } returns exito
        val vm = vm()

        var entro = false
        vm.loginWithGoogle(activity) { entro = true }
        vm.cancelarGoogle()
        espera.complete(PaseDeGoogle.Token("t")) // el pase llega tarde

        verify(exactly = 1) { entrarConGoogle.cancelar() }
        coVerify(exactly = 0) { authRepository.loginWithGoogle(any(), any(), any()) }
        assertFalse(entro)
        val estado = vm.uiState.value
        assertNull(estado.errorMessage)
        assertFalse(estado.isLoading)
        assertFalse(estado.esperandoNavegador)
    }

    @Test
    fun `cancelarGoogle con la contraseña en camino no la corta ni apaga isLoading`() {
        val respuesta = CompletableDeferred<LoginResult>()
        coEvery { authRepository.loginWithEmail(any(), any(), any()) } coAnswers { respuesta.await() }
        val vm = vm()
        vm.onEmailChange("cajera@avoqado.io")
        vm.onPasswordChange("contraseña-1")

        var entro = false
        vm.loginWithPassword { entro = true }
        vm.cancelarGoogle()

        assertTrue(vm.uiState.value.isLoading)
        verify(exactly = 0) { entrarConGoogle.cancelar() }
        respuesta.complete(exito)
        assertTrue("la contraseña sigue su curso", entro)
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
        coEvery { authRepository.configDeGoogle() } returns ConfigDeGoogle.NoDisponible()
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
    fun `Fallo con red y la sonda al servidor contesta muestra su texto`() {
        configLista()
        every { connectivityMonitor.isOnline() } returns true
        coEvery { entrarConGoogle.pedirPase(any(), any()) } returns PaseDeGoogle.Fallo("texto de Google", "causa")
        val vm = vm()

        vm.loginWithGoogle(activity) {}

        assertEquals("texto de Google", vm.uiState.value.errorMessage)
        assertFalse(vm.uiState.value.isLoading)
        coVerify(exactly = 2) { authRepository.configDeGoogle() } // la config y la sonda
    }

    @Test
    fun `Fallo con Wi-Fi sin internet (la sonda dice SinRed) dice sin conexion`() {
        coEvery { authRepository.configDeGoogle() } returnsMany listOf(ConfigDeGoogle.Lista(config), ConfigDeGoogle.SinRed)
        every { connectivityMonitor.isOnline() } returns true // el monitor todavía ve la interfaz conectada
        coEvery { entrarConGoogle.pedirPase(any(), any()) } returns PaseDeGoogle.Fallo("no disponible", "causa")
        val vm = vm()

        vm.loginWithGoogle(activity) {}

        assertEquals(AuthRepository.SIN_CONEXION_PARA_GOOGLE, vm.uiState.value.errorMessage)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun `WINDOWS no sondea tras el Fallo y conserva su texto`() {
        coEvery { authRepository.configDeGoogle() } returnsMany listOf(ConfigDeGoogle.Lista(config), ConfigDeGoogle.SinRed)
        every { connectivityMonitor.isOnline() } returns true
        every { entrarConGoogle.abreNavegador } returns true
        val texto = "No recibimos respuesta de Google. Vuelve a intentarlo."
        coEvery { entrarConGoogle.pedirPase(any(), any()) } returns PaseDeGoogle.Fallo(texto, "plazo")
        val vm = vm()

        vm.loginWithGoogle(activity) {}

        assertEquals(texto, vm.uiState.value.errorMessage)
        coVerify(exactly = 1) { authRepository.configDeGoogle() }
    }

    @Test
    fun `cancelarGoogle con el pase ya enviado no corta el login y entra`() {
        configLista()
        every { entrarConGoogle.abreNavegador } returns true
        coEvery { entrarConGoogle.pedirPase(any(), any()) } returns PaseDeGoogle.Codigo("c", "v", "http://127.0.0.1:5000")
        val respuesta = CompletableDeferred<LoginResult>()
        coEvery { authRepository.loginWithGoogle(any(), any(), any()) } coAnswers { respuesta.await() }
        val vm = vm()

        var entro = false
        vm.loginWithGoogle(activity) { entro = true }
        // El POST va en camino: ya no se espera al navegador (la pantalla quita «Cancelar»).
        assertTrue(vm.uiState.value.isLoading)
        assertFalse(vm.uiState.value.esperandoNavegador)
        vm.cancelarGoogle()
        respuesta.complete(exito)

        verify(exactly = 0) { entrarConGoogle.cancelar() }
        assertTrue("el login termina y entra", entro)
    }

    @Test
    fun `Siguiente con Google en curso no cambia de paso`() {
        configLista()
        coEvery { entrarConGoogle.pedirPase(any(), any()) } coAnswers { CompletableDeferred<PaseDeGoogle>().await() }
        val vm = vm()
        vm.onEmailChange("cajera@avoqado.io")

        vm.loginWithGoogle(activity) {}
        vm.goToPassword()

        assertEquals(SignInStep.EMAIL, vm.uiState.value.step)
        assertTrue(vm.uiState.value.isLoading)
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

    // --- Seguimiento r3 ---

    @Test
    fun `Atras con el pase ya enviado no corta ni sale, y el login entra`() {
        configLista()
        every { entrarConGoogle.abreNavegador } returns true
        coEvery { entrarConGoogle.pedirPase(any(), any()) } returns PaseDeGoogle.Codigo("c", "v", "http://127.0.0.1:5000")
        val respuesta = CompletableDeferred<LoginResult>()
        coEvery { authRepository.loginWithGoogle(any(), any(), any()) } coAnswers { respuesta.await() }
        val vm = vm()

        var entro = false
        var salio = false
        vm.loginWithGoogle(activity) { entro = true }
        assertTrue(vm.uiState.value.enviandoGoogle)
        vm.atras { salio = true }
        respuesta.complete(exito)

        assertFalse("Atrás no sale de la pantalla mientras viaja el pase", salio)
        verify(exactly = 0) { entrarConGoogle.cancelar() }
        assertTrue(entro)
    }

    @Test
    fun `Atras con el pase ya enviado y el servidor rechaza enseña el error en la pantalla`() {
        configLista()
        coEvery { entrarConGoogle.pedirPase(any(), any()) } returns PaseDeGoogle.Token("t")
        val respuesta = CompletableDeferred<LoginResult>()
        coEvery { authRepository.loginWithGoogle(any(), any(), any()) } coAnswers { respuesta.await() }
        val vm = vm()

        var salio = false
        vm.loginWithGoogle(activity) {}
        vm.atras { salio = true }
        respuesta.complete(LoginResult.Error("Vuelve a intentarlo."))

        assertFalse(salio)
        assertEquals("Vuelve a intentarlo.", vm.uiState.value.errorMessage)
        assertFalse(vm.uiState.value.isLoading)
        assertFalse(vm.uiState.value.enviandoGoogle)
    }

    @Test
    fun `Atras esperando el navegador cancela y sale como hoy`() {
        configLista()
        every { entrarConGoogle.abreNavegador } returns true
        coEvery { entrarConGoogle.pedirPase(any(), any()) } coAnswers { CompletableDeferred<PaseDeGoogle>().await() }
        val vm = vm()

        var salio = false
        vm.loginWithGoogle(activity) {}
        vm.atras { salio = true }

        verify(exactly = 1) { entrarConGoogle.cancelar() }
        assertTrue(salio)
        assertFalse(vm.uiState.value.isLoading)
        coVerify(exactly = 0) { authRepository.loginWithGoogle(any(), any(), any()) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `la sonda que nunca contesta se corta a los 5 s y dice sin conexion`() {
        var llamadas = 0
        coEvery { authRepository.configDeGoogle() } coAnswers {
            if (++llamadas == 1) ConfigDeGoogle.Lista(config) else awaitCancellation()
        }
        every { connectivityMonitor.isOnline() } returns true
        coEvery { entrarConGoogle.pedirPase(any(), any()) } returns PaseDeGoogle.Fallo("no disponible", "causa")
        val vm = vm()

        vm.loginWithGoogle(activity) {}
        despachador.scheduler.advanceTimeBy(4_999)
        despachador.scheduler.runCurrent()
        assertTrue("antes de los 5 s sigue esperando la sonda", vm.uiState.value.isLoading)
        despachador.scheduler.advanceTimeBy(2)
        despachador.scheduler.runCurrent()

        assertEquals(AuthRepository.SIN_CONEXION_PARA_GOOGLE, vm.uiState.value.errorMessage)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun `config 429 enseña el aviso del servidor`() {
        val texto = "Demasiados intentos. Espera un minuto y vuelve a intentarlo."
        coEvery { authRepository.configDeGoogle() } returns ConfigDeGoogle.NoDisponible(texto)
        val vm = vm()

        vm.loginWithGoogle(activity) {}

        assertEquals(texto, vm.uiState.value.errorMessage)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun `entrar 429 enseña el aviso del servidor`() {
        val texto = "Demasiados intentos. Espera un minuto y vuelve a intentarlo."
        configLista()
        coEvery { entrarConGoogle.pedirPase(any(), any()) } returns PaseDeGoogle.Token("t")
        coEvery { authRepository.loginWithGoogle(any(), any(), any()) } returns LoginResult.Error(texto)
        val vm = vm()

        vm.loginWithGoogle(activity) {}

        assertEquals(texto, vm.uiState.value.errorMessage)
    }
}

