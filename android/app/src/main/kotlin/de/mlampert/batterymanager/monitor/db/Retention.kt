package de.mlampert.batterymanager.monitor.db

/**
 * Löscht Rohdaten (sample, span) älter als [RAW_DAYS] Tage in Batches und gibt Speicher frei.
 * charge_session, session_point und day_stat bleiben unbegrenzt erhalten.
 * Aufrufer (Dienst) stellt sicher: höchstens 1× in 24 h und nur beim Laden.
 *
 * @param retentionSql Inhalt von `spec/db/q/retention.sql` (genau zwei Anweisungen).
 */
class Retention(private val db: SqlDb, retentionSql: String) {

    private val deleteSamples: String
    private val deleteSpans: String

    init {
        val statements = SqlScript.split(retentionSql)
        require(statements.size == 2) { "retention.sql muss genau 2 Anweisungen enthalten, gefunden: ${statements.size}" }
        deleteSamples = statements[0]
        deleteSpans = statements[1]
    }

    data class Result(val samplesDeleted: Int, val spansDeleted: Int)

    fun run(nowMs: Long): Result {
        val cutoff = nowMs - RAW_DAYS * DAY_MS
        var samples = 0
        while (true) {
            val n = db.update(deleteSamples, listOf(cutoff, BATCH))
            samples += n
            if (n < BATCH) break
        }
        val spans = db.update(deleteSpans, listOf(cutoff))
        db.pragma("PRAGMA incremental_vacuum")
        db.pragma("PRAGMA wal_checkpoint(TRUNCATE)")
        return Result(samples, spans)
    }

    companion object {
        const val RAW_DAYS = 30L
        const val BATCH = 5000
        const val DAY_MS = 86_400_000L
    }
}
