-- Batterymanager – Schema v2: lernendes Restzeit-Modell und Akku-Gesundheit.

-- Restzeit-Modell (siehe ChargeModel.kt). kind 0 = Energie je Prozentpunkt (|Rohprodukt|·s),
-- kind 1..3 = Log-Leistungskurve der Leistungsklasse. Einheitenfrei (nur Verhältnisse zählen).
CREATE TABLE charge_model (
  kind   INTEGER NOT NULL,
  pct    INTEGER NOT NULL,
  value  REAL NOT NULL,
  n      INTEGER NOT NULL,
  PRIMARY KEY (kind, pct)
) WITHOUT ROWID;

-- Kapazitätsmessungen (unbegrenzt, höchstens eine je Ladevorgang). Grundlage für den Gesundheits-Trend.
-- method 0 = Ladefenster: ΔCHARGE_COUNTER ÷ ΔLadestand über ≥ 15 Prozentpunkte.
CREATE TABLE health_point (
  ts_utc        INTEGER PRIMARY KEY,
  capacity_mah  REAL NOT NULL,
  design_mah    REAL,                         -- Nennkapazität laut Hersteller, falls bekannt
  cycles        INTEGER,                      -- Ladezyklen laut Android (ab Android 14)
  window_pm     INTEGER NOT NULL,             -- gemessener Ladebereich in Promille
  method        INTEGER NOT NULL DEFAULT 0
);
