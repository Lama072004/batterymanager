package de.mlampert.batterymanager.monitor.db

import android.database.sqlite.SQLiteDatabase
import de.mlampert.batterymanager.monitor.engine.ChargeModel
import de.mlampert.batterymanager.monitor.engine.Engine
import de.mlampert.batterymanager.monitor.engine.Profile
import de.mlampert.batterymanager.monitor.engine.Reading
import de.mlampert.batterymanager.monitor.engine.Settings

/** Alle Schreibzugriffe des Dienstes (Prozess :monitor ist einziger Schreiber). */
class BatteryStore(val db: AndroidSqlDb, private val retentionSql: String) {
    private val raw: SQLiteDatabase get() = db.raw

    // ---------------- Einstellungen (kv, set.*) ----------------
    fun kv(key: String): String? =
        raw.rawQuery("SELECT value FROM kv WHERE key = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }

    fun putKv(key: String, value: String) {
        raw.execSQL("INSERT OR REPLACE INTO kv (key, value) VALUES (?, ?)", arrayOf(key, value))
    }

    fun loadSettings(): Settings {
        val d = Settings()
        return Settings(
            targetPct = kv("set.targetPct")?.toIntOrNull() ?: d.targetPct,
            alarm = kv("set.alarm")?.toBooleanStrictOrNull() ?: d.alarm,
            mode = kv("set.mode")?.toIntOrNull() ?: d.mode,
            heatWarnDc = kv("set.heatWarnDc")?.toIntOrNull() ?: d.heatWarnDc,
            summary = kv("set.summary")?.toBooleanStrictOrNull() ?: d.summary,
            onboarded = kv("set.onboarded")?.toBooleanStrictOrNull() ?: d.onboarded,
            liveChip = kv("set.liveChip")?.toBooleanStrictOrNull() ?: d.liveChip,
            statusIcon = kv("set.statusIcon")?.toBooleanStrictOrNull() ?: d.statusIcon,
        )
    }

    fun saveSettings(s: Settings) = db.transaction {
        putKv("set.targetPct", s.targetPct.toString())
        putKv("set.alarm", s.alarm.toString())
        putKv("set.mode", s.mode.toString())
        putKv("set.heatWarnDc", s.heatWarnDc.toString())
        putKv("set.summary", s.summary.toString())
        putKv("set.onboarded", s.onboarded.toString())
        putKv("set.liveChip", s.liveChip.toString())
        putKv("set.statusIcon", s.statusIcon.toString())
    }

    // ---------------- Geräteprofil ----------------
    fun loadProfile(): Profile =
        raw.rawQuery("SELECT i_to_ma, v_to_mv, p_to_mw, sign, cells, p_src, source, rev FROM device_profile WHERE id = 1", null).use {
            if (!it.moveToFirst()) Profile()
            else Profile(it.getDouble(0), it.getDouble(1), it.getDouble(2), it.getInt(3), it.getInt(4), it.getInt(5), it.getInt(6), it.getInt(7))
        }

    fun saveProfile(p: Profile, fingerprint: String?) {
        raw.execSQL(
            "UPDATE device_profile SET i_to_ma=?, v_to_mv=?, p_to_mw=?, sign=?, cells=?, p_src=?, source=?, rev=?, fingerprint=? WHERE id = 1",
            arrayOf(p.iToMa, p.vToMv, p.pToMw, p.sign, p.cells, p.pSrc, p.source, p.rev, fingerprint)
        )
    }

    fun profileFingerprint(): String? =
        raw.rawQuery("SELECT fingerprint FROM device_profile WHERE id = 1", null).use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }

    // ---------------- Spans (Abdeckung) ----------------
    fun openSpan(boot: Int, mode: Int, nowUtc: Long): Long {
        // Offen gebliebene Spans: am letzten Heartbeat als „Kill“ schließen.
        raw.execSQL("UPDATE span SET end_reason = 1 WHERE end_reason IS NULL")
        return raw.compileStatement("INSERT INTO span (boot, mode, start_ts, end_ts) VALUES (?, ?, ?, ?)").use { st ->
            st.bindLong(1, boot.toLong()); st.bindLong(2, mode.toLong()); st.bindLong(3, nowUtc); st.bindLong(4, nowUtc)
            st.executeInsert()
        }
    }

    fun heartbeat(span: Long, nowUtc: Long) {
        raw.execSQL("UPDATE span SET end_ts = ? WHERE id = ? AND end_ts < ?", arrayOf(nowUtc, span, nowUtc))
    }

    fun closeSpan(span: Long, reason: Int, nowUtc: Long) {
        raw.execSQL("UPDATE span SET end_ts = MAX(end_ts, ?), end_reason = ? WHERE id = ?", arrayOf(nowUtc, reason, span))
    }

    // ---------------- Samples ----------------
    fun insertSamples(samples: List<Pair<Reading, Int>>) {
        if (samples.isEmpty()) return
        db.transaction {
            raw.compileStatement(
                "INSERT INTO sample (span, ts_utc, elapsed_ms, tz_min, level_pm, status, plug, screen, current_raw, voltage_raw, power_raw, counter_raw, temp_dc, q)" +
                    " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
            ).use { st ->
                for ((r, q) in samples) {
                    st.clearBindings()
                    st.bindLong(1, r.span); st.bindLong(2, r.utc); st.bindLong(3, r.t); st.bindLong(4, r.tzMin.toLong())
                    st.bindLong(5, r.levelPm.toLong()); st.bindLong(6, r.status.toLong()); st.bindLong(7, r.plug.toLong())
                    r.screen?.let { st.bindLong(8, it.toLong()) } ?: st.bindNull(8)
                    r.currentRaw?.let { st.bindLong(9, it) } ?: st.bindNull(9)
                    r.voltageRaw?.let { st.bindLong(10, it) } ?: st.bindNull(10)
                    r.powerRaw?.let { st.bindLong(11, it) } ?: st.bindNull(11)
                    r.counterRaw?.let { st.bindLong(12, it) } ?: st.bindNull(12)
                    r.tempDc?.let { st.bindLong(13, it.toLong()) } ?: st.bindNull(13)
                    st.bindLong(14, q.toLong())
                    st.executeInsert()
                }
            }
        }
    }

    fun lastSampleId(): Long = db.queryLong("SELECT MAX(id) FROM sample") ?: 0L

    /** Letzter Messpunkt (für die Wiederaufnahme nach Dienst-Neustart). */
    fun lastReading(): Reading? = raw.rawQuery(
        "SELECT s.span, s.ts_utc, s.elapsed_ms, s.tz_min, s.level_pm, s.status, s.plug, sp.boot FROM sample s JOIN span sp ON sp.id = s.span ORDER BY s.id DESC LIMIT 1",
        null
    ).use {
        if (!it.moveToFirst()) null
        else Reading(
            t = it.getLong(2), utc = it.getLong(1), boot = it.getInt(7), tzMin = it.getInt(3), span = it.getLong(0),
            levelPm = it.getInt(4), status = it.getInt(5), plug = it.getInt(6), screen = null,
            currentRaw = null, voltageRaw = null, powerRaw = null, counterRaw = null, tempDc = null,
        )
    }

    // ---------------- Ladesitzungen ----------------
    fun openSessionFor(last: Reading?): Engine.Session? = raw.rawQuery(
        "SELECT id, start_ts, start_level_pm, plug, klass, start_known, target_reached_ts FROM charge_session WHERE end_ts IS NULL ORDER BY id DESC LIMIT 1",
        null
    ).use {
        if (!it.moveToFirst() || last == null) return@use null
        val startUtc = it.getLong(1)
        Engine.Session(last.t - (last.utc - startUtc), startUtc, it.getInt(2), it.getInt(3), it.getInt(5) == 1).apply {
            dbId = it.getLong(0)
            klass = it.getInt(4)
            reached = !it.isNull(6)
            if (reached) reachedUtc = it.getLong(6)
            lastLevelPm = last.levelPm
            lastPct = last.levelPm / 10
            lastT = last.t
            lastUtc = last.utc
        }
    }

    fun insertSession(s: Engine.Session, span: Long, targetPct: Int): Long {
        raw.execSQL(
            "INSERT OR IGNORE INTO charge_session (span, start_ts, start_level_pm, plug, klass, start_known, target_pct) VALUES (?,?,?,?,?,?,?)",
            arrayOf(span, s.startUtc, s.startLevelPm, s.plug, s.klass, if (s.startKnown) 1 else 0, targetPct)
        )
        return db.queryLong("SELECT id FROM charge_session WHERE start_ts = ?", listOf(s.startUtc)) ?: 0L
    }

    fun updateSession(s: Engine.Session) {
        if (s.dbId == 0L) return
        raw.execSQL(
            "UPDATE charge_session SET klass=?, end_level_pm=?, e_raw=?, pk_max_raw=?, pk_min_raw=?, target_reached_ts=COALESCE(target_reached_ts, ?) WHERE id=?",
            arrayOf(s.klass, s.lastLevelPm, s.eRaw, s.pkMaxRaw, s.pkMinRaw, s.reachedUtc, s.dbId)
        )
    }

    fun markTargetReached(s: Engine.Session, utc: Long) {
        if (s.dbId != 0L) raw.execSQL("UPDATE charge_session SET target_reached_ts = COALESCE(target_reached_ts, ?) WHERE id = ?", arrayOf(utc, s.dbId))
    }

    fun endSession(s: Engine.Session, endUtc: Long, reason: Int) {
        updateSession(s)
        if (s.dbId != 0L) raw.execSQL("UPDATE charge_session SET end_ts = ?, end_reason = ? WHERE id = ?", arrayOf(endUtc, reason, s.dbId))
    }

    fun addPoint(s: Engine.Session, r: Reading) {
        if (s.dbId == 0L) return
        val count = db.queryLong("SELECT COUNT(*) FROM session_point WHERE session = ?", listOf(s.dbId)) ?: 0
        if (count >= 120) return
        raw.execSQL(
            "INSERT OR REPLACE INTO session_point (session, t_off_s, level_pm, current_raw, voltage_raw, power_raw) VALUES (?,?,?,?,?,?)",
            arrayOf(s.dbId, (r.utc - s.startUtc) / 1000, r.levelPm, r.currentRaw, r.voltageRaw, r.powerRaw)
        )
    }

    // ---------------- Ladeprofil (Lernen) ----------------
    fun loadChargeProfile(): Map<Triple<Int, Int, Int>, Double> =
        raw.rawQuery("SELECT plug, klass, pct, sec_per_pct FROM charge_profile", null).use {
            val m = HashMap<Triple<Int, Int, Int>, Double>()
            while (it.moveToNext()) m[Triple(it.getInt(0), it.getInt(1), it.getInt(2))] = it.getDouble(3)
            m
        }

    fun learn(s: Engine.Session) = db.transaction {
        for ((pct, sec) in s.stepSec) {
            if (sec <= 0 || sec > 3600) continue
            val old = raw.rawQuery(
                "SELECT sec_per_pct, n FROM charge_profile WHERE plug=? AND klass=? AND pct=?",
                arrayOf(s.plug.toString(), s.klass.toString(), pct.toString())
            ).use { if (it.moveToFirst()) it.getDouble(0) to it.getInt(1) else null }
            val value = if (old == null) sec else old.first + 0.3 * (sec - old.first)
            raw.execSQL(
                "INSERT OR REPLACE INTO charge_profile (plug, klass, pct, sec_per_pct, n) VALUES (?,?,?,?,?)",
                arrayOf(s.plug, s.klass, pct, value, (old?.second ?: 0) + 1)
            )
        }
    }

    // ---------------- Lernmodell (charge_model) ----------------
    fun loadModel(): ChargeModel {
        val m = ChargeModel()
        raw.rawQuery("SELECT kind, pct, value, n FROM charge_model", null).use {
            while (it.moveToNext()) {
                val kind = it.getInt(0); val p = it.getInt(1)
                if (p !in 0..99) continue
                if (kind == 0) { m.energy[p] = it.getDouble(2); m.energyN[p] = it.getInt(3) }
                else if (kind in 1..3) { m.shape[kind][p] = it.getDouble(2); m.shapeN[kind][p] = it.getInt(3) }
            }
        }
        m.sessions = kv("sys.model.sessions")?.toIntOrNull() ?: 0
        m.bias = kv("sys.model.bias")?.toDoubleOrNull() ?: 0.0
        m.biasN = kv("sys.model.biasN")?.toIntOrNull() ?: 0
        return m
    }

    fun saveModel(m: ChargeModel) = db.transaction {
        raw.execSQL("DELETE FROM charge_model")
        raw.compileStatement("INSERT INTO charge_model (kind, pct, value, n) VALUES (?,?,?,?)").use { st ->
            fun put(kind: Int, p: Int, v: Double, n: Int) {
                if (n <= 0) return
                st.clearBindings(); st.bindLong(1, kind.toLong()); st.bindLong(2, p.toLong()); st.bindDouble(3, v); st.bindLong(4, n.toLong())
                st.executeInsert()
            }
            for (p in 0 until 100) {
                put(0, p, m.energy[p], m.energyN[p])
                for (k in 1..3) put(k, p, m.shape[k][p], m.shapeN[k][p])
            }
        }
        putKv("sys.model.sessions", m.sessions.toString())
        putKv("sys.model.bias", m.bias.toString())
        putKv("sys.model.biasN", m.biasN.toString())
    }

    /**
     * Prozentschritte früherer Ladevorgänge aus session_point (für das einmalige Nachlernen nach dem Update).
     * Liefert je abgeschlossenem Ladevorgang (älteste zuerst) Klasse und Schritte.
     */
    fun historicSteps(): List<Pair<Int, List<ChargeModel.Step>>> {
        val sessions = raw.rawQuery(
            "SELECT id, klass FROM charge_session WHERE end_ts IS NOT NULL AND end_reason = 0 ORDER BY start_ts", null
        ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0) to c.getInt(1)) } }
        return sessions.map { (id, klass) ->
            val pts = raw.rawQuery(
                "SELECT t_off_s, level_pm, current_raw, voltage_raw, power_raw FROM session_point WHERE session = ? ORDER BY t_off_s",
                arrayOf(id.toString())
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val prod = when {
                            !c.isNull(4) -> c.getDouble(4)
                            !c.isNull(2) && !c.isNull(3) -> c.getDouble(2) * c.getDouble(3)
                            else -> null
                        }
                        add(Triple(c.getLong(0), c.getInt(1) / 10, prod))
                    }
                }
            }
            val steps = mutableListOf<ChargeModel.Step>()
            var last: Triple<Long, Int, Double?>? = null
            var firstLevel = true // erster Stand ist meist nur teilweise geladen → nicht lernen
            for (p in pts) {
                val l = last
                if (l != null && p.second > l.second && firstLevel) firstLevel = false
                else if (l != null && p.second > l.second && l.third != null && p.third != null) {
                    val dp = p.second - l.second
                    val dt = (p.first - l.first).toDouble()
                    val e = (l.third!! + p.third!!) / 2 * dt / dp
                    if (dp <= 3) for (q in l.second until p.second) steps += ChargeModel.Step(q, dt / dp, e)
                }
                if (l == null || p.second != l.second) last = p
            }
            klass to steps
        }
    }

    // ---------------- Akku-Gesundheit (health_point) ----------------
    fun addHealthPoint(tsUtc: Long, capacityMah: Double, designMah: Double?, cycles: Int?, windowPm: Int) {
        raw.execSQL(
            "INSERT OR REPLACE INTO health_point (ts_utc, capacity_mah, design_mah, cycles, window_pm, method) VALUES (?,?,?,?,?,0)",
            arrayOf(tsUtc, capacityMah, designMah, cycles, windowPm)
        )
    }

    /**
     * Für frühere Ladevorgänge die Kapazität aus den Rohdaten (CHARGE_COUNTER in sample, 30 Tage) nachtragen.
     * Liefert (Zeitpunkt, ΔCounter roh, ΔLadestand Promille).
     */
    fun historicCapacityWindows(): List<Triple<Long, Long, Int>> = raw.rawQuery(
        "SELECT cs.end_ts, " +
            "(SELECT counter_raw FROM sample WHERE ts_utc BETWEEN cs.start_ts AND cs.end_ts AND counter_raw > 0 ORDER BY ts_utc DESC LIMIT 1) - " +
            "(SELECT counter_raw FROM sample WHERE ts_utc BETWEEN cs.start_ts AND cs.end_ts AND counter_raw > 0 ORDER BY ts_utc LIMIT 1), " +
            "(SELECT level_pm FROM sample WHERE ts_utc BETWEEN cs.start_ts AND cs.end_ts AND counter_raw > 0 ORDER BY ts_utc DESC LIMIT 1) - " +
            "(SELECT level_pm FROM sample WHERE ts_utc BETWEEN cs.start_ts AND cs.end_ts AND counter_raw > 0 ORDER BY ts_utc LIMIT 1) " +
            "FROM charge_session cs WHERE cs.end_ts IS NOT NULL ORDER BY cs.start_ts", null
    ).use { c ->
        buildList { while (c.moveToNext()) if (!c.isNull(1) && !c.isNull(2)) add(Triple(c.getLong(0), c.getLong(1), c.getInt(2))) }
    }

    // ---------------- Wartung ----------------
    /** Höchstens 1× in 24 h und nur beim Laden aufrufen. */
    fun maintenanceIfDue(nowUtc: Long): Boolean {
        val last = kv("sys.maintenance")?.toLongOrNull() ?: 0L
        if (nowUtc - last < Retention.DAY_MS) return false
        Retention(db, retentionSql).run(nowUtc)
        putKv("sys.maintenance", nowUtc.toString())
        return true
    }

    fun clearHistory() = db.transaction {
        for (t in listOf("sample", "span", "charge_session", "session_point", "charge_profile", "day_stat", "charge_model", "health_point")) {
            raw.execSQL("DELETE FROM $t")
        }
        raw.execSQL("DELETE FROM kv WHERE key LIKE 'sys.model.%'")
    }
}
