package com.avoqado.pos.giftcards.presentation

import androidx.lifecycle.ViewModel
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.giftcards.data.*
import com.avoqado.pos.giftcards.domain.*
import com.avoqado.pos.printing.data.CertificadoDeTarjeta
import com.avoqado.pos.printing.data.PrinterService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject

data class SobreEnResultado(val sobre: SobreDeLaCuenta?, val activando: Boolean, val textoDeEntrega: String?, val qr: String?,
    val impresa: Boolean = false, val sinLiga: Boolean = false) {
    override fun toString() = "SobreEnResultado(****)"
}

@HiltViewModel class ResultadoDeVentaViewModel @Inject constructor(
    private val repo: GiftCardsRepository, private val store: SolicitudesDeTarjetaStore,
    private val almacen: SecureStorage, private val red: ConnectivityMonitor, private val impresora: PrinterService,
): ViewModel() {
    private val valor = MutableStateFlow<List<SobreEnResultado>>(emptyList())
    val sobres = valor.asStateFlow()
    private val avisoValor = MutableStateFlow<String?>(null)
    val aviso = avisoValor.asStateFlow()
    private val codigoValor = MutableStateFlow<String?>(null)
    val codigo = codigoValor.asStateFlow()
    private val exitoValor = MutableStateFlow<String?>(null)
    val exito = exitoValor.asStateFlow()
    private val ocupadoValor = MutableStateFlow(false)
    val ocupado = ocupadoValor.asStateFlow()
    private val fijado = MutableStateFlow(false)
    val dialogoFijado = fijado.asStateFlow()
    val conectado = red.isConnected
    val servidor = red.isServerReachable
    private val mutex = Mutex()
    internal var ahora: () -> Long = { System.nanoTime() / 1_000_000 }
    private var avisoImpresion: String? = null
    internal var esperar: suspend (Long) -> Unit = { delay(it) }
    private var orden: String? = null
    private var venue: String? = null
    private var usuario: String? = null
    private var llave: String? = null
    private var reclamada = false
    private var llaveImpresionEnVuelo: String? = null
    private var generacion = 0
    private var detalle: CuentaDeTarjetas? = null
    private val intentadas = mutableSetOf<String>()
    private val impresas = mutableSetOf<String>()
    private val sinLigas = mutableSetOf<String>()
    private var primeraRevelacion = true
    private var dialogo: Pair<String,String>? = null
    private var llaveDeDialogo: String? = null
    private var datosCambio: Triple<String,String?,String?>? = null
    private var dialogoTerminado = false
    private var versionDialogo = 0
    private var vigilando = false
    private var revisionSondeo = 0

    fun abrir(llave: String?, orderId: String) {
        if (orden == orderId && venue == almacen.venueId && usuario == almacen.userId) return
        cerrar()
        orden = orderId; venue = almacen.venueId; usuario = almacen.userId
        val s = venue?.let { v -> store.delNegocio(v).firstOrNull { it.llave == llave && it.orderId == orderId && it.tipo == "GIFT_CARD_SALE" } }
        this.llave = s?.llave
        reclamada = s != null && store.enVueloDeEsteProceso.add(s.llave)
        impresas.addAll(s?.impresos.orEmpty())
    }
    private fun vigente(g: Int = generacion) = orden != null && generacion == g && venue == almacen.venueId && usuario == almacen.userId
    private fun conRed(): Boolean {
        if (!vigente()) return false
        if (!conectado.value || !servidor.value) { avisoValor.value = TextosDeTarjetas.SIN_RED; return false }
        return true
    }
    private fun actual() = venue?.let { v -> store.delNegocio(v).firstOrNull { it.llave == llave && it.orderId == orden && it.tipo == "GIFT_CARD_SALE" } }
    private fun reclamarSiLibre() {
        if (!reclamada) actual()?.let { reclamada = store.enVueloDeEsteProceso.add(it.llave) }
    }
    private fun esperandoImpresion(): Boolean = !reclamada && actual()?.let { s ->
        detalle?.sobres?.any { it.status == "ACTIVE" && it.deliveryChannel == "PRINTED" && it.id !in s.impresos }
    } == true
    fun impresa(id: String) = id in impresas
    fun sinLiga(id: String) = id in sinLigas
    fun consumirExito() { exitoValor.value = null }
    private fun publicar() {
        valor.value = detalle?.sobres.orEmpty().map { s ->
            val qr = valor.value.firstOrNull { it.sobre?.id == s.id && it.sobre?.ultimos4 == s.ultimos4 }?.qr.takeIf { s.status == "ACTIVE" }
            SobreEnResultado(s, s.status == "PENDING", if (s.status == "ACTIVE") textoDeEntrega(s.deliveryChannel,s.deliveryStatus) else null,
                qr, impresa(s.id), sinLiga(s.id))
        }
    }
    suspend fun refrescar() = mutex.withLock { refrescarDentro() }
    private suspend fun refrescarDentro() {
        if (!conRed()) return
        val g = generacion; val v = venue ?: return; val o = orden ?: return
        ocupadoValor.value = true
        try {
            when (val r = repo.cuenta(v,o)) {
                is Respuesta.Ok -> {
                    if (!vigente(g)) return
                    detalle = r.valor; avisoValor.value = avisoImpresion; reclamarSiLibre(); publicar()
                    val solicitudActual = actual()
                    val pendientes = if (reclamada && solicitudActual != null) r.valor.sobres.filter {
                        it.status == "ACTIVE" && it.deliveryChannel == "PRINTED" && it.id !in solicitudActual.impresos && intentadas.add(it.id)
                    } else emptyList()
                    if (pendientes.isNotEmpty()) {
                        val revelados = revelarCuenta(UUID.randomUUID().toString(),g)
                        for (s in pendientes) {
                            if (!vigente(g)) return
                            val codigo = revelados?.firstOrNull { it.giftCardId == s.id }
                            if (codigo != null) imprimirRevelado(s,codigo,g)
                            else if (cuentaFueraDeDia || cuentaSinPermiso && s.revelarPideGerente) revelarIndividual(s,"REPRINT",true,UUID.randomUUID().toString(),g)?.let { imprimirRevelado(s,it,g) }
                            else if (cuentaSinPermiso) avisoValor.value = "No tienes permiso para revelar el código. Pídeselo a tu gerente."
                            else if (revelados != null) avisoValor.value = "No se pudo obtener el código vigente. Intenta de nuevo."
                        }
                    }
                    if (vigente(g)) { limpiar(); publicar() }
                }
                is Respuesta.Rechazo -> if (vigente(g)) {
                    if (r.fallo.http == 404 && r.fallo.code == null) { detalle = null; valor.value = emptyList(); codigoValor.value=null; avisoValor.value = null }
                    else fallo(r.fallo)
                }
                Respuesta.SinRespuesta -> if (vigente(g)) avisoValor.value = TextosDeTarjetas.SIN_RED
            }
        } finally { ocupadoValor.value = false }
    }
    suspend fun vigilar() {
        if (vigilando) return
        vigilando = true
        val g = generacion
        var revision = revisionSondeo
        var inicio = ahora()
        var transcurrido = 0L
        try {
            refrescar()
            while (vigente(g)) {
                if (revision != revisionSondeo) { revision=revisionSondeo;inicio=ahora();transcurrido=0 }
                transcurrido = maxOf(transcurrido,ahora()-inicio)
                val activando = (detalle?.sobres?.any { it.status == "PENDING" } == true || esperandoImpresion()) && transcurrido < 30_000
                val enviando = detalle?.sobres?.any { it.status == "ACTIVE" && it.deliveryChannel in setOf("EMAIL","WHATSAPP") && it.deliveryStatus == "PENDING" } == true && transcurrido < 120_000
                val pausa = when { activando -> minOf(2_000,30_000-transcurrido); enviando -> minOf(5_000,120_000-transcurrido); else -> break }
                esperar(pausa); transcurrido += pausa
                if (!vigente(g) || !conRed()) break
                refrescar()
            }
        } finally { vigilando = false }
    }
    private var cuentaFueraDeDia = false
    private var cuentaSinPermiso = false
    private suspend fun revelarCuenta(key: String, g: Int): List<CodigoRevelado>? {
        cuentaFueraDeDia = false; cuentaSinPermiso = false; primeraRevelacion = false
        val v = venue ?: return null; val o = orden ?: return null
        val r = repo.revelarCuenta(v,o,key)
        if (!vigente(g)) return null
        return when (r) {
            is Respuesta.Ok -> r.valor
            is Respuesta.Rechazo -> {
                cuentaFueraDeDia = r.fallo.code == "GIFT_CARD_REVEAL_NOT_ALLOWED"
                cuentaSinPermiso = r.fallo.http == 403 && r.fallo.code == null
                if (!cuentaFueraDeDia && !cuentaSinPermiso) fallo(r.fallo)
                null
            }
            Respuesta.SinRespuesta -> { avisoValor.value = TextosDeTarjetas.SIN_RED; null }
        }
    }
    private suspend fun revelarIndividual(s: SobreDeLaCuenta, motivo: String, gerente: Boolean, key: String, g: Int): CodigoRevelado? {
        val v = venue ?: return null
        val r = repo.revelar(v,s.id,key,motivo,gerente)
        if (!vigente(g)) return null
        return when (r) {
            is Respuesta.Ok -> r.valor
            is Respuesta.Rechazo -> { fallo(r.fallo,gerente); null }
            Respuesta.SinRespuesta -> { avisoValor.value = TextosDeTarjetas.SIN_RED; null }
        }
    }
    private suspend fun revelar(s: SobreDeLaCuenta, motivo: String, key: String, g: Int): CodigoRevelado? {
        if (primeraRevelacion) {
            val todos = revelarCuenta(key,g)
            if (todos != null) return todos.firstOrNull { it.giftCardId == s.id }.also {
                if (it == null) avisoValor.value = "No se pudo obtener el código vigente. Intenta de nuevo."
            }
            if (cuentaSinPermiso && !s.revelarPideGerente) {
                avisoValor.value = "No tienes permiso para revelar el código. Pídeselo a tu gerente."
                return null
            }
            if (!cuentaFueraDeDia && !cuentaSinPermiso) return null
            return revelarIndividual(s,motivo,true,key,g)
        }
        return revelarIndividual(s,motivo,s.revelarPideGerente,key,g)
    }
    private suspend fun imprimirRevelado(s: SobreDeLaCuenta, c: CodigoRevelado, g: Int): Boolean {
        if (!vigente(g) || c.codigo.isBlank()) return false
        val fecha = c.venceEl?.let { runCatching { DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(com.avoqado.pos.core.util.VenueTimeZone.zoneIdFor(almacen.venueTimezone)).format(Instant.parse(it)) }.getOrDefault(it) }
        val certificado = CertificadoDeTarjeta(almacen.venueName,c.monto,c.recipientName,c.purchaserName,c.message,c.pageUrl,c.codigo,c.ultimos4,fecha)
        val solicitudImpresion = actual()?.takeIf { reclamada }
        val llaveDelEnvio = solicitudImpresion?.llave
        llaveImpresionEnVuelo = llaveDelEnvio
        return withContext(NonCancellable) {
            // ponytail: sin resultado del driver se retiene la llave hasta reiniciar; un watchdog exige cese acreditado.
            try {
                val r = try { impresora.printGiftCardCertificate(certificado) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { PrinterService.PrintOutcome.Failed("No se pudo imprimir. Intenta de nuevo.") }
                if (r is PrinterService.PrintOutcome.Printed && r.count > 0) {
                    // Papel→marca no es atómico: morir aquí puede repetir UNA copia del MISMO código, siempre con reveal auditado.
                    if (solicitudImpresion != null && !store.anotarImpreso(solicitudImpresion.llave,s.id)) {
                        if (vigente(g)) avisoDeImpresion("Se imprimió, pero no se pudo guardar la marca en el aparato. Puedes volver a imprimir.")
                        return@withContext false
                    }
                    if (vigente(g)) { impresas.add(s.id); publicar() }
                    return@withContext true
                }
                if (vigente(g)) avisoDeImpresion(when(r) {
                    PrinterService.PrintOutcome.NoPrinter -> "No hay impresora configurada. Puedes volver a imprimir."
                    PrinterService.PrintOutcome.OutOfPaper -> "La impresora no tiene papel. Puedes volver a imprimir."
                    is PrinterService.PrintOutcome.Failed -> r.reason
                    else -> "No se pudo imprimir. Intenta de nuevo."
                })
                return@withContext false
            } finally {
                llaveImpresionEnVuelo = null
                if (llaveDelEnvio != null && !vigente(g)) {
                    if (generacion == g) reclamada = false
                    store.enVueloDeEsteProceso.remove(llaveDelEnvio)
                }
            }
        }
    }
    private fun avisoDeImpresion(texto: String) { avisoImpresion=texto;avisoValor.value=texto }
    private fun limpiar() {
        val d = detalle ?: return
        val s = actual() ?: return
        if (!reclamada || d.sobres.isEmpty() || d.sobres.any { it.status !in setOf("ACTIVE","DEACTIVATED","BLOCKED","EXPIRED","EMPTY") }) return
        if (d.sobres.any { it.status == "ACTIVE" && it.deliveryChannel == "PRINTED" && it.id !in s.impresos }) return
        if (!store.quitar(s.llave)) avisoDeImpresion("No se pudo guardar en el aparato. La solicitud se conserva para reintentar.")
        else {avisoImpresion=null;avisoValor.value=null}
    }
    private fun fallo(f: FalloDeTarjeta, gerente: Boolean = false) {
        avisoValor.value = if (gerente && f.http == 403 && f.code == null) null else if (f.http == 403 && f.code == null) TextosDeTarjetas.SIN_PERMISO_CONSULTAR else f.message ?: TextosDeTarjetas.SIN_RED
    }
    fun abrirDialogo(id: String, accion: String) {
        cerrarDialogo(); dialogo = id to accion; llaveDeDialogo = UUID.randomUUID().toString(); exitoValor.value=null; avisoValor.value=null
    }
    fun cerrarDialogo() {
        versionDialogo++; dialogo=null; llaveDeDialogo=null;datosCambio=null;dialogoTerminado=false;fijado.value=false;codigoValor.value=null
        valor.value=valor.value.map {it.copy(qr=null)}
    }
    private fun clave(id: String, accion: String): String {
        if (dialogo != (id to accion)) abrirDialogo(id,accion)
        return llaveDeDialogo!!
    }
    private suspend fun accion(id: String, nombre: String, hacer: suspend (SobreDeLaCuenta,String,Int,Int)->Unit) = mutex.withLock {
        if (!conRed()) return@withLock
        val s=detalle?.sobres?.firstOrNull {it.id==id && it.status=="ACTIVE"} ?: return@withLock
        val permitida=when(nombre) { "REPRINT"->s.deliveryChannel=="PRINTED" || s.puedeRevelar; "SHOW_QR"->s.deliveryChannel=="HANDED_TO_BUYER" || s.puedeRevelar; "RESEND"->s.puedeReenviar;else->s.puedeCambiarDestino }
        if (!permitida) return@withLock
        if (nombre == "REPRINT") {
            reclamarSiLibre()
            if (actual() != null && !reclamada) { avisoValor.value="Esta venta tiene una operación en curso. Intenta de nuevo."; return@withLock }
        }
        val key=clave(id,nombre);if(dialogoTerminado) return@withLock
        val g=generacion;val vd=versionDialogo;ocupadoValor.value=true;avisoValor.value=null
        try { hacer(s,key,g,vd) } finally { ocupadoValor.value=false }
    }
    suspend fun imprimir(id: String) = accion(id,"REPRINT") { s,key,g,vd ->
        val c=revelar(s,"REPRINT",key,g)
        if(c!=null && vigente(g) && versionDialogo==vd && imprimirRevelado(s,c,g) && vigente(g) && versionDialogo==vd) {
            dialogoTerminado=true;avisoImpresion=null;limpiar();exitoValor.value="Tarjeta impresa"
        }
    }
    suspend fun mostrarQr(id: String) = accion(id,"SHOW_QR") { s,key,g,vd ->
        codigoValor.value=null;valor.value=valor.value.map {it.copy(qr=null)}
        val c=revelar(s,"SHOW_QR",key,g)
        if(c!=null && vigente(g) && versionDialogo==vd) {
            if(c.pageUrl.isNullOrBlank()) {sinLigas.add(id);avisoValor.value="Esta tarjeta no incluye una liga para mostrar o compartir.";publicar()}
            else {codigoValor.value=c.codigo;valor.value=valor.value.map {if(it.sobre?.id==id) it.copy(qr=c.pageUrl) else it}}
        }
    }
    suspend fun reenviar(id: String) = accion(id,"RESEND") { _,key,g,vd ->
        when(val r=repo.reenviar(venue!!,id,key)) {
            is Respuesta.Ok -> if(vigente(g) && versionDialogo==vd) {dialogoTerminado=true;revisionSondeo++;exitoValor.value="Reenvío solicitado";refrescarDentro()}
            is Respuesta.Rechazo -> if(vigente(g) && versionDialogo==vd) fallo(r.fallo)
            Respuesta.SinRespuesta -> if(vigente(g)) avisoValor.value=TextosDeTarjetas.SIN_RED
        }
    }
    suspend fun cambiarDestino(id: String, canal: String, telefonoDigitos: String, correo: String) = accion(id,"CHANGE_DESTINATION") { _,key,g,vd ->
        val datos=datosCambio ?: when(canal) {
            "WHATSAPP"->telefonoMx(telefonoDigitos)?.let {Triple(canal,it,null)}
            "EMAIL"->correo.trim().takeIf {it.length<=120 && it.matches(Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"))}?.let {Triple(canal,null,it)}
            else->null
        }
        if(datos==null) {avisoValor.value=if(canal=="WHATSAPP") "Escribe 10 dígitos de México (+52)" else "Revisa el correo";return@accion}
        datosCambio=datos;fijado.value=true;codigoValor.value=null;valor.value=valor.value.map {it.copy(qr=null)}
        when(val r=repo.cambiarDestino(venue!!,id,key,datos.first,datos.second,datos.third)) {
            is Respuesta.Ok -> if(vigente(g) && versionDialogo==vd) {dialogoTerminado=true;primeraRevelacion=true;sinLigas.remove(id);revisionSondeo++;exitoValor.value="Destino actualizado";refrescarDentro()}
            is Respuesta.Rechazo -> if(vigente(g) && versionDialogo==vd) fallo(r.fallo,true)
            Respuesta.SinRespuesta -> if(vigente(g)) avisoValor.value=TextosDeTarjetas.SIN_RED
        }
    }
    fun cerrar() {
        // Cerrar la pantalla no acredita que haya cesado un envío suspendido.
        if (reclamada && llave != llaveImpresionEnVuelo) llave?.let {store.enVueloDeEsteProceso.remove(it)}
        generacion++;orden=null;venue=null;usuario=null;llave=null;reclamada=false;detalle=null
        intentadas.clear();impresas.clear();sinLigas.clear();primeraRevelacion=true;cuentaFueraDeDia=false;cuentaSinPermiso=false
        cerrarDialogo();valor.value=emptyList();avisoValor.value=null;avisoImpresion=null;exitoValor.value=null
    }
    override fun onCleared() { cerrar();super.onCleared() }
}
