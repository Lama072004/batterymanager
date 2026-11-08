// Generated from acceptance contract — US-002: DB-Schema v1 & Abfragen als SQL-Dateien
package de.mlampert.batterymanager

import org.junit.Test

class Us002DbSchemaTest {
    // AC-006: `spec/db/001_init.sql` legt `span`, `sample` (inkl. `tz_min`), `charge_session`, `session_point`, `charge_profile`, `device_profile`, `day_stat`, `kv` und die Views `pf`, `sample_n`, `point_n`, `session_n` an und nutzt nur SQLite-3.9-Syntax.
    // GIVEN the precondition of the criterion WHEN the behaviour is exercised THEN the criterion holds
    @Test
    fun ac006() {
        throw NotImplementedError("Not implemented — AC-006 pending")
    }

    // AC-007: Der Migrator setzt `user_version`, ist idempotent und legt vor jeder Migration eine Kopie der DB an.
    // GIVEN the precondition of the criterion WHEN the behaviour is exercised THEN the criterion holds
    @Test
    fun ac007() {
        throw NotImplementedError("Not implemented — AC-007 pending")
    }

    // AC-008: `sample_n` liefert für jede Golden-Fixture exakt `expect.normalized`.
    // GIVEN the precondition of the criterion WHEN the behaviour is exercised THEN the criterion holds
    @Test
    fun ac008() {
        throw NotImplementedError("Not implemented — AC-008 pending")
    }

    // AC-010: Retention löscht `sample`/`span` älter als 30 Tage in 5k-Batches; `charge_session`, `session_point` und `day_stat` bleiben erhalten; danach `incremental_vacuum` und `wal_checkpoint(TRUNCATE)`.
    // GIVEN the precondition of the criterion WHEN the behaviour is exercised THEN the criterion holds
    @Test
    fun ac010() {
        throw NotImplementedError("Not implemented — AC-010 pending")
    }

}
