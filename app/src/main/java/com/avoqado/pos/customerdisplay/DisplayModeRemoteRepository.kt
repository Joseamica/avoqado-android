package com.avoqado.pos.customerdisplay

import com.avoqado.pos.core.data.network.ForbiddenInterceptor
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Resultado estable de la capa HTTP. Ninguna excepción de red, HTTP o decode
 * cruza este límite; la cancelación de la coroutine sí se conserva.
 */
sealed interface DisplayModeRemoteOutcome<out T> {
    data class Success<T>(val data: T) : DisplayModeRemoteOutcome<T>

    /** El binding sigue siendo necesario aunque el server no tenga intención activa. */
    data class NoRequest(val terminalId: String) : DisplayModeRemoteOutcome<Nothing>

    data class Retryable(val status: Int? = null) : DisplayModeRemoteOutcome<Nothing>

    data class Rejected(
        val status: Int,
        val code: String?,
        val message: String?,
    ) : DisplayModeRemoteOutcome<Nothing>

    data object SessionInvalid : DisplayModeRemoteOutcome<Nothing>
}

/** GET exitoso con una solicitud activa y su terminal server-bound. */
data class DisplayModeRequestBinding(
    val terminalId: String,
    val request: RemoteDisplayModeRequest,
)

/** Estado tipado que evita construir un ACK REJECTED sin resultCode. */
sealed interface DisplayModeAcknowledgement {
    data object Applied : DisplayModeAcknowledgement

    data class Rejected(
        val resultCode: DisplayModeAckResultCode,
    ) : DisplayModeAcknowledgement
}

/**
 * Adaptador exacto de los endpoints ligeros de customer display.
 *
 * Usa el [OkHttpClient] compartido sin agregar headers de identidad. De esa
 * manera auth, refresh, conectividad, supresión de errores de background y
 * `DeviceHeadersInterceptor` conservan una sola fuente de verdad.
 */
@Singleton
class DisplayModeRemoteRepository @Inject constructor(
    private val client: OkHttpClient,
    @Named("apiBaseUrl") private val baseUrlProvider: () -> String,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }
    private val jsonMediaType = "application/json".toMediaType()

    suspend fun reportCapabilities(
        venueId: String,
        snapshot: DisplayCapabilitySnapshot,
    ): DisplayModeRemoteOutcome<Unit> = execute(
        requestFactory = {
            val payload = CapabilityReportBody(
                customerDisplay = CustomerDisplayBody(
                    present = snapshot.present,
                    invertible = snapshot.invertible,
                ),
                displayModeProtocolVersion = DISPLAY_MODE_PROTOCOL_VERSION,
            )
            Request.Builder()
                .url(venueUrl(venueId).newBuilder().addPathSegment("device-capabilities").build())
                .header(ForbiddenInterceptor.BACKGROUND_HEADER, BACKGROUND_HEADER_VALUE)
                .put(json.encodeToString(payload).toRequestBody(jsonMediaType))
                .build()
        },
        decodeSuccess = { DisplayModeRemoteOutcome.Success(Unit) },
    )

    suspend fun fetchDisplayModeRequest(
        venueId: String,
    ): DisplayModeRemoteOutcome<DisplayModeRequestBinding> = execute(
        requestFactory = {
            Request.Builder()
                .url(venueUrl(venueId).newBuilder().addPathSegment("display-mode-request").build())
                .header(ForbiddenInterceptor.BACKGROUND_HEADER, BACKGROUND_HEADER_VALUE)
                .get()
                .build()
        },
        decodeSuccess = { body ->
            val envelope = json.decodeFromString<PollEnvelope>(body)
            val terminalId = requireBoundedId(envelope.data.terminalId)
            val request = envelope.data.request
            if (request == null) {
                DisplayModeRemoteOutcome.NoRequest(terminalId)
            } else {
                val requestId = requireBoundedId(request.requestId)
                val requestedAt = Instant.parse(request.requestedAt)
                val expiresAt = Instant.parse(request.expiresAt)
                DisplayModeRemoteOutcome.Success(
                    DisplayModeRequestBinding(
                        terminalId = terminalId,
                        request = RemoteDisplayModeRequest(
                            requestId = requestId,
                            desiredInverted = request.desiredInverted,
                            status = RemoteDisplayModeRequest.STATUS_PENDING,
                            expiresAt = expiresAt,
                            requestedAt = requestedAt,
                        ),
                    ),
                )
            }
        },
    )

    suspend fun acknowledgeDisplayMode(
        venueId: String,
        terminalId: String,
        requestId: String,
        customerDisplayInverted: Boolean,
        acknowledgement: DisplayModeAcknowledgement,
    ): DisplayModeRemoteOutcome<Unit> = execute(
        requestFactory = {
            val payload = when (acknowledgement) {
                DisplayModeAcknowledgement.Applied -> AckBody(
                    customerDisplayInverted = customerDisplayInverted,
                    requestId = requestId,
                    outcome = DisplayModeAckOutcome.APPLIED.name,
                )

                is DisplayModeAcknowledgement.Rejected -> AckBody(
                    customerDisplayInverted = customerDisplayInverted,
                    requestId = requestId,
                    outcome = DisplayModeAckOutcome.REJECTED.name,
                    resultCode = acknowledgement.resultCode.name,
                )
            }
            val url = venueUrl(venueId).newBuilder()
                .addPathSegment("terminals")
                .addPathSegment(terminalId)
                .addPathSegment("display-mode")
                .build()
            Request.Builder()
                .url(url)
                .header(ForbiddenInterceptor.BACKGROUND_HEADER, BACKGROUND_HEADER_VALUE)
                .patch(json.encodeToString(payload).toRequestBody(jsonMediaType))
                .build()
        },
        decodeSuccess = { DisplayModeRemoteOutcome.Success(Unit) },
    )

    private fun venueUrl(venueId: String): HttpUrl {
        val base = baseUrlProvider().toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid API base URL")
        return base.newBuilder()
            .addPathSegment("mobile")
            .addPathSegment("venues")
            .addPathSegment(venueId)
            .build()
    }

    private suspend fun <T> execute(
        requestFactory: () -> Request,
        decodeSuccess: (String) -> DisplayModeRemoteOutcome<T>,
    ): DisplayModeRemoteOutcome<T> = try {
        val request = requestFactory()
        val response = awaitHttpResult(request)
        when {
            response.bodyTooLarge -> DisplayModeRemoteOutcome.Retryable(response.status)
            response.status in 200..299 -> try {
                decodeSuccess(response.body)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                DisplayModeRemoteOutcome.Retryable(response.status)
            }

            response.status == 401 -> DisplayModeRemoteOutcome.SessionInvalid
            response.status == 408 || response.status == 429 || response.status >= 500 ->
                DisplayModeRemoteOutcome.Retryable(response.status)

            response.status in 400..499 -> {
                val error = parseServerError(response.body)
                DisplayModeRemoteOutcome.Rejected(
                    status = response.status,
                    code = error.code,
                    message = error.message,
                )
            }

            else -> DisplayModeRemoteOutcome.Retryable(response.status)
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: IOException) {
        DisplayModeRemoteOutcome.Retryable()
    } catch (_: Exception) {
        DisplayModeRemoteOutcome.Retryable()
    }

    /**
     * Puente coroutine-aware sobre OkHttp async. Cancelar background/logout
     * cancela el socket real, no sólo la coroutine que estaba esperando a
     * `execute()` bloqueante.
     */
    private suspend fun awaitHttpResult(request: Request): HttpResult =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, error: IOException) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(error)
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val result = try {
                            response.use { httpResponse ->
                                val boundedBody = readBoundedBody(httpResponse.body)
                                HttpResult(
                                    status = httpResponse.code,
                                    body = boundedBody.body,
                                    bodyTooLarge = boundedBody.tooLarge,
                                )
                            }
                        } catch (cancellation: CancellationException) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(cancellation)
                            }
                            return
                        } catch (error: Exception) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(error)
                            }
                            return
                        }
                        if (continuation.isActive) continuation.resume(result)
                    }
                },
            )
        }

    private fun requireBoundedId(value: String): String {
        require(value.isNotBlank() && value.length <= MAX_SERVER_ID_LENGTH)
        return value
    }

    /**
     * Retains at most 64 KiB + one sentinel byte for decode. The lightweight
     * endpoints only return tiny JSON envelopes; anything larger is a
     * transient/invalid response and must not allocate an unbounded String.
     * Okio may prefetch the remainder of its current small source segment, but
     * that buffer is closed immediately and never converted into the payload.
     */
    private fun readBoundedBody(body: ResponseBody?): BoundedBody {
        if (body == null) return BoundedBody(body = "", tooLarge = false)
        val declaredLength = body.contentLength()
        if (declaredLength > MAX_RESPONSE_BODY_BYTES) {
            return BoundedBody(body = "", tooLarge = true)
        }

        val source = body.source()
        val buffer = Buffer()
        var remaining = MAX_RESPONSE_BODY_BYTES + 1L
        while (remaining > 0L) {
            val read = source.read(buffer, remaining)
            if (read == -1L) {
                return BoundedBody(body = buffer.readUtf8(), tooLarge = false)
            }
            remaining -= read
        }
        return BoundedBody(body = "", tooLarge = true)
    }

    private fun parseServerError(body: String): ServerError {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return ServerError(null, null)
        val nested = root["error"] as? JsonObject
        val code = root.stringValue("code") ?: nested?.stringValue("code")
        val message = root.stringValue("message")
            ?: nested?.stringValue("message")
            ?: root["error"]?.let { value ->
                runCatching { value.jsonPrimitive.contentOrNull }.getOrNull()
            }
        return ServerError(
            code = code?.take(MAX_ERROR_CODE_LENGTH),
            message = message?.take(MAX_ERROR_MESSAGE_LENGTH),
        )
    }

    private fun JsonObject.stringValue(key: String): String? =
        runCatching { get(key)?.jsonPrimitive?.contentOrNull }.getOrNull()

    private data class HttpResult(
        val status: Int,
        val body: String,
        val bodyTooLarge: Boolean,
    )

    private data class BoundedBody(
        val body: String,
        val tooLarge: Boolean,
    )

    private data class ServerError(val code: String?, val message: String?)

    @Serializable
    private data class CustomerDisplayBody(
        val present: Boolean,
        val invertible: Boolean,
    )

    @Serializable
    private data class CapabilityReportBody(
        val customerDisplay: CustomerDisplayBody,
        val displayModeProtocolVersion: Int,
    )

    @Serializable
    private data class PollEnvelope(val data: PollData)

    @Serializable
    private data class PollData(
        val terminalId: String,
        val request: PollRequest?,
    )

    @Serializable
    private data class PollRequest(
        val requestId: String,
        val desiredInverted: Boolean,
        val requestedAt: String,
        val expiresAt: String,
    )

    @Serializable
    private data class AckBody(
        val customerDisplayInverted: Boolean,
        val requestId: String,
        val outcome: String,
        val resultCode: String? = null,
    )

    private companion object {
        const val DISPLAY_MODE_PROTOCOL_VERSION = 1
        const val BACKGROUND_HEADER_VALUE = "1"
        const val MAX_SERVER_ID_LENGTH = 128
        const val MAX_RESPONSE_BODY_BYTES = 64L * 1024L
        const val MAX_ERROR_CODE_LENGTH = 128
        const val MAX_ERROR_MESSAGE_LENGTH = 512
    }
}
