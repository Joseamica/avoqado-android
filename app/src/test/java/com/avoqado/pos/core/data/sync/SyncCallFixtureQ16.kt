package com.avoqado.pos.core.data.sync

import com.avoqado.pos.core.data.network.ApiService
import io.mockk.every
import kotlinx.coroutines.*
import okhttp3.Request
import okio.Timeout
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/** Transport adapter only: preserves each old fixture's exact suspend response and verification. */
internal fun vincularCallFixtureQ16(api: ApiService) {
    every { api.syncIntentsAdmittedQ16(any(),any(),any()) } answers {
        val venue=firstArg<String>();val request=secondArg<SyncIntentsRequest>()
        object: Call<SyncIntentsResponse> {
            private var job: Job?=null
            private var cancelled=false
            override fun enqueue(callback: Callback<SyncIntentsResponse>) {
                check(job==null)
                val call=this
                job=CoroutineScope(Dispatchers.Unconfined).launch(start=CoroutineStart.UNDISPATCHED) {
                    try { callback.onResponse(call,Response.success(api.syncIntents(venue,request))) }
                    catch(error:Throwable) { callback.onFailure(call,error) }
                }
                if(cancelled) job?.cancel()
            }
            override fun cancel() { cancelled=true;job?.cancel() }
            override fun isCanceled()=cancelled
            override fun isExecuted()=job!=null
            override fun execute(): Response<SyncIntentsResponse> = error("Fixture expects enqueue")
            override fun clone():Call<SyncIntentsResponse> = error("No retry clone in these fixtures")
            override fun request():Request=Request.Builder().url("http://fixture.invalid/").build()
            override fun timeout():Timeout=Timeout.NONE
        }
    }
}
