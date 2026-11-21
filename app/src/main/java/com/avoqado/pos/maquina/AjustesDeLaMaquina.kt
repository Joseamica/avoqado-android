package com.avoqado.pos.maquina

import android.content.Context
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.core.data.network.ForbiddenInterceptor
import com.avoqado.pos.core.data.network.ServerErrorText
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

// MARK: - Textos de la hoja «Máquina de efectivo» (C6)

const val TEXTO_IP_INVALIDA = "Escribe la IP como 192.168.1.50"
const val TEXTO_PENDIENTES_DE_LA_MAQUINA = "Hay cobros de la máquina sin subir o en duda: resuélvelos primero"
/** R5#10: lo mismo dice la fila apagada en Ajustes y el 403 del SERVIDOR. Sin PIN de gerente en la acción: se entra con el suyo. */
const val TEXTO_SIN_PERMISO = "Pide a un gerente que entre con su PIN (cambio de usuario) para configurar la máquina"
const val TEXTO_SIN_RED_EMPAREJAR = "Se necesita internet para emparejar"
const val TEXTO_SIN_RED_DESEMPAREJAR = "Se necesita internet para desemparejar"

/**
 * La IP de la máquina como la acepta el SERVIDOR (`z.ip({ version: 'v4' })`): cuatro números de 0 a 255 con dígitos ASCII.
 * Un cero a la izquierda («01») se rechaza aunque el SERVIDOR lo aceptara: no se sabe si alguien quiso escribir octal.
 */
fun esIpv4(texto: String): Boolean {
    val partes = texto.trim().split('.')
    return partes.size == 4 && partes.all { p ->
        p.length in 1..3 && p.all { it in '0'..'9' } && !(p.length > 1 && p[0] == '0') && p.toInt() <= 255
    }
}

/** Lo que ESTA PC recuerda de su máquina. En WINDOWS vive en la carpeta de datos (`shared_prefs/`). */
@Singleton
class MaquinaDeEfectivoPrefs @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(NOMBRE, Context.MODE_PRIVATE)

    val ip: String? get() = prefs.getString(IP, null)

    /** Emparejada con el SERVIDOR (R4-3). La credencial vive aparte, en [BovedaDeLaMaquina]. */
    val activa: Boolean get() = prefs.getBoolean(ACTIVA, false)

    /** Codex código r1 #10: la sucursal con la que se emparejó. La máquina sólo cobra con la sesión en ESA sucursal. */
    val venueId: String? get() = prefs.getString(SUCURSAL, null)

    /** Su nombre, para decir «Esta PC está emparejada con <sucursal>» desde otra. */
    val nombreDeLaSucursal: String? get() = prefs.getString(NOMBRE_DE_LA_SUCURSAL, null)

    fun guardar(ip: String?, activa: Boolean, venueId: String? = this.venueId, nombreDeLaSucursal: String? = this.nombreDeLaSucursal) {
        check(
            prefs.edit().putString(IP, ip).putBoolean(ACTIVA, activa).putString(SUCURSAL, venueId)
                .putString(NOMBRE_DE_LA_SUCURSAL, nombreDeLaSucursal).commit(),
        ) { "No se pudo guardar el ajuste de la máquina" }
    }

    /** Codex código r1 #10: emparejada, y con la sesión en la MISMA sucursal del emparejamiento. */
    fun activaEn(venueIdDeLaSesion: String?): Boolean = activa && venueId != null && venueId == venueIdDeLaSesion

    companion object {
        const val NOMBRE = "avoqado_maquina_efectivo"
        private const val IP = "ip"
        private const val ACTIVA = "activa"
        private const val SUCURSAL = "sucursal"
        private const val NOMBRE_DE_LA_SUCURSAL = "sucursal_nombre"
    }
}

/**
 * La credencial que el SERVIDOR entrega UNA vez al emparejar (R5#3) y que viaja en `X-Cash-Machine-Credential`. En WINDOWS se
 * cifra con DPAPI de MÁQUINA (`desktop/`, `MaquinaModule`): una copia del perfil en otra PC no la abre y [leer] da null.
 */
interface BovedaDeLaMaquina {
    fun guardar(credencial: String)
    /** null = no hay, o esta PC no la puede descifrar (perfil copiado de otra). */
    fun leer(): String?
    fun borrar()
}

/**
 * «Probar conexión»: true si en [plazoMs] llega el `sockets` de la máquina. Abre su PROPIA conexión y la cierra al terminar;
 * [okHttp] debe ser uno sin los interceptores de la API (no se le manda la sesión a un aparato de la red local).
 */
suspend fun probarConexion(ip: String, okHttp: OkHttpClient, puerto: Int = 8080, plazoMs: Long = 5_000): Boolean = coroutineScope {
    val cliente = ClienteDeLaMaquina(okHttp, this, System::currentTimeMillis)
    // Se suscribe ANTES de conectar: `recibidos` no repite lo que ya pasó.
    val sockets = async(start = CoroutineStart.UNDISPATCHED) { cliente.recibidos.first { it.mensaje is MensajeDeLaMaquina.Sockets } }
    try {
        cliente.conectar(ip, puerto)
        withTimeoutOrNull(plazoMs) { sockets.await() } != null
    } finally {
        cliente.desconectar()
        sockets.cancel()
    }
}

/** Lo que deja un emparejamiento: la credencial (DPAPI) y la sucursal en que vale (Codex código r1 #10). */
data class Emparejado(val credencial: String, val venueId: String, val nombreDeLaSucursal: String?)

/**
 * `POST|DELETE /mobile/venues/:venueId/cash-machine/binding` (R4-3). ONLINE-ONLY a propósito: el SERVIDOR revoca y entrega la
 * credencial; nada se encola. El `X-Device-ID` lo pone `DeviceHeadersInterceptor` en el cliente de la app. Una falla de red
 * llega como [IOException]; cualquier otra, con el texto para la hoja.
 */
@Singleton
class EmparejamientoDeLaMaquina @Inject constructor(
    private val secureStorage: SecureStorage,
    private val client: OkHttpClient,
) {
    /** Seam de pruebas: apuntar a un MockWebServer. */
    internal var baseUrl: String = ApiConstants.BASE_URL

    /** La sucursal de la sesión (con la que se empareja, Codex código r1 #10). */
    val sucursalDeLaSesion: String? get() = secureStorage.venueId

    /** La credencial (sale sólo aquí; nunca a la bitácora) y la sucursal en que se emparejó (Codex código r1 #10). */
    suspend fun emparejar(machineIp: String): Result<Emparejado> {
        val venueId = secureStorage.venueId
        val nombre = secureStorage.venueName
        return llamar(Request.Builder().post(JSONObject().put("machineIp", machineIp).toString().toRequestBody(JSON))) { cuerpo ->
            val credencial = JSONObject(cuerpo).optString("credential").takeIf { it.isNotBlank() } ?: error("El servidor no mandó la credencial")
            Emparejado(credencial, requireNotNull(venueId), nombre)
        }
    }

    suspend fun desemparejar(): Result<Unit> = llamar(Request.Builder().delete()) {}

    private suspend fun <T> llamar(pedido: Request.Builder, leer: (String) -> T): Result<T> = withContext(Dispatchers.IO) {
        val venueId = secureStorage.venueId ?: return@withContext Result.failure(IllegalStateException("Sin sucursal activa"))
        val request = pedido.url("$baseUrl/mobile/venues/$venueId/cash-machine/binding")
            // R5#10: el 403 lo explica la hoja; nunca el diálogo de PIN de gerente del ForbiddenInterceptor.
            .header(ForbiddenInterceptor.LOCAL_ERROR_HEADER, "1")
            .build()
        try {
            client.newCall(request).execute().use { r ->
                val cuerpo = r.body?.string().orEmpty()
                when {
                    r.isSuccessful -> runCatching { leer(cuerpo) }
                    r.code == 403 -> Result.failure(IllegalStateException(TEXTO_SIN_PERMISO))
                    else -> Result.failure(IllegalStateException(ServerErrorText.fromResponseBody(cuerpo, "No se pudo. Inténtalo de nuevo.")))
                }
            }
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
