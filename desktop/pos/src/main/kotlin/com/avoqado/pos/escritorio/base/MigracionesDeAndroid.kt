package com.avoqado.pos.escritorio.base

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL

/** Lo único de SupportSQLiteDatabase que usan las migraciones de Android. */
interface CursorDeAndroid : java.io.Closeable {
    fun moveToNext(): Boolean
    fun getString(index: Int): String?
}
interface BaseDeAndroid {
    fun execSQL(sql: String)
    fun execSQL(sql: String, bindArgs: Array<out Any?>) { error("Esta base no permite parámetros") }
    fun query(sql: String, bindArgs: Array<out Any?>): CursorDeAndroid { error("Esta base no permite lectura") }
}
private fun SQLiteStatement.parametros(args: Array<out Any?>) {
    args.forEachIndexed { n,value -> when(value) {
        null -> bindNull(n+1)
        is String -> bindText(n+1,value)
        else -> error("Parámetro inesperado en migración de identidad")
    } }
}

/** El `Migration` que ven las migraciones de Android en escritorio (alias en la copia generada de AvoqadoDatabaseMigrations). */
abstract class MigracionDeAndroid(val startVersion: Int, val endVersion: Int) {
    abstract fun migrate(database: BaseDeAndroid)
}

/** Todas las `MigracionDeAndroid` del objeto AvoqadoDatabaseMigrations, ordenadas por startVersion. */
fun migracionesDeAndroid(): List<MigracionDeAndroid> {
    val clase = Class.forName("com.avoqado.pos.core.data.local.database.AvoqadoDatabaseMigrations")
    val objeto = clase.getField("INSTANCE").get(null)
    val migraciones = clase.declaredFields
        .filter { MigracionDeAndroid::class.java.isAssignableFrom(it.type) }
        .map { it.apply { isAccessible = true }.get(objeto) as MigracionDeAndroid }
        .sortedBy { it.startVersion }
    check(migraciones.isNotEmpty()) { "No se encontró ninguna migración de Android en AvoqadoDatabaseMigrations" }
    return migraciones
}

/** Para Room JVM: corre la migración de Android sobre la conexión que Room le da (dentro de su transacción). */
fun MigracionDeAndroid.paraRoom(): Migration = object : Migration(startVersion, endVersion) {
    override fun migrate(connection: SQLiteConnection) =
        this@paraRoom.migrate(object : BaseDeAndroid {
            override fun execSQL(sql: String) = connection.execSQL(sql)
            override fun execSQL(sql: String, bindArgs: Array<out Any?>) {
                connection.prepare(sql).use { statement -> statement.parametros(bindArgs); statement.step() }
            }
            override fun query(sql: String, bindArgs: Array<out Any?>): CursorDeAndroid {
                val statement=connection.prepare(sql)
                try { statement.parametros(bindArgs) } catch (e: Exception) { statement.close(); throw e }
                return object : CursorDeAndroid {
                    override fun moveToNext() = statement.step()
                    override fun getString(index: Int): String? = if(statement.isNull(index)) null else statement.getText(index)
                    override fun close() = statement.close()
                }
            }
        })
}

/** La versión REAL de la base: @Database(version) de AvoqadoDatabase, leída al generar (VersionDeLaBase.kt). */
fun versionDeLaApp(): Int = VERSION_DE_LA_BASE
