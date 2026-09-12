package com.avoqado.pos.tables

import com.avoqado.pos.core.data.network.ServerErrorText
import com.avoqado.pos.payment.domain.CancelacionDeCobro
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * «Anular cuenta», anular en masa y fusionar contra una cuenta con un cobro de tarjeta VIVO.
 *
 * El servidor contesta 409 `ORDER_CANCEL_BLOCKED_BY_TERMINAL_CHARGE` (diseño §C.6). Sin leer el
 * `code`, el mesero veía «HTTP 409» o el texto crudo del servidor y no sabía qué hacer; lo que
 * necesita es el siguiente movimiento: cancelar el cobro o esperar a que termine.
 */
class CobroVivoBloqueaAnularTest {

    private fun http(codigo: Int, cuerpo: String) = HttpException(
        Response.error<Any>(codigo, cuerpo.toResponseBody("application/json".toMediaType())),
    )

    @Test
    fun `P1 un 409 por cobro vivo dice qué hacer, no el error crudo`() {
        val error = http(
            409,
            """{"message":"Hay un cobro en curso en la terminal para esta orden. Cancela o espera el resultado del cobro antes de cancelar la orden.",
                "code":"ORDER_CANCEL_BLOCKED_BY_TERMINAL_CHARGE","details":{"requestId":"req-1"}}""",
        )

        assertEquals(
            CancelacionDeCobro.COBRO_EN_CURSO_BLOQUEA_CUENTA,
            ServerErrorText.humanizeCancelacionDeCuenta(error, "No se pudo anular la cuenta"),
        )
    }

    @Test
    fun `cualquier otro rechazo conserva el motivo del servidor`() {
        val error = http(400, """{"message":"Esta cuenta ya tiene pagos registrados. Reembólsalos antes de cancelarla."}""")

        assertEquals(
            "Esta cuenta ya tiene pagos registrados. Reembólsalos antes de cancelarla.",
            ServerErrorText.humanizeCancelacionDeCuenta(error, "No se pudo anular la cuenta"),
        )
    }

    @Test
    fun `sin mensaje del servidor se usa el respaldo de quien llama`() {
        assertEquals(
            "No se pudo anular la cuenta",
            ServerErrorText.humanizeCancelacionDeCuenta(http(500, ""), "No se pudo anular la cuenta"),
        )
    }

    @Test
    fun `un fallo de red se sigue leyendo como falta de conexion`() {
        assertEquals(
            "Sin conexión. Revisa la red e inténtalo de nuevo.",
            ServerErrorText.humanizeCancelacionDeCuenta(java.net.UnknownHostException("no dns"), "No se pudo anular la cuenta"),
        )
    }
}
