package com.avoqado.pos.auth.presentation

import androidx.fragment.app.FragmentActivity
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.auth.data.AuthRepository
import com.avoqado.pos.auth.data.BiometricAuthManager
import com.avoqado.pos.auth.data.model.LoginResult
import com.avoqado.pos.core.data.local.SecureStorage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.lang.reflect.Field
import java.util.regex.Pattern

/**
 * Defecto de producción (2.25.8, D3, 20-oct): entrar con contraseña → cerrar sesión → «Iniciar sesión» sin cerrar la
 * app mostraba el paso de contraseña con la contraseña ANTERIOR y el botón con la ruedita, deshabilitado.
 *
 * El ViewModel no muere al entrar: `LandingScreen` no vive en un NavHost, así que `hiltViewModel()` lo cuelga de la
 * Activity y la MISMA instancia se vuelve a pintar tras cerrar sesión. Por eso estas pruebas miran el estado de esa
 * instancia después del éxito: es exactamente lo que se ve al volver.
 */
class SignInViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val authRepository = mockk<AuthRepository>()
    private val biometricAuthManager = mockk<BiometricAuthManager>(relaxed = true)
    private val secureStorage = mockk<SecureStorage>(relaxed = true)

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

    @Test
    fun `tras entrar con contraseña, volver a la pantalla no queda cargando ni con la contraseña anterior`() {
        coEvery { authRepository.loginWithEmail(any(), any(), any()) } returns
            LoginResult.Success("u1", "cajera@avoqado.io", "Ana", null, emptyList())
        val viewModel = SignInViewModel(authRepository, biometricAuthManager, secureStorage)

        viewModel.onEmailChange("cajera@avoqado.io")
        viewModel.goToPassword()
        viewModel.onPasswordChange("contraseña-1")
        var entro = false
        viewModel.loginWithPassword { entro = true }

        assertTrue("el login debía terminar con éxito", entro)
        val alVolver = viewModel.uiState.value
        assertFalse("la ruedita no debe seguir girando", alVolver.isLoading)
        assertEquals("la contraseña anterior no debe quedar escrita", "", alVolver.password)
        assertEquals("debe arrancar en el paso del correo, como al abrir la app", SignInStep.EMAIL, alVolver.step)
    }

    @Test
    fun `tras entrar con huella, volver a la pantalla no queda cargando`() {
        every { secureStorage.isBiometricLoginEnabled } returns true
        every { secureStorage.biometricRefreshToken } returns "refresh-1"
        every { secureStorage.biometricUserEmail } returns "cajera@avoqado.io"
        every { secureStorage.biometricVenuesList } returns emptyList()
        coEvery { biometricAuthManager.authenticate(any()) } returns true
        coEvery { authRepository.entrarConHuella("refresh-1", any(), any()) } returns true
        val viewModel = SignInViewModel(authRepository, biometricAuthManager, secureStorage)

        var entro = false
        viewModel.loginWithBiometric(mockk<FragmentActivity>(relaxed = true)) { entro = true }

        assertTrue("el login con huella debía terminar con éxito", entro)
        val alVolver = viewModel.uiState.value
        assertFalse("la ruedita no debe seguir girando", alVolver.isLoading)
        assertTrue("la huella sigue disponible para la siguiente entrada", alVolver.canUseBiometric)
    }

    @Test
    fun `D4 H2-d una huella cuya respuesta llega despues de que entro otra persona no entra ni borra la huella`() {
        every { secureStorage.isBiometricLoginEnabled } returns true
        every { secureStorage.biometricRefreshToken } returns "refresh-1"
        every { secureStorage.biometricVenuesList } returns emptyList()
        coEvery { biometricAuthManager.authenticate(any()) } returns true
        coEvery { authRepository.entrarConHuella("refresh-1", any(), any()) } returns false // ya entró otra persona
        val viewModel = SignInViewModel(authRepository, biometricAuthManager, secureStorage)

        var entro = false
        viewModel.loginWithBiometric(mockk<FragmentActivity>(relaxed = true)) { entro = true }

        assertFalse("no navega como si hubiera entrado", entro)
        assertEquals(AuthRepository.OTRA_PERSONA_ENTRO, viewModel.uiState.value.errorMessage)
        verify(exactly = 0) { secureStorage.clearBiometricCredentials() }
        verify(exactly = 0) { secureStorage.clearBiometricCredentialsSiSigue(any()) }
    }
}
