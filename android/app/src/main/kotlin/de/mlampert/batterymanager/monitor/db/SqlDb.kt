package de.mlampert.batterymanager.monitor.db

/**
 * Minimale SQL-Schnittstelle des Dienstes. Produktion: [AndroidSqlDb] (Framework-SQLite).
 * Tests: JDBC-Implementierung auf dem Rechner. So bleibt der Migrator ohne Android testbar.
 */
interface SqlDb {
    /** Führt eine Anweisung ohne Ergebniszeilen aus. */
    fun exec(sql: String, args: List<Any?> = emptyList())

    /** Führt UPDATE/DELETE/INSERT aus und liefert die Anzahl betroffener Zeilen. */
    fun update(sql: String, args: List<Any?> = emptyList()): Int

    /** Erste Spalte der ersten Zeile als Long, oder null. */
    fun queryLong(sql: String, args: List<Any?> = emptyList()): Long?

    /** PRAGMA oder Anweisung, deren Ergebniszeilen verworfen werden (z. B. wal_checkpoint). */
    fun pragma(sql: String)

    /** Führt [block] in einer Transaktion aus; Exception = Rollback. */
    fun <T> transaction(block: () -> T): T
}
