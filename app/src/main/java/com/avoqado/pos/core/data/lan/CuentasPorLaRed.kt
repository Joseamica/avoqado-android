package com.avoqado.pos.core.data.lan

/**
 * D12 · Opción D — las preguntas de una cuenta (`cuenta:<orderId>`) por el WiFi del local, en el formato de [LeaseProtocol]. Se contestan
 * sólo en la caja que la ABRIÓ ([DuenoDeCuentas]); cualquier otra contesta [LeaseProtocol.STATUS_NO_ES_MIA], y eso ES «¿quién la abrió?»
 * (regla 2 de `opcion-d.md`). Las cuatro ops de mesas no cambian.
 */
object CuentasPorLaRed {
    const val PREFIJO = "cuenta:"

    fun llave(orderId: String): String = PREFIJO + orderId

    fun esDeCuenta(op: String): Boolean = op.startsWith("cuenta_")

    fun aCable(r: Respuesta?): LeaseResponse = when (r) {
        null -> LeaseResponse(status = LeaseProtocol.STATUS_NO_ES_MIA)
        Respuesta.Concedida -> LeaseResponse(status = LeaseProtocol.STATUS_GRANTED)
        is Respuesta.Ocupada -> LeaseResponse(status = LeaseProtocol.STATUS_DENIED, quien = r.quien, caja = r.caja)
        is Respuesta.Cerrada -> LeaseResponse(status = LeaseProtocol.STATUS_CERRADA, caja = r.caja, faltaSubirla = r.faltaSubirla)
        Respuesta.Rechazada -> LeaseResponse(status = LeaseProtocol.STATUS_RECHAZADA)
    }

    /** null = no contestó la dueña (nada, error, versión distinta o «no es mía»). */
    fun desdeCable(r: LeaseResponse?): Respuesta? = when (r?.status) {
        LeaseProtocol.STATUS_GRANTED -> Respuesta.Concedida
        LeaseProtocol.STATUS_DENIED -> Respuesta.Ocupada(r.quien.orEmpty(), r.caja.orEmpty())
        LeaseProtocol.STATUS_CERRADA -> Respuesta.Cerrada(r.caja.orEmpty(), r.faltaSubirla ?: true)
        LeaseProtocol.STATUS_RECHAZADA -> Respuesta.Rechazada
        else -> null
    }

    /** El acuse de «suelta» y «cobrada»: sólo un `ok` de la dueña. */
    fun acuso(r: LeaseResponse?): Boolean = r?.status == LeaseProtocol.STATUS_OK

    fun pedido(op: String, orderId: String, yo: String, caja: String, quien: String, intento: String, enLinea: Boolean = false, subida: Boolean = false) =
        LeaseRequest(op = op, tableId = llave(orderId), deviceId = yo, staffName = quien, intento = intento, caja = caja, enLinea = enLinea, subida = subida)

    /** Lo que contesta ESTA caja a una línea `cuenta_*` de otra, con su registro. Nunca lanza (salvo la cancelación). */
    suspend fun contestar(linea: String, venueId: String?, dueno: DuenoDeCuentas): String {
        val req = LeaseProtocol.decodeRequest(linea) ?: return error("Petición ilegible")
        if (req.version != LeaseProtocol.PROTOCOL_VERSION) return error(LeaseProtocol.ERROR_VERSION_MISMATCH)
        val v = venueId ?: return error("Sin sucursal")
        if (!req.tableId.startsWith(PREFIJO)) return error("Llave ajena")
        val orden = req.tableId.removePrefix(PREFIJO)
        val s = Solicitud(req.deviceId, req.caja, req.staffName, req.intento, req.enLinea)
        val respuesta = try {
            when (req.op) {
                LeaseProtocol.OP_CUENTA_APARTAR -> aCable(dueno.apartar(v, orden, s))
                LeaseProtocol.OP_CUENTA_COMPROMETER -> aCable(dueno.comprometer(v, orden, s))
                LeaseProtocol.OP_CUENTA_SOLTAR -> acuse(dueno.soltar(v, orden, req.intento))
                LeaseProtocol.OP_CUENTA_COBRADA -> acuse(dueno.cobrada(v, orden, s, req.subida))
                else -> return error("Operación desconocida: ${req.op}")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return error("No se pudo guardar") // sin acuse: quien pregunta reintenta
        }
        return LeaseProtocol.encode(respuesta)
    }

    private fun acuse(ok: Boolean?): LeaseResponse =
        LeaseResponse(status = if (ok == null) LeaseProtocol.STATUS_NO_ES_MIA else LeaseProtocol.STATUS_OK)

    private fun error(mensaje: String) = LeaseProtocol.encode(LeaseResponse(status = LeaseProtocol.STATUS_ERROR, message = mensaje))
}
