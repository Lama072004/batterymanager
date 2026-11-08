-- Batterymanager – Schema v1
-- Einzige Schemaquelle: Der Kotlin-Dienst im Prozess :monitor ist einziger Schreiber und Migrator,
-- die Flutter-UI liest read-only. Versionierung über PRAGMA user_version (setzt der Migrator).
-- Nur SQLite-3.9-Teilmenge (minSdk 24): keine Window-Funktionen, kein UPSERT, kein RETURNING,
-- kein STRICT, kein IIF, keine generierten Spalten.
-- PRAGMA auto_vacuum = INCREMENTAL setzt der Migrator VOR diesem Skript;
-- journal_mode = WAL und synchronous = NORMAL setzt der Dienst beim Öffnen.
--
-- Einheiten: Zeiten in ms (ts_utc = Unix-Epoch UTC, elapsed_ms = SystemClock.elapsedRealtime),
-- Ladestand in Promille (level_pm, 0..1000), Temperatur in Zehntel-°C (temp_dc).
-- *_raw sind UNVERÄNDERTE Gerätewerte; normalisiert wird ausschließlich in den Views über device_profile.

-- Aufzeichnungsintervall (Abdeckung). Lücken = Zeit zwischen Spans; nie aus Sample-Dichte erraten.
CREATE TABLE span (
  id          INTEGER PRIMARY KEY,
  boot        INTEGER NOT NULL,              -- Settings.Global.BOOT_COUNT
  mode        INTEGER NOT NULL,              -- 0 Immer aktiv, 1 Nur beim Laden
  start_ts    INTEGER NOT NULL,
  end_ts      INTEGER NOT NULL,              -- letzter Commit (Heartbeat)
  end_reason  INTEGER                        -- NULL offen, 0 sauber, 1 Kill, 2 Reboot, 3 Moduswechsel, 4 Uhrsprung
);

-- Rohmesswerte. q = Qualitäts-Bits (siehe spec/engine/spec.md):
--   1 OUT_OF_RANGE, 2 WARMUP, 4 PAUSE, 8 SINGLE_POINT, 16 SIGN_CONFLICT, 32 ESTIMATED
CREATE TABLE sample (
  id           INTEGER PRIMARY KEY,
  span         INTEGER NOT NULL,
  ts_utc       INTEGER NOT NULL,
  elapsed_ms   INTEGER NOT NULL,
  tz_min       INTEGER NOT NULL DEFAULT 0,   -- Zeitzonen-Offset in Minuten zum Messzeitpunkt
  level_pm     INTEGER NOT NULL,
  status       INTEGER NOT NULL,             -- BatteryManager.BATTERY_STATUS_*
  plug         INTEGER NOT NULL,             -- 0 nicht eingesteckt, sonst BATTERY_PLUGGED_*
  screen       INTEGER,                      -- 1 an, 0 aus, NULL unbekannt
  current_raw  INTEGER,                      -- CURRENT_NOW wie geliefert (NULL = nicht unterstützt)
  voltage_raw  INTEGER,                      -- EXTRA_VOLTAGE wie geliefert
  power_raw    INTEGER,                      -- nur falls das Gerät Leistung direkt liefert
  counter_raw  INTEGER,                      -- CHARGE_COUNTER wie geliefert
  temp_dc      INTEGER,                      -- EXTRA_TEMPERATURE (Zehntel-°C)
  q            INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX sample_ts ON sample(ts_utc);

-- Ladevorgang (unbegrenzt aufbewahrt, ca. 2 KB je Vorgang inkl. session_point).
CREATE TABLE charge_session (
  id                 INTEGER PRIMARY KEY,
  span               INTEGER NOT NULL,
  start_ts           INTEGER NOT NULL UNIQUE,
  end_ts             INTEGER,
  start_level_pm     INTEGER NOT NULL,
  end_level_pm       INTEGER,
  plug               INTEGER NOT NULL,
  klass              INTEGER NOT NULL,       -- Leistungsklasse: 0 unbekannt, 1 <=7,5 W, 2 <=18 W, 3 >18 W
  start_known        INTEGER NOT NULL,       -- 0 = Beginn nicht erfasst (Modus Nur beim Laden)
  target_pct         INTEGER,
  target_reached_ts  INTEGER,
  end_reason         INTEGER,                -- 0 abgesteckt, 1 Kill, 2 Reboot, 3 Moduswechsel
  e_raw              REAL,                   -- Summe Rohprodukt * Sekunden (Energie, normalisiert in session_n)
  pk_max_raw         REAL,                   -- größtes Rohprodukt (Peak bei sign > 0)
  pk_min_raw         REAL,                   -- kleinstes Rohprodukt (Peak bei sign < 0)
  eta_err_s          INTEGER                 -- Qualitätsmaß der ETA (Ist - Prognose bei halber Restzeit)
);

-- Höchstens 120 Punkte je Ladevorgang für Graph 2 (rückwirkend normalisierbar).
CREATE TABLE session_point (
  session      INTEGER NOT NULL,
  t_off_s      INTEGER NOT NULL,
  level_pm     INTEGER NOT NULL,
  current_raw  INTEGER,
  voltage_raw  INTEGER,
  power_raw    INTEGER,
  PRIMARY KEY (session, t_off_s)
) WITHOUT ROWID;

-- Gelernte Ladekurve, einheitenfrei (Sekunden je Prozentpunkt).
CREATE TABLE charge_profile (
  plug         INTEGER NOT NULL,
  klass        INTEGER NOT NULL,
  pct          INTEGER NOT NULL,
  sec_per_pct  REAL NOT NULL,
  n            INTEGER NOT NULL,
  PRIMARY KEY (plug, klass, pct)
) WITHOUT ROWID;

-- Geräteprofil (genau eine Zeile). i_to_ma = 0 bedeutet: Strom wird nicht unterstützt.
CREATE TABLE device_profile (
  id        INTEGER PRIMARY KEY CHECK (id = 1),
  i_to_ma   REAL NOT NULL DEFAULT 0.001,      -- Default: CURRENT_NOW in µA
  v_to_mv   REAL NOT NULL DEFAULT 1,          -- Default: EXTRA_VOLTAGE in mV
  p_to_mw   REAL NOT NULL DEFAULT 1,
  sign      INTEGER NOT NULL DEFAULT 1,       -- +1: positiv = in den Akku
  cells     INTEGER NOT NULL DEFAULT 1,       -- 2 = Dual-Cell (Zellspannung gemeldet)
  p_src     INTEGER NOT NULL DEFAULT 0,       -- 0 I*U, 1 power_raw
  source    INTEGER NOT NULL DEFAULT 0,       -- 0 Default, 1 automatisch erkannt, 2 manuell
  rev       INTEGER NOT NULL DEFAULT 0,       -- erhöht sich bei jeder Änderung (Cache-Schlüssel)
  fingerprint TEXT                            -- Build-Fingerprint, für den gelernt wurde
);

-- Tagesaggregat (unbegrenzt). Lokale Tagesgrenzen über tz_min.
CREATE TABLE day_stat (
  day           TEXT PRIMARY KEY,            -- YYYY-MM-DD (lokal)
  cov_s         INTEGER,                     -- abgedeckte Sekunden
  on_s          INTEGER,
  off_s         INTEGER,
  unk_s         INTEGER,
  drain_on_pm   INTEGER,                     -- Entladung in Promille bei Bildschirm an
  drain_off_pm  INTEGER,
  drain_unk_pm  INTEGER,
  e_on_raw      REAL,
  e_off_raw     REAL,
  charge_n      INTEGER,
  max_temp_dc   INTEGER
);

-- Einstellungen (set.*) und Systemcursor (sys.*). Schreiber ist nur der Dienst.
CREATE TABLE kv (
  key    TEXT PRIMARY KEY,
  value  TEXT NOT NULL
);

INSERT INTO device_profile (id) VALUES (1);

-- Umrechnungsfaktor Rohprodukt -> mW.
CREATE VIEW pf AS
SELECT sign, i_to_ma, v_to_mv, p_to_mw, cells, p_src, rev,
       CASE p_src WHEN 1 THEN p_to_mw ELSE i_to_ma * v_to_mv * cells / 1000.0 END AS f
FROM device_profile;

-- Normalisierte Messwerte. Unplausible Werte werden NULL (UI zeigt „–“, nie 0):
-- |I| > 15 A, |P| > 150 W, U außerhalb 2,5–10 V (dann auch P), T außerhalb −20…+70 °C, Strom nicht unterstützt.
CREATE VIEW sample_n AS
SELECT x.id, x.span, x.ts_utc, x.elapsed_ms, x.tz_min, x.level_pm, x.status, x.plug, x.screen, x.q,
       CASE WHEN x.c BETWEEN -15000 AND 15000 THEN x.c END AS current_ma,
       CASE WHEN x.v BETWEEN 2500 AND 10000 THEN x.v END AS voltage_mv,
       CASE WHEN x.p BETWEEN -150000 AND 150000
             AND (x.p_src = 1 OR x.v BETWEEN 2500 AND 10000) THEN x.p END AS power_mw,
       CASE WHEN x.temp_dc BETWEEN -200 AND 700 THEN x.temp_dc END AS temp_dc,
       x.rev AS profile_rev
FROM (
  SELECT s.*, p.rev, p.p_src,
         CASE WHEN p.i_to_ma = 0 THEN NULL
              ELSE CAST(ROUND(s.current_raw * p.i_to_ma * p.sign) AS INTEGER) END AS c,
         CAST(ROUND(s.voltage_raw * p.v_to_mv) AS INTEGER) AS v,
         CASE WHEN p.p_src = 1 THEN CAST(ROUND(p.sign * p.f * s.power_raw) AS INTEGER)
              WHEN p.i_to_ma = 0 THEN NULL
              ELSE CAST(ROUND(p.sign * p.f * s.current_raw * s.voltage_raw) AS INTEGER) END AS p
  FROM sample s CROSS JOIN pf p
) x;

-- Normalisierte Punkte je Ladevorgang (Graph 2).
CREATE VIEW point_n AS
SELECT x.session, x.t_off_s, x.level_pm,
       CASE WHEN x.c BETWEEN -15000 AND 15000 THEN x.c END AS current_ma,
       CASE WHEN x.v BETWEEN 2500 AND 10000 THEN x.v END AS voltage_mv,
       CASE WHEN x.p BETWEEN -150000 AND 150000
             AND (x.p_src = 1 OR x.v BETWEEN 2500 AND 10000) THEN x.p END AS power_mw,
       x.rev AS profile_rev
FROM (
  SELECT sp.*, p.rev, p.p_src,
         CASE WHEN p.i_to_ma = 0 THEN NULL
              ELSE CAST(ROUND(sp.current_raw * p.i_to_ma * p.sign) AS INTEGER) END AS c,
         CAST(ROUND(sp.voltage_raw * p.v_to_mv) AS INTEGER) AS v,
         CASE WHEN p.p_src = 1 THEN CAST(ROUND(p.sign * p.f * sp.power_raw) AS INTEGER)
              WHEN p.i_to_ma = 0 THEN NULL
              ELSE CAST(ROUND(p.sign * p.f * sp.current_raw * sp.voltage_raw) AS INTEGER) END AS p
  FROM session_point sp CROSS JOIN pf p
) x;

-- Normalisierte Ladevorgänge: Energie in mWh, Peak in mW.
CREATE VIEW session_n AS
SELECT cs.id, cs.span, cs.start_ts, cs.end_ts, cs.start_level_pm, cs.end_level_pm, cs.plug, cs.klass,
       cs.start_known, cs.target_pct, cs.target_reached_ts, cs.end_reason, cs.eta_err_s,
       CASE WHEN p.p_src = 0 AND p.i_to_ma = 0 THEN NULL
            ELSE ROUND(p.sign * p.f * cs.e_raw / 3600.0, 1) END AS energy_mwh,
       CASE WHEN p.p_src = 0 AND p.i_to_ma = 0 THEN NULL
            WHEN p.sign > 0 THEN CAST(ROUND(p.f * cs.pk_max_raw) AS INTEGER)
            ELSE CAST(ROUND(-p.f * cs.pk_min_raw) AS INTEGER) END AS peak_mw,
       p.rev AS profile_rev
FROM charge_session cs CROSS JOIN pf p;
