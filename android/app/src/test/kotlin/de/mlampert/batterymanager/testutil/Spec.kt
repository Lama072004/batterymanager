package de.mlampert.batterymanager.testutil

import de.mlampert.batterymanager.monitor.db.Migrator
import java.io.File

/** Zugriff auf `spec/` im Projekt-Root (Gradle-Tests laufen in android/app). */
object Spec {
    val root: File by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "spec/db/001_init.sql").isFile) return@lazy File(dir, "spec")
            dir = dir.parentFile
        }
        error("spec/db/001_init.sql nicht gefunden (user.dir=${System.getProperty("user.dir")})")
    }

    val dbDir: File get() = File(root, "db")
    val fixturesDir: File get() = File(root, "engine/fixtures")

    fun schemaScripts(): List<Migrator.Script> =
        Migrator.scriptsFrom(
            dbDir.listFiles { f -> f.isFile && f.name.endsWith(".sql") }.orEmpty()
                .associate { it.name to it.readText(Charsets.UTF_8) }
        )

    fun query(name: String): String = File(dbDir, "q/$name").readText(Charsets.UTF_8)

    /** Alle SQL-Dateien (Schema + Abfragen) für statische Prüfungen. */
    fun allSqlFiles(): List<File> = dbDir.walkTopDown().filter { it.isFile && it.name.endsWith(".sql") }.toList()

    /** Frisch migrierte In-Memory-DB. */
    fun freshDb(): JdbcSqlDb = JdbcSqlDb.inMemory().also { Migrator(it, schemaScripts()).migrate() }
}
