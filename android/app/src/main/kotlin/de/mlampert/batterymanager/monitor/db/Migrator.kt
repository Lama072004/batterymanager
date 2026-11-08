package de.mlampert.batterymanager.monitor.db

/**
 * Wendet die Schema-Skripte `spec/db/NNN_name.sql` in Versionsreihenfolge an.
 * Einziger Migrator ist der Dienst im Prozess :monitor. Version über `PRAGMA user_version`.
 *
 * - Idempotent: bereits angewandte Versionen werden übersprungen.
 * - Vor jeder Migration einer bestehenden DB wird [backup] mit der alten Version aufgerufen.
 * - Jede Version läuft in einer eigenen Transaktion (inklusive Setzen von user_version).
 */
class Migrator(
    private val db: SqlDb,
    private val scripts: List<Script>,
) {
    data class Script(val version: Int, val name: String, val sql: String)

    /** @return die Schema-Version nach der Migration. */
    fun migrate(backup: (fromVersion: Int) -> Unit = {}): Int {
        val current = (db.queryLong("PRAGMA user_version") ?: 0L).toInt()
        val pending = scripts.filter { it.version > current }.sortedBy { it.version }
        if (pending.isEmpty()) return current

        if (current == 0) {
            // Muss vor der ersten Tabelle gesetzt sein, sonst wirkungslos.
            db.pragma("PRAGMA auto_vacuum = INCREMENTAL")
        } else {
            backup(current)
        }
        for (script in pending) {
            db.transaction {
                SqlScript.split(script.sql).forEach { db.exec(it) }
                db.exec("PRAGMA user_version = ${script.version}")
            }
        }
        return pending.last().version
    }

    companion object {
        private val NAME = Regex("""^(\d{3})_[A-Za-z0-9_]+\.sql$""")

        /** Version aus dem Dateinamen (`001_init.sql` → 1), sonst null. */
        fun versionOf(fileName: String): Int? = NAME.matchEntire(fileName)?.groupValues?.get(1)?.toInt()

        fun scriptsFrom(files: Map<String, String>): List<Script> =
            files.mapNotNull { (name, sql) -> versionOf(name)?.let { Script(it, name, sql) } }
                .sortedBy { it.version }
    }
}
