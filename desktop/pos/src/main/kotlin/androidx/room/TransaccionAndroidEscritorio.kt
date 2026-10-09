package androidx.room

/** The Android sources use Room's legacy name; JVM 2.8 provides the writer connection API. */
suspend fun <T> RoomDatabase.withTransaction(block: suspend () -> T): T =
    useWriterConnection { connection ->
        connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) { block() }
    }
