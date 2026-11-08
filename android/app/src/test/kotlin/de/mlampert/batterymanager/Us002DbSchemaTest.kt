// US-002: DB-Schema v1 & Abfragen als SQL-Dateien (Akzeptanzvertrag AC-006, AC-007, AC-008, AC-010)
package de.mlampert.batterymanager

import de.mlampert.batterymanager.monitor.db.Migrator
import de.mlampert.batterymanager.monitor.db.Retention
import de.mlampert.batterymanager.monitor.db.SqlScript
import de.mlampert.batterymanager.testutil.Fixtures
import de.mlampert.batterymanager.testutil.JdbcSqlDb
import de.mlampert.batterymanager.testutil.Spec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Us002DbSchemaTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // AC-006: 001_init.sql legt alle Tabellen und Views an und nutzt nur SQLite-3.9-Syntax.
    @Test
    fun ac006() {
        Spec.freshDb().use { db ->
            val objects = db.rows("SELECT type, name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'")
                .map { "${it["type"]}:${it["name"]}" }.toSet()
            val tables = listOf("span", "sample", "charge_session", "session_point", "charge_profile",
                "device_profile", "day_stat", "kv")
            val views = listOf("pf", "sample_n", "point_n", "session_n")
            tables.forEach { assertTrue("Tabelle $it fehlt", "table:$it" in objects) }
            views.forEach { assertTrue("View $it fehlt", "view:$it" in objects) }
            val sampleCols = db.rows("PRAGMA table_info(sample)").map { it["name"] }
            assertTrue("sample.tz_min fehlt", "tz_min" in sampleCols)
            assertEquals(1L, db.queryLong("SELECT COUNT(*) FROM device_profile"))
        }
        // Statische Prüfung: keine Syntax, die neuer als SQLite 3.9 (Android 7 / API 24) ist.
        val forbidden = listOf(
            Regex("""\bOVER\s*\(""", RegexOption.IGNORE_CASE) to "Window-Funktion (3.25)",
            Regex("""\bON\s+CONFLICT\b[^;]*\bDO\b""", RegexOption.IGNORE_CASE) to "UPSERT (3.24)",
            Regex("""\bRETURNING\b""", RegexOption.IGNORE_CASE) to "RETURNING (3.35)",
            Regex("""\bSTRICT\b""", RegexOption.IGNORE_CASE) to "STRICT (3.37)",
            Regex("""\bIIF\s*\(""", RegexOption.IGNORE_CASE) to "IIF (3.32)",
            Regex("""\bGENERATED\s+ALWAYS\b""", RegexOption.IGNORE_CASE) to "Generierte Spalte (3.31)",
            Regex("""->>?""") to "JSON-Operator (3.38)",
            Regex("""\bNULLS\s+(FIRST|LAST)\b""", RegexOption.IGNORE_CASE) to "NULLS FIRST/LAST (3.30)",
            Regex("""\bFILTER\s*\(\s*WHERE\b""", RegexOption.IGNORE_CASE) to "Aggregat-FILTER (3.30)",
            Regex("""\bDROP\s+COLUMN\b""", RegexOption.IGNORE_CASE) to "DROP COLUMN (3.35)",
            Regex("""\bRENAME\s+COLUMN\b""", RegexOption.IGNORE_CASE) to "RENAME COLUMN (3.25)",
        )
        for (file in Spec.allSqlFiles()) {
            val code = SqlScript.split(file.readText()).joinToString(";\n")
            for ((re, what) in forbidden) {
                assertTrue("${file.name}: $what ist auf API 24 nicht verfügbar", !re.containsMatchIn(code))
            }
        }
    }

    // AC-007: Migrator setzt user_version, ist idempotent und sichert vor jeder Migration.
    @Test
    fun ac007() {
        JdbcSqlDb.file(tmp.newFile("m.db")).use { db ->
            val scripts = Spec.schemaScripts()
            val backups = mutableListOf<Int>()

            assertEquals(1, Migrator(db, scripts).migrate { backups += it })
            assertEquals(1L, db.queryLong("PRAGMA user_version"))
            assertEquals("auto_vacuum muss INCREMENTAL (2) sein", 2L, db.queryLong("PRAGMA auto_vacuum"))
            assertTrue("Neuanlage braucht keine Sicherung", backups.isEmpty())

            // Zweiter Lauf: nichts zu tun, keine Sicherung.
            assertEquals(1, Migrator(db, scripts).migrate { backups += it })
            assertTrue(backups.isEmpty())

            // Neue Version: Sicherung mit alter Version, dann Migration.
            val v2 = scripts + Migrator.Script(2, "002_test.sql", "CREATE TABLE t2 (x INTEGER); -- Test;")
            assertEquals(2, Migrator(db, v2).migrate { backups += it })
            assertEquals(listOf(1), backups)
            assertEquals(2L, db.queryLong("PRAGMA user_version"))
        }
        assertEquals(1, Migrator.versionOf("001_init.sql"))
        assertEquals(null, Migrator.versionOf("init.sql"))
    }

    // AC-008: sample_n liefert für jede Golden-Fixture exakt expect.normalized.
    @Test
    fun ac008() {
        val fixtures = Fixtures.all()
        assertTrue("keine Fixtures gefunden", fixtures.isNotEmpty())
        for (fx in fixtures) {
            Spec.freshDb().use { db ->
                val p = fx.profile
                db.update(
                    "UPDATE device_profile SET i_to_ma=?, v_to_mv=?, p_to_mw=?, sign=?, cells=?, p_src=?",
                    listOf(p.iToMa, p.vToMv, p.pToMw, p.sign, p.cells, p.pSrc)
                )
                fx.samples.forEachIndexed { i, s ->
                    db.update(
                        "INSERT INTO sample (id, span, ts_utc, elapsed_ms, tz_min, level_pm, status, plug, screen," +
                            " current_raw, voltage_raw, power_raw, counter_raw, temp_dc, q)" +
                            " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        listOf(i + 1, s.span, s.utc, s.t, s.tz, s.levelPm, s.status, s.plug, s.screen,
                            s.currentRaw, s.voltageRaw, s.powerRaw, s.counterRaw, s.tempDc, s.q)
                    )
                }
                val rows = db.rows("SELECT current_ma, voltage_mv, power_mw, temp_dc FROM sample_n ORDER BY id")
                assertEquals(fx.samples.size, rows.size)
                rows.forEachIndexed { i, r ->
                    val want = fx.normalized[i]
                    val got = listOf(r["current_ma"], r["voltage_mv"], r["power_mw"], r["temp_dc"])
                        .map { (it as Number?)?.toLong() }
                    assertEquals("${fx.id} Sample $i", listOf(want.currentMa, want.voltageMv, want.powerMw, want.tempDc), got)
                }
            }
        }
    }

    // AC-010: Retention löscht Rohdaten > 30 Tage in 5k-Batches, Sitzungen/Tage bleiben, Speicher wird freigegeben.
    @Test
    fun ac010() {
        JdbcSqlDb.file(tmp.newFile("r.db")).use { db ->
            Migrator(db, Spec.schemaScripts()).migrate()
            db.pragma("PRAGMA journal_mode = WAL")
            val now = 1_762_430_400_000L
            val old = now - 40 * Retention.DAY_MS
            val recent = now - 1 * Retention.DAY_MS
            db.update("INSERT INTO span (id, boot, mode, start_ts, end_ts, end_reason) VALUES (1,1,0,?,?,0)", listOf(old, old + 7000 * 1000L))
            db.update("INSERT INTO span (id, boot, mode, start_ts, end_ts) VALUES (2,2,0,?,?)", listOf(recent, now))
            db.transaction {
                for (i in 0 until 7000) {
                    db.update("INSERT INTO sample (span, ts_utc, elapsed_ms, level_pm, status, plug) VALUES (1,?,?,500,3,0)",
                        listOf(old + i * 1000L, i * 1000L))
                }
                for (i in 0 until 10) {
                    db.update("INSERT INTO sample (span, ts_utc, elapsed_ms, level_pm, status, plug) VALUES (2,?,?,600,3,0)",
                        listOf(recent + i * 1000L, i * 1000L))
                }
            }
            db.update("INSERT INTO charge_session (id, span, start_ts, start_level_pm, plug, klass, start_known) VALUES (1,1,?,200,1,2,1)", listOf(old))
            db.update("INSERT INTO session_point (session, t_off_s, level_pm) VALUES (1,0,200)")
            db.update("INSERT INTO day_stat (day, cov_s) VALUES ('2025-09-27', 7000)")

            val r = Retention(db, Spec.query("retention.sql")).run(now)

            assertEquals(7000, r.samplesDeleted)
            assertEquals(1, r.spansDeleted)
            assertEquals(10L, db.queryLong("SELECT COUNT(*) FROM sample"))
            assertEquals(0L, db.queryLong("SELECT COUNT(*) FROM sample WHERE ts_utc < ?", listOf(now - 30 * Retention.DAY_MS)))
            assertEquals(1L, db.queryLong("SELECT COUNT(*) FROM span"))
            assertEquals(1L, db.queryLong("SELECT COUNT(*) FROM charge_session"))
            assertEquals(1L, db.queryLong("SELECT COUNT(*) FROM session_point"))
            assertEquals(1L, db.queryLong("SELECT COUNT(*) FROM day_stat"))
        }
    }
}
