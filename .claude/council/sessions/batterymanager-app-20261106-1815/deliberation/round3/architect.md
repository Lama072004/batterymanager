## Architect Final Position — Gesamtarchitektur Batterymanager

**Überarbeitete Empfehlung:** Pro Plattform gibt es genau einen Schreiber: Android den Kotlin-Dienst in `:monitor`, Desktop die reine Dart-Engine. Er besitzt eine SQLite-Datei (WAL) mit Rohwerten, expliziter Abdeckung und gelerntem Ladeprofil; das Schema existiert nur als SQL-Dateien in `spec/db/`, normalisiert wird beim Lesen über Views. Die UI liest read-only (`sqflite`), steuert per `ContentProvider.call()` und erhält Live-Daten per `Messenger` nur, solange sie sichtbar ist. Den Engine-Kern gibt es zweimal (Kotlin, Dart), spezifiziert allein durch geteilte Golden-Fixtures.

**Zugeständnisse:**
- **Kein Binär-Journal:** Die Engine braucht ihre Historie (Ladeprofil, Session-Wiederaufnahme), also besitzt der Produzent die DB. Mein Korruptionsargument galt nur für zwei SQLite-Libs in einem Prozess.
- **Kein drift:** Als reiner Leser wäre es eine dritte Schemakopie.
- **Pigeon** nur noch zwischen Dart und Kotlin im UI-Prozess.
- **Kein Desktop-Daemon**, außer das 30-mW-Gate reißt.
- **Kurven-BLOB** wird zur Tabelle `session_point`, damit auch Graph 2 rückwirkend korrigierbar bleibt.

**Non-negotiables:**
1. **Ein Schreiber und Migrator pro Plattform.** Schema nur aus `spec/db/NNN_*.sql` über `user_version`. Der UI-Prozess schreibt nie in die DB, in `:monitor` läuft kein Flutter- oder Plugin-Code.
2. **Rohwerte + `device_profile` + Views.** Der Kotlin-Live-Pfad nutzt dieselbe Profilzeile, Fixtures belegen die Gleichheit.
3. **Abdeckung explizit über `span`.** Lücken werden nie aus der Sample-Dichte abgeleitet und nie interpoliert; Raten nur aus `elapsed_ms` innerhalb eines Spans.
4. **Golden-Fixtures in `spec/engine/` sind die Spezifikation.** Kotlin- und Dart-Suite müssen beide grün sein.
5. **Entkopplung.** Der Ziel-Alarm hängt nur am %-Übergang, das Ladeprofil ist einheitenfrei (s/%). Ein falsches Geräteprofil verdirbt so weder Alarm noch Lernkurve.

---

### Implementierungshinweise

**Schema v1** (`spec/db/001_init.sql`): nur die SQLite-3.9-Teilmenge, unabhängig vom minSdk. Vorab `auto_vacuum=INCREMENTAL`, zur Laufzeit `WAL` und `synchronous=NORMAL`.
```sql
CREATE TABLE span (id INTEGER PRIMARY KEY, boot INTEGER NOT NULL,
  mode INTEGER NOT NULL,                 -- 0 Dauer, 1 nur Laden, 2 Desktop
  start_ts INTEGER NOT NULL, end_ts INTEGER NOT NULL, -- end_ts = letzter Commit (Heartbeat)
  end_reason INTEGER);                   -- NULL offen, 0 sauber, 1 Kill, 2 Reboot, 3 Moduswechsel
CREATE TABLE sample (id INTEGER PRIMARY KEY, span INTEGER NOT NULL,
  ts_utc INTEGER NOT NULL, elapsed_ms INTEGER NOT NULL, level_pm INTEGER NOT NULL,
  status INTEGER NOT NULL, plug INTEGER NOT NULL, screen INTEGER,     -- NULL = unbekannt
  current_raw INTEGER, voltage_raw INTEGER, power_raw INTEGER, counter_raw INTEGER,
  temp_dc INTEGER, q INTEGER NOT NULL DEFAULT 0); -- Bits: implausibel|Warmup|Pause|Einzelpunkt
CREATE INDEX sample_ts ON sample(ts_utc);
CREATE TABLE charge_session (id INTEGER PRIMARY KEY, span INTEGER NOT NULL,
  start_ts INTEGER NOT NULL UNIQUE, end_ts INTEGER, start_level_pm INTEGER NOT NULL,
  end_level_pm INTEGER, plug INTEGER NOT NULL, klass INTEGER NOT NULL,
  start_known INTEGER NOT NULL, target_pct INTEGER, target_reached_ts INTEGER,
  end_reason INTEGER, e_raw REAL, pk_max_raw REAL, pk_min_raw REAL, eta_err_s INTEGER);
CREATE TABLE session_point (session INTEGER NOT NULL, t_off_s INTEGER NOT NULL,
  level_pm INTEGER NOT NULL, current_raw INTEGER, voltage_raw INTEGER, power_raw INTEGER,
  PRIMARY KEY (session, t_off_s)) WITHOUT ROWID;                   -- ≤120 je Session
CREATE TABLE charge_profile (plug INTEGER, klass INTEGER, pct INTEGER,
  sec_per_pct REAL NOT NULL, n INTEGER NOT NULL, PRIMARY KEY (plug, klass, pct)) WITHOUT ROWID;
CREATE TABLE device_profile (id INTEGER PRIMARY KEY CHECK (id = 1),
  i_to_ma REAL NOT NULL DEFAULT 0.001, v_to_mv REAL NOT NULL DEFAULT 1,
  p_to_mw REAL NOT NULL DEFAULT 1, sign INTEGER NOT NULL DEFAULT 1,
  cells INTEGER NOT NULL DEFAULT 1, p_src INTEGER NOT NULL DEFAULT 0, -- 0 I·U, 1 power_raw
  source INTEGER NOT NULL DEFAULT 0, rev INTEGER NOT NULL DEFAULT 0); -- Default|auto|manuell
CREATE TABLE day_stat (day TEXT PRIMARY KEY, cov_s INTEGER, on_s INTEGER, off_s INTEGER,
  unk_s INTEGER, drain_on_pm INTEGER, drain_off_pm INTEGER, drain_unk_pm INTEGER,
  e_on_raw REAL, e_off_raw REAL, charge_n INTEGER, max_temp_dc INTEGER);
CREATE TABLE kv (key TEXT PRIMARY KEY, value TEXT NOT NULL);  -- 'set.*' Settings, 'sys.*' Cursor
INSERT INTO device_profile (id) VALUES (1);
CREATE VIEW pf AS SELECT sign, i_to_ma, v_to_mv, p_src,
  CASE p_src WHEN 1 THEN p_to_mw ELSE i_to_ma * v_to_mv * cells / 1000.0 END AS f
  FROM device_profile;
CREATE VIEW sample_n AS SELECT s.*,
  CAST(s.current_raw * p.i_to_ma * p.sign AS INTEGER) AS current_ma,
  CAST(s.voltage_raw * p.v_to_mv AS INTEGER) AS voltage_mv,
  CAST(p.sign * p.f * CASE p.p_src WHEN 1 THEN s.power_raw
       ELSE s.current_raw * s.voltage_raw END AS INTEGER) AS power_mw
  FROM sample s CROSS JOIN pf p;
-- point_n analog; session_n: energy_mwh = sign*f*e_raw/3600,
-- peak_mw = CASE WHEN sign > 0 THEN f*pk_max_raw ELSE -f*pk_min_raw END
```
**Settings** liegen in `kv` (`set.*`). Es schreibt nur der Provider bzw. die Desktop-Engine, die UI liest sie über `getState`.

**Retention und Downsampling:**
- **Aufbewahrung:** `sample` und `span` 30 Tage. Alles andere unbegrenzt (ca. 2 KB je Ladevorgang).
- **Täglich beim ersten Lade-Commit** (dann zahlt das Netzteil):
  1. `q/day_rollup.sql`: Deltas per Self-Join `b.id = a.id + 1 AND b.span = a.span`. Die ids sind lückenlos, weil nur alte Zeilen gelöscht werden.
  2. `DELETE` in 5k-Batches.
  3. `incremental_vacuum`, dann `wal_checkpoint(TRUNCATE)`.
- **„Heute“** berechnet die UI über den SELECT-Teil derselben Datei.
- **Graphen** (`q/series.sql`): `GROUP BY span, ts_utc / :bucket` mit MIN/MAX(level) und AVG(power).
  - Buckets: 24 h → 3 min, 7 d → 15 min.
  - Höchstens 600 Punkte; die Linie bricht an Span-Grenzen.

**ETA-Skizze:**
1. **Warmup:** 90 s „wird berechnet“. Danach `klass` aus der Warmup-Leistung (≤ 7,5 / ≤ 18 / > 18 W; ohne Strom 0).
2. **Vorrang:** Die Zustände „Pause/Limit“ und „entlädt trotz Kabel“ ersetzen die ETA.
3. **Live-Rate:** EWMA der Zeit je %-Schritt (einheitenfrei). Der Strom dient nur der Interpolation und der CC→CV-Erkennung.
4. **Restzeit** = Σ `sec_per_pct[plug, klass, p]` · k, mit k = EWMA(Live- zu Profilrate), begrenzt auf 0,5–2.
   - Fehlende Buckets: bis 80 % CC mit Live-Rate, darüber linear steigend auf 3×.
5. **Konfidenz** = min(1, n̄/5) · Abdeckung · (1 − Streuung von k).
   - ab 0,7: 5-min-Stufen
   - ab 0,4: „ca.“ in 15-min-Stufen
   - sonst: „—“
   - Hysterese: Änderung erst ab |Δ| > max(1 min, 10 %).
6. **Lernen:** bei SessionEnd in derselben Transaktion, nur lückenlose %-Schritte ohne Pause, EMA mit α = 0,3. `eta_err_s` dient der Qualitätsmessung.

**Ordnerstruktur:**
```
spec/db/{001_init.sql, q/*.sql}       spec/engine/{spec.md, fixtures/*.json}
lib/ main.dart · app/ · core/{model, format, backend/battery_backend.dart}
  data/{db_reader, history_repository}.dart          # read-only, q/*.sql als Assets
  engine/   # reines Dart: normalizer, profile_detector, session_machine, eta, target_alarm, migrator, writer
  platform/android/{pigeon.g, android_backend}.dart
  platform/desktop/{desktop_backend, source_linux, source_windows, tray, notifier}.dart
  features/{dashboard, history, sessions, settings}/ · ui/charts/
android/app/src/main/kotlin/<id>/
  App.kt (Prozessprüfung) · ui/HostApiImpl.kt (Pigeon → call()/Messenger)
  monitor/{MonitorService, ChargeJob, WatchdogJob, BootReceiver, EngineProvider}.kt
  monitor/{engine, collect, db, notify}/
```
Gradle bindet `../../spec/db` als Assets ein, `pubspec` ebenso.

**`ContentProvider.call()`** (Authority `<id>.engine`, nicht exportiert, jedes Bundle mit `v=1`):

| Methode | Eingabe → Rückgabe |
|---|---|
| `getState` | – → Settings, Capabilities, `schema`, `profileRev`, offener Span, Job-Latenz |
| `putSettings` | targetPct?, alarmMask, mode, heatWarnDc, etaOn → angewandte Settings / `err` |
| `setProfile` | iToMa, sign, vToMv, cells \| `auto` → `rev` |
| `flush` | – → letzte `sample.id` |
| `snapshot` | – → Einzelpunkt schreiben (Modus b) + Snapshot |

Eine unbekannte Methode liefert `null`; die UI zeigt dann „Dienst veraltet“.

**Messenger:**
- Nachrichten (`what`): 1 REGISTER, 2 UNREGISTER, 10 SNAPSHOT.
- Inhalt: `ts, levelPm, status, plug, screen?, powerMw?, currentMa?, voltageMv?, tempDc?, etaState, etaFullS?, etaTargetS?, etaConf, sessionStartTs?, peakMw?, energyMwh?, profileRev`.
- Push nur bei Änderung, höchstens 1 Hz und nur, solange ein Client registriert ist.
- `DesktopBackend` implementiert denselben Port `BatteryBackend` im selben Prozess.

**Desktop und Fixtures:**
- `lib/engine/` ist der Dart-Zwilling von `monitor/engine/`: gleiche Namen, Zustände und Konstanten (aus `spec.md`).
- Fixture-Format: `{profile, samples:[…Rohwerte…], expect:{normalized, events, eta:[{atMs, etaS, tolPct}], profileAfter}}`.
- Quellen: Spike-Mitschnitte und Skeptics P0-Fälle.

Geprüft wird auf drei Ebenen:
1. **Engine-Logik:** in JUnit und `dart test`.
2. **SQL:** Der Migrator legt eine In-Memory-DB an, `sample_n` muss `expect.normalized` liefern. In Dart über `sqflite_common_ffi`, in Kotlin einmal auf einem API-24-Emulator.
3. **Live == View:** Der Kotlin-`Normalizer` muss dieselben Erwartungswerte liefern.

Auf dem Desktop schreibt die Engine mit denselben SQL-Dateien; die UI liest über eine zweite Read-only-Connection.
