package de.mlampert.batterymanager.monitor.db

import android.content.Context
import android.content.res.AssetManager
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import java.io.File

/** [SqlDb] auf der Android-Framework-SQLite (dieselbe Bibliothek, die sqflite in der UI nutzt). */
class AndroidSqlDb(val raw: SQLiteDatabase) : SqlDb {

    override fun exec(sql: String, args: List<Any?>) {
        if (args.isEmpty()) raw.execSQL(sql) else raw.execSQL(sql, args.toTypedArray())
    }

    override fun update(sql: String, args: List<Any?>): Int =
        raw.compileStatement(sql).use { st ->
            bind(st, args)
            st.executeUpdateDelete()
        }

    override fun queryLong(sql: String, args: List<Any?>): Long? =
        raw.rawQuery(sql, args.map { it?.toString() }.toTypedArray()).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }

    override fun pragma(sql: String) {
        raw.rawQuery(sql, null).use { it.moveToFirst() }
    }

    override fun <T> transaction(block: () -> T): T {
        raw.beginTransaction()
        try {
            val r = block()
            raw.setTransactionSuccessful()
            return r
        } finally {
            raw.endTransaction()
        }
    }

    private fun bind(st: SQLiteStatement, args: List<Any?>) {
        args.forEachIndexed { i, a ->
            val idx = i + 1
            when (a) {
                null -> st.bindNull(idx)
                is Int -> st.bindLong(idx, a.toLong())
                is Long -> st.bindLong(idx, a)
                is Double -> st.bindDouble(idx, a)
                is Float -> st.bindDouble(idx, a.toDouble())
                is ByteArray -> st.bindBlob(idx, a)
                else -> st.bindString(idx, a.toString())
            }
        }
    }

    companion object {
        const val DB_NAME = "batterymanager.db"

        /**
         * Öffnet (bzw. erzeugt) die DB im Prozess :monitor und migriert sie.
         * Vor jeder Migration einer bestehenden DB wird eine Kopie `batterymanager.db.v<N>.bak` angelegt.
         */
        fun openAndMigrate(context: Context): AndroidSqlDb {
            val file = context.getDatabasePath(DB_NAME)
            file.parentFile?.mkdirs()
            val raw = SQLiteDatabase.openDatabase(
                file.path, null,
                SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING
            )
            val db = AndroidSqlDb(raw)
            db.pragma("PRAGMA synchronous = NORMAL")
            Migrator(db, loadScripts(context.assets)).migrate { from ->
                db.pragma("PRAGMA wal_checkpoint(TRUNCATE)")
                file.copyTo(File(file.path + ".v$from.bak"), overwrite = true)
            }
            return db
        }

        /** Schema-Skripte aus den Assets `db/NNN_*.sql` (Gradle kopiert spec/db dorthin). */
        fun loadScripts(assets: AssetManager): List<Migrator.Script> {
            val names = assets.list("db").orEmpty().filter { Migrator.versionOf(it) != null }
            return Migrator.scriptsFrom(names.associateWith { name ->
                assets.open("db/$name").bufferedReader(Charsets.UTF_8).use { it.readText() }
            })
        }

        fun loadQuery(assets: AssetManager, name: String): String =
            assets.open("db/q/$name").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
