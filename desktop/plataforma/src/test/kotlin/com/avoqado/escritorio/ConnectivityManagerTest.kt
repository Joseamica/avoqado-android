package com.avoqado.escritorio

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

class ConnectivityManagerTest {
    @Test fun `con una interfaz arriba avisa onAvailable al registrarse y reporta internet`() {
        val cm = ConnectivityManager { true }   // constructor de prueba: "hay interfaz arriba"
        val llego = CountDownLatch(1)
        cm.registerNetworkCallback(
            NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
            object : ConnectivityManager.NetworkCallback() { override fun onAvailable(network: Network) { llego.countDown() } },
        )
        assertTrue(llego.await(2, TimeUnit.SECONDS))
        assertTrue(cm.getNetworkCapabilities(cm.activeNetwork)!!.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
    }
}
