package de.mlampert.batterymanager.monitor

import de.mlampert.batterymanager.monitor.db.BatteryStore
import kotlin.math.max
import kotlin.math.min

/**
 * NUR Debug-Builds: füllt die Historie mit 7 Tagen synthetischer Daten (für UI-Tests und Screenshots).
 * Im Release liefert MonitorProvider.seedDemo null.
 */
object DemoSeeder {
    fun seed(store: BatteryStore, now: Long) {
        val raw = store.db.raw
        store.clearHistory()
        store.db.transaction {
            val minute = 60_000L
            val start = now - 7 * 24 * 60 * minute
            raw.execSQL("INSERT INTO span (id, boot, mode, start_ts, end_ts, end_reason) VALUES (900001, 1, 0, ?, ?, 1)", arrayOf(start, now - 30 * 60 * minute))
            raw.execSQL("INSERT INTO span (id, boot, mode, start_ts, end_ts) VALUES (900002, 1, 0, ?, ?)", arrayOf(now - 28 * 60 * minute, now))
            val st = raw.compileStatement(
                "INSERT INTO sample (span, ts_utc, elapsed_ms, tz_min, level_pm, status, plug, screen, current_raw, voltage_raw, temp_dc, q) VALUES (?,?,?,60,?,?,?,?,?,?,?,0)"
            )
            var level = 820.0
            var session: Long? = null
            var sessionStart = 0L
            var t = start
            var elapsed = 0L
            while (t < now) {
                val hour = ((t / (60 * minute)) % 24).toInt()
                val gap = t in (now - 30 * 60 * minute) until (now - 28 * 60 * minute)
                val charging = hour in 22..23 || hour in 0..1 || (hour == 13 && level < 600)
                val screen = if (hour in 7..22 && (t / (20 * minute)) % 3 != 0L) 1 else 0
                val pm: Double
                val current: Long
                if (charging && level < 1000) {
                    val p = level / 10
                    val w = when { p < 50 -> 25.0; p < 80 -> 18.0 - (p - 50) * 0.2; else -> max(3.0, 12.0 - (p - 80) * 0.45) }
                    pm = w * 1000
                    current = (pm / 4.2 * 1000).toLong()
                    level = min(1000.0, level + w * 0.11)
                } else if (charging) {
                    pm = 200.0; current = 50_000L
                } else {
                    val drain = if (screen == 1) 1.5 else 0.12
                    level = max(80.0, level - drain)
                    current = if (screen == 1) -650_000L else -55_000L
                    pm = current / 1000.0 * 3.9
                }
                val span = if (t >= now - 28 * 60 * minute) 900002L else 900001L
                if (!gap) {
                    st.clearBindings()
                    st.bindLong(1, span); st.bindLong(2, t); st.bindLong(3, elapsed); st.bindLong(4, level.toLong())
                    st.bindLong(5, if (charging) 2 else 3); st.bindLong(6, if (charging) 1 else 0); st.bindLong(7, screen.toLong())
                    st.bindLong(8, current); st.bindLong(9, if (charging) 4200 else 3900); st.bindLong(10, if (charging) 340 else 295)
                    st.executeInsert()
                }
                if (charging && session == null && !gap) {
                    sessionStart = t
                    raw.execSQL(
                        "INSERT INTO charge_session (span, start_ts, start_level_pm, plug, klass, start_known, target_pct) VALUES (?,?,?,1,3,1,80)",
                        arrayOf(span, t, level.toLong())
                    )
                    session = store.db.queryLong("SELECT id FROM charge_session WHERE start_ts = ?", listOf(t))
                }
                if (session != null) {
                    raw.execSQL(
                        "INSERT OR REPLACE INTO session_point (session, t_off_s, level_pm, current_raw, voltage_raw) VALUES (?,?,?,?,4200)",
                        arrayOf(session, (t - sessionStart) / 1000, level.toLong(), current)
                    )
                    if (!charging) {
                        val hours = (t - sessionStart) / 3_600_000.0
                        raw.execSQL(
                            "UPDATE charge_session SET end_ts=?, end_level_pm=?, end_reason=0, e_raw=?, pk_max_raw=?, pk_min_raw=0 WHERE id=?",
                            arrayOf(t, level.toLong(), 15_000.0 / 1e-6 * 3600 * hours, 25_000.0 / 4.2 * 1000 * 4200, session)
                        )
                        session = null
                    }
                }
                val step = if (charging) 5 * minute else 10 * minute
                t += step; elapsed += step
            }
            for (pct in 0 until 100) {
                val sec = when { pct < 50 -> 55.0; pct < 80 -> 75.0; else -> 75.0 + (pct - 80) * 12.0 }
                raw.execSQL("INSERT OR REPLACE INTO charge_profile (plug, klass, pct, sec_per_pct, n) VALUES (1, 3, ?, ?, 6)", arrayOf(pct, sec))
            }
        }
    }
}
