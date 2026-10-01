package com.avoqado.pos.escritorio.base

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/** Lo único de SupportSQLiteDatabase que usan las migraciones de Android. */
interface BaseDeAndroid { fun execSQL(sql: String) }

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
        this@paraRoom.migrate(object : BaseDeAndroid { override fun execSQL(sql: String) = connection.execSQL(sql) })
}

/** La versión REAL de la base: @Database(version) de AvoqadoDatabase, leída al generar (VersionDeLaBase.kt). */
fun versionDeLaApp(): Int = VERSION_DE_LA_BASE
