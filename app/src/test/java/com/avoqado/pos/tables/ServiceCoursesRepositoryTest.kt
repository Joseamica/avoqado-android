package com.avoqado.pos.tables

import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.tables.data.ServiceCoursesRepository
import com.avoqado.pos.kds.data.KitchenPreparationRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ServiceCoursesRepositoryTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `saved courses and confirmed workflow are visible before slow capability HTTP finishes`() = runTest {
        val storage = mockk<SecureStorage>(relaxed = true)
        every { storage.venueId } returns "v1"
        every { storage.accessToken } returns "qa"
        val cache = mockk<PayloadCache>(relaxed = true)
        coEvery { cache.load(ServiceCoursesRepository.TYPE, "v1") } returns PayloadCache.Cached("""{"success":true,"data":{"schemaVersion":1,"venueId":"v1","revision":"o:1:v:0","enabled":true,"source":"ORGANIZATION","courses":[{"id":"immediate","label":"Al momento","kind":"IMMEDIATE"},{"id":"drinks","label":"Bebidas","kind":"STANDARD"}]}}""", 1)
        val preparation = mockk<KitchenPreparationRepository>(relaxed = true)
        every { preparation.negotiated("v1") } returns true
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { preparation.refreshCapabilities("v1") } coAnswers {
            entered.complete(Unit)
            release.await()
        }
        val call = mockk<Call>(); every { call.execute() } throws IOException("offline")
        val client = mockk<OkHttpClient>(); every { client.newCall(any()) } returns call
        val repo = ServiceCoursesRepository(storage, client, cache, preparation)
        val refresh = launch { repo.refresh("v1") }
        try {
            runCurrent()
            assertTrue("El refresh HTTP sigue esperando", entered.isCompleted && !refresh.isCompleted)
            assertEquals(listOf("Al momento", "Bebidas"), repo.courses.value.map { it.label })
            assertTrue(repo.courses.value.all { it.preparationVersion == 1 })
            assertEquals("Al momento", repo.immediateSnapshot()?.label)
        } finally {
            release.complete(Unit)
            refresh.join()
        }
    }

    @Test fun `cold offline startup keeps saved catalog for this venue only`() = runTest {
        val storage = mockk<SecureStorage>(relaxed = true)
        every { storage.venueId } returns "v1"
        every { storage.accessToken } returns "qa"
        val cache = mockk<PayloadCache>(relaxed = true)
        coEvery { cache.load(ServiceCoursesRepository.TYPE, "v1") } returns PayloadCache.Cached("""{"success":true,"data":{"schemaVersion":1,"venueId":"v1","revision":"o:1:v:0","enabled":true,"source":"ORGANIZATION","courses":[{"id":"immediate","label":"Al momento","kind":"IMMEDIATE"},{"id":"drinks","label":"Bebidas","kind":"STANDARD"}]}}""", 1)
        val call = mockk<Call>(); every { call.execute() } throws IOException("offline")
        val client = mockk<OkHttpClient>(); every { client.newCall(any()) } returns call
        val repo = ServiceCoursesRepository(storage, client, cache)
        repo.refresh("v1")
        assertEquals(listOf("Al momento", "Bebidas"), repo.courses.value.map { it.label })
        assertTrue(repo.notice.value!!.contains("última lista guardada"))
        every { storage.venueId } returns "v2"
        coEvery { cache.load(ServiceCoursesRepository.TYPE, "v2") } returns null
        repo.refresh("v2")
        assertEquals("Inmediato", repo.courses.value.first().label)
        assertFalse(repo.courses.value.any { it.label == "Bebidas" })
        assertTrue(repo.notice.value!!.contains("predeterminados"))
    }
}
