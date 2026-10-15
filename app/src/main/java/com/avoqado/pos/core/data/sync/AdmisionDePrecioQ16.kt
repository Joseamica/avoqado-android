package com.avoqado.pos.core.data.sync

/** Process-local admission only. No prices, UUIDs, money or durable state are created here. */
internal object AdmisionDePrecioQ16 {
    class Token internal constructor(internal val venue: String, internal val generation: Any)
    private class State(var generation: Any = Any(), var writers: Int = 0)
    private val lock = Any()
    private val venues = mutableMapOf<String, State>()
    fun snapshot(venue: String): Token = synchronized(lock) {
        Token(venue, venues.getOrPut(venue) { State() }.generation)
    }
    fun begin(venue: String) = synchronized(lock) {
        val state=venues.getOrPut(venue) { State() };state.generation=Any();state.writers++
    }
    fun end(venue: String) = synchronized(lock) {
        val state=checkNotNull(venues[venue]);check(state.writers>0);state.writers--;state.generation=Any()
    }
    suspend fun <T> write(venue: String, block: suspend () -> T): T {
        begin(venue)
        try { return block() } finally { end(venue) }
    }
    /** The block may ONLY enqueue a request, never wait for a response or disk. */
    fun admit(token: Token, start: () -> Unit): Boolean = synchronized(lock) {
        val state=venues[token.venue] ?: return@synchronized false
        if (state.writers != 0 || state.generation !== token.generation) return@synchronized false
        start();true
    }
    class Changed : java.io.IOException("La captura cambió antes de iniciar la petición")
}

/** Executed for EVERY network attempt, after headers and authenticator follow-up creation.
 * Admission is the linearization point; proceed waits outside the monitor. */
internal object InterceptorDePrecioQ16: okhttp3.Interceptor {
    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val token=chain.request().tag(Any::class.java) as? AdmisionDePrecioQ16.Token
        if (token != null && !AdmisionDePrecioQ16.admit(token) { }) throw AdmisionDePrecioQ16.Changed()
        return chain.proceed(chain.request())
    }
}
