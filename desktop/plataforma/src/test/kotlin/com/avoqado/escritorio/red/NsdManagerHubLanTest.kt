package com.avoqado.escritorio.red

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.InetAddress
import java.util.concurrent.Executor
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** El contrato de NSD que LanDiscovery (Android) usa para el Hub LAN, sobre un mDNS falso. */
class NsdManagerHubLanTest {
    private val directo = Executor { it.run() }
    private val mdns = MdnsDePrueba()
    private val nsd = NsdManager()

    @BeforeTest fun armar() {
        RedLocalMdns.enUso = RedLocalMdns({ InetAddress.getByName("192.168.100.70") }, { mdns }, directo, directo, directo)
    }
    @AfterTest fun soltar() { RedLocalMdns.enUso = null }

    private class Registro : NsdManager.RegistrationListener {
        val eventos = mutableListOf<String>()
        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) { eventos += "fallo $errorCode" }
        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) { eventos += "registrado ${serviceInfo?.serviceName}" }
        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) {}
    }

    private class Busqueda : NsdManager.DiscoveryListener {
        val eventos = mutableListOf<String>()
        val encontrados = mutableListOf<NsdServiceInfo>()
        override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) { eventos += "fallo" }
        override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
        override fun onDiscoveryStarted(serviceType: String?) { eventos += "iniciado" }
        override fun onDiscoveryStopped(serviceType: String?) { eventos += "detenido" }
        override fun onServiceFound(serviceInfo: NsdServiceInfo) { eventos += "+${serviceInfo.serviceName}"; encontrados += serviceInfo }
        override fun onServiceLost(serviceInfo: NsdServiceInfo) { eventos += "-${serviceInfo.serviceName}" }
    }

    @Test fun `anunciar con TXT y dar de baja`() {
        val info = NsdServiceInfo().apply {
            serviceName = "Avoqado-POS-abc123"; serviceType = "_avoqado-pos._tcp"; port = 4321
            setAttribute("did", "abc123"); setAttribute("kds", "st_barra")
        }
        val r = Registro()
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, r)
        assertEquals(listOf("registrado Avoqado-POS-abc123"), r.eventos)
        assertEquals("abc123", mdns.txtDe["Avoqado-POS-abc123"]?.get("did"))
        assertEquals("st_barra", mdns.txtDe["Avoqado-POS-abc123"]?.get("kds"))
        nsd.unregisterService(r)
        assertEquals(listOf("Avoqado-POS-abc123"), mdns.retirados)
    }

    @Test fun `si no se puede anunciar avisa la falla como Android`() {
        mdns.fallaAlRegistrar = true
        val r = Registro()
        nsd.registerService(NsdServiceInfo().apply { serviceName = "x"; serviceType = "_avoqado-pos._tcp"; port = 1 }, NsdManager.PROTOCOL_DNS_SD, r)
        assertEquals(listOf("fallo ${NsdManager.FAILURE_INTERNAL_ERROR}"), r.eventos)
    }

    @Test fun `buscar - iniciado, encontrado SIN host ni TXT, perdido y detenido`() {
        val b = Busqueda()
        nsd.discoverServices("_avoqado-pos._tcp", NsdManager.PROTOCOL_DNS_SD, b)
        mdns.aparece("Avoqado-POS-zzz111")
        mdns.desaparece("Avoqado-POS-zzz111")
        nsd.stopServiceDiscovery(b)
        mdns.aparece("Avoqado-POS-yyy222")
        assertEquals(listOf("iniciado", "+Avoqado-POS-zzz111", "-Avoqado-POS-zzz111", "detenido"), b.eventos)
        val encontrado = b.encontrados.single()
        assertNull(encontrado.host)
        assertTrue(encontrado.serviceType.contains("avoqado-pos"))
    }

    @Test fun `resolver entrega un NsdServiceInfo NUEVO con host, puerto y TXT, y no toca el encontrado`() {
        val b = Busqueda()
        nsd.discoverServices("_avoqado-pos._tcp", NsdManager.PROTOCOL_DNS_SD, b)
        mdns.aparece("Avoqado-POS-zzz111")
        mdns.resueltos["Avoqado-POS-zzz111"] = Resuelto(InetAddress.getByName("192.168.100.71"), 5555, mapOf("did" to "zzz111", "v" to "1"))
        val encontrado = b.encontrados.single()
        var resuelto: NsdServiceInfo? = null
        nsd.resolveService(encontrado, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) = error("no")
            override fun onServiceResolved(serviceInfo: NsdServiceInfo?) { resuelto = serviceInfo }
        })
        assertEquals("192.168.100.71", resuelto?.host?.hostAddress)
        assertEquals(5555, resuelto?.port)
        assertEquals("zzz111", resuelto?.attributes?.get("did")?.let { String(it) })
        assertNull(encontrado.host, "el encontrado se vuelve a resolver cada minuto: no puede quedarse con el host viejo")
        mdns.resueltos["Avoqado-POS-zzz111"] = Resuelto(InetAddress.getByName("192.168.100.72"), 6666, mapOf("did" to "zzz111"))
        nsd.resolveService(encontrado, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) = error("no")
            override fun onServiceResolved(serviceInfo: NsdServiceInfo?) { resuelto = serviceInfo }
        })
        assertEquals(6666, resuelto?.port, "re-resolver trae lo NUEVO")
    }

    @Test fun `detener dentro de onDiscoveryStarted si detiene`() {
        val eventos = mutableListOf<String>()
        val l = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String?) { eventos += "iniciado"; nsd.stopServiceDiscovery(this) }
            override fun onDiscoveryStopped(serviceType: String?) { eventos += "detenido" }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) { eventos += "+${serviceInfo.serviceName}" }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
        }
        nsd.discoverServices("_avoqado-pos._tcp", NsdManager.PROTOCOL_DNS_SD, l)
        mdns.aparece("Avoqado-POS-zzz111")
        assertEquals(listOf("iniciado", "detenido"), eventos)
    }

    @Test fun `iniciado siempre llega antes que detenido aunque se detenga de inmediato`() {
        val enEspera = mutableListOf<Runnable>()
        RedLocalMdns.enUso = RedLocalMdns({ InetAddress.getByName("192.168.100.70") }, { mdns }, Executor { enEspera += it }, directo, directo)
        val b = Busqueda()
        nsd.discoverServices("_avoqado-pos._tcp", NsdManager.PROTOCOL_DNS_SD, b)
        nsd.stopServiceDiscovery(b)
        enEspera.toList().forEach { it.run() }
        assertEquals(listOf("iniciado", "detenido"), b.eventos)
    }

    @Test fun `un resolve cancelado con stopServiceResolution no llega`() {
        val enEspera = mutableListOf<Runnable>()
        RedLocalMdns.enUso = RedLocalMdns({ InetAddress.getByName("192.168.100.70") }, { mdns }, Executor { enEspera += it }, directo, directo)
        mdns.resueltos["Avoqado-POS-zzz111"] = Resuelto(InetAddress.getByName("192.168.100.71"), 5555, emptyMap())
        val l = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) = error("cancelado no avisa")
            override fun onServiceResolved(serviceInfo: NsdServiceInfo?) = error("cancelado no avisa")
        }
        nsd.discoverServices("_avoqado-pos._tcp", NsdManager.PROTOCOL_DNS_SD, Busqueda())   // abre mDNS
        enEspera.toList().forEach { it.run() }; enEspera.clear()
        nsd.resolveService(NsdServiceInfo().apply { serviceName = "Avoqado-POS-zzz111"; serviceType = "_avoqado-pos._tcp" }, l)
        nsd.stopServiceResolution(l)
        enEspera.forEach { it.run() }
    }

    @Test fun `un resolve sin respuesta falla con el codigo de Android`() {
        var codigo = -1
        nsd.resolveService(NsdServiceInfo().apply { serviceName = "Avoqado-POS-nadie"; serviceType = "_avoqado-pos._tcp" },
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) { codigo = errorCode }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo?) = error("no")
            })
        assertEquals(NsdManager.FAILURE_INTERNAL_ERROR, codigo)
    }

    @Test fun `otros tipos siguen sin red local (y no tocan mDNS)`() {
        val b = Busqueda()
        nsd.discoverServices("_ipp._tcp", NsdManager.PROTOCOL_DNS_SD, b)
        assertTrue(b.eventos.isEmpty())
        assertTrue(mdns.escuchas.isEmpty())
    }

    private class MdnsDePrueba : MdnsPort {
        val txtDe = mutableMapOf<String, Map<String, String>>()
        val retirados = mutableListOf<String>()
        val escuchas = mutableListOf<Pair<(String) -> Unit, (String) -> Unit>>()
        val resueltos = mutableMapOf<String, Resuelto>()
        var fallaAlRegistrar = false
        override fun registrar(tipo: String, nombre: String, puerto: Int, txt: Map<String, String>): String {
            if (fallaAlRegistrar) error("sin red")
            txtDe[nombre] = txt; return nombre
        }
        override fun retirar(tipo: String, nombre: String) { retirados += nombre }
        override fun escuchar(tipo: String, alEncontrar: (String) -> Unit, alPerder: (String) -> Unit): () -> Unit {
            val e = alEncontrar to alPerder; escuchas += e; return { escuchas -= e }
        }
        override fun resolver(tipo: String, nombre: String, plazoMs: Long): Resuelto? = resueltos[nombre]
        override fun cerrar() {}
        fun aparece(n: String) = escuchas.toList().forEach { it.first(n) }
        fun desaparece(n: String) = escuchas.toList().forEach { it.second(n) }
    }
}
